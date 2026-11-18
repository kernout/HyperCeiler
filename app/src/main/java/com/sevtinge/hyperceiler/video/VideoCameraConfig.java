/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Range;
import android.util.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import android.hardware.camera2.CaptureRequest;

/** Strict regular-session support: one encoder surface, no hidden preview or lens fallback. */
final class VideoCameraConfig {
    static final int WIDTH = 1920, HEIGHT = 1080, FPS = 60, BITRATE = 20_000_000;
    static final Range<Integer> FPS_RANGE = new Range<>(FPS, FPS);
    final String id;
    final String physicalId;
    final CameraCharacteristics characteristics;
    final CameraCharacteristics logicalCharacteristics;
    final int sensorOrientation;
    final boolean physicalFpsKey;

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
        List<CaptureRequest.Key<?>> physicalKeys = carrier.getAvailablePhysicalCameraRequestKeys();
        physicalFpsKey = physicalId != null && physicalKeys != null
                && physicalKeys.contains(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
    }

    static VideoCameraConfig select(CameraManager manager) throws Exception {
        Set<String> publicIds = new HashSet<>(Arrays.asList(manager.getCameraIdList()));
        Map<String, CameraCharacteristics> metadata = new HashMap<>();
        for (String id : publicIds) metadata.put(id, manager.getCameraCharacteristics(id));
        // Hidden physical devices are legal characteristics queries when named by a public
        // logical parent. Never attempt to open a hidden ID directly.
        for (String id : publicIds) {
            for (String physical : metadata.get(id).getPhysicalCameraIds()) {
                if (!metadata.containsKey(physical)) metadata.put(physical, manager.getCameraCharacteristics(physical));
            }
        }
        List<VideoMainCameraPolicy.Camera> descriptors = new ArrayList<>();
        for (Map.Entry<String, CameraCharacteristics> entry : metadata.entrySet()) {
            CameraCharacteristics c = entry.getValue();
            Integer role = null;
            int[] roles = null;
            for (CameraCharacteristics.Key<?> key : c.getKeys()) {
                if (!key.getName().equals("com.xiaomi.cameraid.role.cameraId")
                        && !key.getName().equals("com.xiaomi.cameraid.role.cameraIds")) continue;
                Object value = read(c, key);
                if (value instanceof Integer) role = (Integer) value;
                if (value instanceof int[]) roles = (int[]) value;
            }
            descriptors.add(new VideoMainCameraPolicy.Camera(entry.getKey(),
                    Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING)),
                    logical(c), role, roles, c.getPhysicalCameraIds()));
        }
        VideoMainCameraPolicy.Route route = VideoMainCameraPolicy.select(descriptors, publicIds);
        CameraCharacteristics target = metadata.get(route.targetId());
        CameraCharacteristics carrier = metadata.get(route.openId);
        VideoCameraConfig selected = new VideoCameraConfig(route, target, carrier);
        validateNormal60(target, "MAIN " + route.targetId());
        if (route.physicalId != null) validateNormal60(carrier, "Logical carrier " + route.openId);
        validateEncoder();
        return selected;
    }
    @SuppressWarnings("unchecked")
    private static Object read(CameraCharacteristics c, CameraCharacteristics.Key<?> key) {
        return c.get((CameraCharacteristics.Key<Object>) key);
    }
    private static boolean contains(int[] values, int value) {
        if (values != null) for (int item : values) if (item == value) return true;
        return false;
    }
    private static boolean logical(CameraCharacteristics c) {
        return contains(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
    }
    private static void validateNormal60(CameraCharacteristics characteristics, String label) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new IllegalStateException(label + " stream configuration missing");
        Size wanted = new Size(WIDTH, HEIGHT);
        boolean size = false, range = false;
        Size[] sizes = map.getOutputSizes(ImageFormat.PRIVATE);
        if (sizes != null) for (Size item : sizes) size |= wanted.equals(item);
        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) for (Range<Integer> item : ranges) range |= FPS_RANGE.equals(item);
        long duration = size ? map.getOutputMinFrameDuration(ImageFormat.PRIVATE, wanted) : 0;
        if (!VideoRecordingPolicy.normal60(size, range, duration))
            throw new IllegalStateException(label + " lacks regular 1920x1080 [60,60] with <=16.667ms frame duration; "
                    + "high-speed-only modes unsupported (no 30fps/lens fallback)");
    }
    private static void validateEncoder() {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        boolean usable = false;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video != null && video.areSizeAndRateSupported(WIDTH, HEIGHT, FPS)
                        && video.getBitrateRange().contains(BITRATE) && caps.isFormatSupported(format)) usable = true;
            } catch (IllegalArgumentException ignored) { /* Non-AVC codec. */ }
        }
        if (!usable) throw new IllegalStateException("No surface H264 encoder supports 1080p60 at 20Mbps");
        // MediaRecorder chooses the encoder itself. Its prepare/start can still fail; never retry lower.
    }
    static int orientationHint(int sensorOrientation, int clockwiseDeviceDegrees) {
        int rounded = clockwiseDeviceDegrees < 0 ? 0 : ((clockwiseDeviceDegrees + 45) / 90 * 90) % 360;
        return (sensorOrientation + rounded) % 360;
    }
}
