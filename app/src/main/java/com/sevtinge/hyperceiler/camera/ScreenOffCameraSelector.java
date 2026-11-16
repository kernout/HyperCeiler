/*
 * This file is part of HyperCeiler.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.camera;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Size;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Conservative role-based selection; never assumes the first back camera is the main one. */
final class ScreenOffCameraSelector {
    private static final String TAG = "HCScreenOffCamera";
    private static final String ROLE = "com.xiaomi.cameraid.role.cameraId";
    private static final String ROLES = "com.xiaomi.cameraid.role.cameraIds";
    private static final long MAX_JPEG_PIXELS = 16_000_000L;

    static final class Selection {
        final String id;
        final CameraCharacteristics characteristics;
        final Size jpeg;
        final Size preview;

        Selection(String id, CameraCharacteristics characteristics, Size jpeg, Size preview) {
            this.id = id;
            this.characteristics = characteristics;
            this.jpeg = jpeg;
            this.preview = preview;
        }
    }

    private static final class Candidate {
        final String id;
        final CameraCharacteristics characteristics;
        final boolean physical;
        boolean roleAvailable;
        int rank;

        Candidate(String id, CameraCharacteristics characteristics, boolean physical) {
            this.id = id;
            this.characteristics = characteristics;
            this.physical = physical;
        }
    }

    static Selection select(CameraManager manager) throws CameraAccessException {
        String[] publicIds = manager.getCameraIdList();
        List<Candidate> back = new ArrayList<>();
        Set<String> physicalIds = new HashSet<>();
        // Only publicly enumerated IDs can be opened directly. A hidden physical ID must not
        // be substituted for its logical parent without a physical-output request protocol.
        for (String id : publicIds) {
            physicalIds.addAll(manager.getCameraCharacteristics(id).getPhysicalCameraIds());
        }
        for (String id : publicIds) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            if (!Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(
                    c.get(CameraCharacteristics.LENS_FACING))) continue;
            boolean logical = contains(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
            Candidate candidate = new Candidate(id, c, physicalIds.contains(id) || !logical);
            readRole(candidate);
            back.add(candidate);
        }
        List<Candidate> verified = new ArrayList<>();
        boolean anyRoleAvailable = false;
        int bestRank = 0;
        boolean hasPhysical = false;
        for (Candidate c : back) {
            anyRoleAvailable |= c.roleAvailable;
            if (c.rank > 0) {
                verified.add(c);
                hasPhysical |= c.physical;
            }
        }
        // Prefer a directly openable physical sub-camera over a logical multi-camera.
        for (Candidate c : verified) {
            if (!hasPhysical || c.physical) bestRank = Math.max(bestRank, c.rank);
        }
        Candidate chosen = null;
        for (Candidate c : verified) {
            if ((!hasPhysical || c.physical) && c.rank == bestRank) {
                if (chosen != null) throw new IllegalStateException("Ambiguous role=0 back cameras");
                chosen = c;
            }
        }
        if (chosen == null) {
            if (anyRoleAvailable || back.size() != 1) {
                throw new IllegalStateException("Cannot verify main back camera: back count="
                        + back.size() + ", vendor role available=" + anyRoleAvailable);
            }
            chosen = back.get(0);
            Log.w(TAG, "Vendor main-camera role unavailable; using the only public back camera");
        }
        CameraCharacteristics c = chosen.characteristics;
        if (!contains(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
            throw new IllegalStateException("Verified main camera is not backward compatible");
        }
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new IllegalStateException("Main camera has no output configuration");
        Size jpeg = chooseJpeg(map.getOutputSizes(ImageFormat.JPEG));
        Size preview = choosePreview(map.getOutputSizes(SurfaceTexture.class), jpeg);
        Log.i(TAG, "Verified main back camera=" + chosen.id + ", physical=" + chosen.physical
                + ", role source=" + (chosen.rank == 2 ? "cameraId" : chosen.rank == 1
                ? "cameraIds" : "unique back fallback"));
        return new Selection(chosen.id, c, jpeg, preview);
    }

    @SuppressWarnings("unchecked")
    private static void readRole(Candidate candidate) {
        Integer role = null;
        int[] roles = null;
        for (CameraCharacteristics.Key<?> key : candidate.characteristics.getKeys()) {
            if (!ROLE.equals(key.getName()) && !ROLES.equals(key.getName())) continue;
            try {
                Object value = candidate.characteristics.get((CameraCharacteristics.Key<Object>) key);
                if (ROLE.equals(key.getName()) && value instanceof Integer) role = (Integer) value;
                else if (ROLES.equals(key.getName()) && value instanceof int[]) roles = (int[]) value;
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot read main-camera vendor role for " + candidate.id, e);
            }
        }
        candidate.roleAvailable = role != null || roles != null;
        candidate.rank = mainRoleRank(role, roles);
    }

    static int mainRoleRank(Integer role, int[] roles) {
        // Xiaomi's role mapper uses cameraIds when present, and cameraId only as fallback.
        // A stale/default scalar 0 must not override an explicit non-main roles array.
        if (roles != null) return contains(roles, 0) ? 1 : 0;
        return Integer.valueOf(0).equals(role) ? 2 : 0;
    }

    static int jpegOrientation(int sensorOrientation, int deviceOrientation) {
        int rounded = deviceOrientation < 0 ? 0 : ((deviceOrientation + 45) / 90 * 90) % 360;
        // Only back-facing cameras reach this selector. Device orientation is clockwise.
        return (sensorOrientation + rounded) % 360;
    }

    static boolean contains(int[] values, int wanted) {
        if (values != null) for (int value : values) if (value == wanted) return true;
        return false;
    }

    private static long area(Size size) {
        return (long) size.getWidth() * size.getHeight();
    }

    private static boolean fourByThree(Size s) {
        return Math.abs(s.getWidth() * 3L - s.getHeight() * 4L) <= 8;
    }

    private static Size chooseJpeg(Size[] sizes) {
        Size best = null;
        if (sizes != null) for (Size s : sizes) {
            if (s.getWidth() <= 0 || s.getHeight() <= 0 || area(s) > MAX_JPEG_PIXELS) continue;
            if (best == null || (fourByThree(s) && !fourByThree(best))
                    || (fourByThree(s) == fourByThree(best) && area(s) > area(best))) best = s;
        }
        if (best == null) throw new IllegalStateException("No JPEG output at or below 16 MP");
        return best;
    }

    private static Size choosePreview(Size[] sizes, Size jpeg) {
        Size best = null;
        double bestScore = Double.MAX_VALUE;
        double ratio = (double) jpeg.getWidth() / jpeg.getHeight();
        if (sizes != null) for (Size s : sizes) {
            if (s.getWidth() <= 0 || s.getHeight() <= 0 || s.getWidth() > 1920
                    || s.getHeight() > 1080 || area(s) > 1_000_000L) continue;
            double score = Math.abs((double) s.getWidth() / s.getHeight() - ratio) * 100
                    + Math.abs(Math.log((double) area(s) / (640 * 480)));
            if (score < bestScore) {
                best = s;
                bestScore = score;
            }
        }
        if (best == null) throw new IllegalStateException("No bounded offscreen preview size");
        return best;
    }

    private ScreenOffCameraSelector() {}
}
