/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Range;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sevtinge.hyperceiler.video.VideoDiagnostics.Event.*;
import static com.sevtinge.hyperceiler.video.VideoDiagnostics.Failure.*;

/** Strict regular-session support: one encoder surface, no hidden preview or lens fallback. */
final class VideoCameraConfig {
    static final int WIDTH = 1920, HEIGHT = 1080, FPS = 60, BITRATE = 20_000_000;
    static final Range<Integer> FPS_RANGE = new Range<>(FPS, FPS);
    // ROM metadata: cameraId is int32[1], cameraIds is int32[N]. Android's scalar
    // vendor-key representation varies: read cameraId as Integer, then int[1].
    private static final CameraCharacteristics.Key<int[]> MAIN_ROLES =
            new CameraCharacteristics.Key<>("com.xiaomi.cameraid.role.cameraIds", int[].class);
    private static final CameraCharacteristics.Key<Integer> MAIN_ROLE =
            new CameraCharacteristics.Key<>("com.xiaomi.cameraid.role.cameraId", Integer.class);
    private static final CameraCharacteristics.Key<int[]> MAIN_ROLE_ARRAY =
            new CameraCharacteristics.Key<>("com.xiaomi.cameraid.role.cameraId", int[].class);
    final String id;
    final String physicalId;
    final CameraCharacteristics characteristics;
    final CameraCharacteristics logicalCharacteristics;
    final int sensorOrientation;
    final boolean physicalFpsKey;
    final boolean logicalFpsResultKey, physicalFpsResultKey;

    private VideoCameraConfig(VideoMainCameraPolicy.Route route, CameraCharacteristics target,
            CameraCharacteristics carrier) {
        id = route.openId;
        physicalId = route.physicalId;
        characteristics = target;
        logicalCharacteristics = carrier;
        Integer orientation = target.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (orientation == null || orientation % 90 != 0)
            throw new IllegalStateException("Main camera orientation unavailable");
        sensorOrientation = orientation;
        List<CaptureRequest.Key<?>> physicalKeys = physicalId == null ? null
                : carrier.getAvailablePhysicalCameraRequestKeys();
        physicalFpsKey = physicalId != null && physicalKeys != null
                && physicalKeys.contains(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
        List<CaptureResult.Key<?>> carrierResults = carrier.getAvailableCaptureResultKeys();
        List<CaptureResult.Key<?>> targetResults = target == carrier ? carrierResults : target.getAvailableCaptureResultKeys();
        // Null means unknown, not "not advertised". Do not silently disable FPS echoes.
        if (carrierResults == null || targetResults == null)
            throw new IllegalStateException("Main camera result keys unavailable");
        logicalFpsResultKey = carrierResults.contains(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE);
        physicalFpsResultKey = targetResults.contains(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE);
    }

    static VideoCameraConfig select(CameraManager manager) throws Exception {
        return select(manager, null);
    }

    static VideoCameraConfig select(CameraManager manager, VideoDiagnostics diagnostics) throws Exception {
        VideoDiagnostics.Event stage = CAMERA_IDS;
        try {
            note(diagnostics, stage);
            Set<String> publicIds = new HashSet<>(Arrays.asList(manager.getCameraIdList()));
            Map<String, CameraCharacteristics> metadata = new LinkedHashMap<>();
            List<VideoMainCameraPolicy.Camera> descriptors = new ArrayList<>();
            stage = CAMERA_ROLES;
            note(diagnostics, stage);
            for (String id : publicIds) describe(manager, id, metadata, descriptors, diagnostics);

            // A verified direct MAIN wins by policy. No hidden metadata query is needed
            // in that case (some app UIDs cannot query hidden physical devices at all).
            boolean directMain = false;
            for (VideoMainCameraPolicy.Camera c : descriptors)
                directMain |= c.back && !c.logical && c.rank() > 0;
            if (!directMain) {
                stage = CAMERA_PHYSICAL_IDS;
                note(diagnostics, stage);
                List<VideoMainCameraPolicy.Camera> parents = new ArrayList<>(descriptors);
                for (VideoMainCameraPolicy.Camera parent : parents) {
                    if (!parent.back || !parent.logical) continue;
                    for (String physical : parent.physicalIds) {
                        if (!metadata.containsKey(physical))
                            describe(manager, physical, metadata, descriptors, diagnostics);
                    }
                }
            }
            stage = CAMERA_ROUTE;
            note(diagnostics, stage);
            VideoMainCameraPolicy.Route route;
            try {
                route = VideoMainCameraPolicy.select(descriptors, publicIds);
            } catch (VideoMainCameraPolicy.SelectionException e) {
                if (diagnostics != null) diagnostics.recordFailure(CAMERA_ROUTE_FAILED, switch (e.reason) {
                    case NO_MAIN -> MAIN_UNAVAILABLE;
                    case AMBIGUOUS_MAIN -> MAIN_AMBIGUOUS;
                    case AMBIGUOUS_CARRIER -> CARRIER_AMBIGUOUS;
                });
                throw e;
            }
            CameraCharacteristics target = metadata.get(route.targetId());
            CameraCharacteristics carrier = metadata.get(route.openId);
            stage = CAMERA_SELECTED_METADATA;
            note(diagnostics, stage);
            VideoCameraConfig selected = new VideoCameraConfig(route, target, carrier);
            stage = CAMERA_STREAM;
            note(diagnostics, stage);
            if (!normal60(target) || (route.physicalId != null && !normal60(carrier))) {
                if (diagnostics != null) diagnostics.recordFailure(CAMERA_STREAM_FAILED, STREAM_UNSUPPORTED);
                throw new IllegalStateException("MAIN/carrier lacks regular 1920x1080 [60,60] with <=16.667ms frame duration; no fallback");
            }
            stage = CAMERA_ENCODER;
            note(diagnostics, stage);
            if (!encoderSupported()) {
                if (diagnostics != null) diagnostics.recordFailure(CAMERA_ENCODER_FAILED, ENCODER_UNSUPPORTED);
                throw new IllegalStateException("No surface H264 encoder supports 1080p60 at 20Mbps");
            }
            note(diagnostics, CAMERA_PREFLIGHT_READY);
            return selected;
        } catch (Exception | LinkageError e) {
            // Stage contains no ID/role values or arbitrary exception text.
            if (diagnostics != null) diagnostics.record(stage, e);
            throw e;
        }
    }

    private static void note(VideoDiagnostics diagnostics, VideoDiagnostics.Event event) {
        if (diagnostics != null) diagnostics.record(event);
    }

    private static void describe(CameraManager manager, String id,
            Map<String, CameraCharacteristics> metadata, List<VideoMainCameraPolicy.Camera> descriptors,
            VideoDiagnostics diagnostics) {
        CameraCharacteristics c;
        try {
            c = manager.getCameraCharacteristics(id);
            metadata.put(id, c);
        } catch (Exception | LinkageError e) {
            if (diagnostics != null) diagnostics.record(CAMERA_METADATA_SKIPPED, e);
            return;
        }
        try {
            boolean back = Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK)
                    .equals(c.get(CameraCharacteristics.LENS_FACING));
            if (!back) return; // Front camera's optional vendor/stream metadata is irrelevant.
            boolean logical = contains(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
            int[] roles = optional(c, MAIN_ROLES);
            // Authoritative cameraIds array is never overwritten by cameraId, even
            // when empty or excluding MAIN. A broken array read is NOT scalar fallback.
            Integer role = roles == null ? scalarRole(c) : null;
            Set<String> physicalIds = Set.of();
            if (logical) {
                try {
                    Set<String> reported = c.getPhysicalCameraIds();
                    if (reported != null) physicalIds = reported;
                } catch (RuntimeException | LinkageError e) {
                    if (diagnostics != null) diagnostics.record(CAMERA_PHYSICAL_IDS_SKIPPED, e);
                }
            }
            descriptors.add(new VideoMainCameraPolicy.Camera(id, true, logical, role, roles, physicalIds));
        } catch (RuntimeException | LinkageError e) {
            // Only verified MAIN candidates participate. An unrelated camera's
            // broken optional metadata must not veto a fully verified unique MAIN.
            if (diagnostics != null) diagnostics.record(CAMERA_ROLES_SKIPPED, e);
        }
    }

    private static <T> T optional(CameraCharacteristics c, CameraCharacteristics.Key<T> key) {
        try {
            return c.get(key);
        } catch (IllegalArgumentException e) {
            // CameraMetadataNative uses this for an unregistered optional vendor tag.
            return null;
        }
    }

    private static Integer scalarRole(CameraCharacteristics c) {
        try {
            Integer value = optional(c, MAIN_ROLE);
            if (value != null) return value;
        } catch (ClassCastException ignored) { /* ROM returns int[1] for scalar typed key. */ }
        int[] values = optional(c, MAIN_ROLE_ARRAY);
        if (values == null) return null;
        if (values.length != 1) throw new IllegalArgumentException("Invalid MAIN scalar cardinality");
        return values[0];
    }

    private static boolean contains(int[] values, int value) {
        if (values != null) for (int item : values) if (item == value) return true;
        return false;
    }
    private static boolean normal60(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return false;
        Size wanted = new Size(WIDTH, HEIGHT);
        boolean size = false, range = false;
        Size[] sizes = map.getOutputSizes(ImageFormat.PRIVATE);
        if (sizes != null) for (Size item : sizes) size |= wanted.equals(item);
        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) for (Range<Integer> item : ranges) range |= FPS_RANGE.equals(item);
        long duration = size ? map.getOutputMinFrameDuration(ImageFormat.PRIVATE, wanted) : 0;
        return VideoRecordingPolicy.normal60(size, range, duration);
    }
    private static boolean encoderSupported() {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video != null && video.areSizeAndRateSupported(WIDTH, HEIGHT, FPS)
                        && video.getBitrateRange().contains(BITRATE) && caps.isFormatSupported(format)) return true;
            } catch (IllegalArgumentException ignored) { /* Non-AVC codec. */ }
        }
        // MediaRecorder chooses the encoder itself. prepare/start may still fail; never retry lower.
        return false;
    }
    static int orientationHint(int sensorOrientation, int clockwiseDeviceDegrees) {
        int rounded = clockwiseDeviceDegrees < 0 ? 0 : ((clockwiseDeviceDegrees + 45) / 90 * 90) % 360;
        return (sensorOrientation + rounded) % 360;
    }
}
