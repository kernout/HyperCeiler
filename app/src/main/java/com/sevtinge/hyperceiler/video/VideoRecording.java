/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.Surface;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.sevtinge.hyperceiler.video.VideoDiagnostics.Event.*;

/** One worker owns Camera2, recorder, FD, pending row and every cleanup operation. */
final class VideoRecording {
    interface Listener {
        void started(VideoRecording source, long actualStartElapsed);
        void finished(VideoRecording source, boolean saved, String reason);
    }
    private static final String TAG = "HCVolumeVideo";
    private static final String PREFIX = "HC_VIDEO_";
    private static final String PATH = Environment.DIRECTORY_DCIM + "/Camera/";
    static final long MAX_DURATION_MS = 2 * 60 * 60 * 1000L;
    private final Context context;
    private final int requestUser;
    private final Listener listener;
    private final VideoDiagnostics diagnostics;
    private boolean frameDiagnosed;
    private final HandlerThread thread = new HandlerThread("HCVideoWorker");
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final VideoResourceGate gate = new VideoResourceGate();
    private Handler worker;
    private CameraManager cameras;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private Surface encoder;
    private ParcelFileDescriptor output;
    private Uri pending;
    private AudioManager audio;
    private VideoCameraConfig config;
    private OrientationEventListener orientation;
    private volatile int deviceOrientation = -1;
    private volatile boolean finished;
    private boolean audioMonitor;
    private boolean nativeStarted;
    private boolean audioVerified;
    private long nativeStartedAt;
    private long audioMissingSince;
    private long lastCaptureAt;
    private long firstSensorAt;
    private long sensorFrames;
    private long lastSensorAt;
    private volatile long lastHealth;

    VideoRecording(Context context, int requestUser, Listener listener) {
        this.context = context;
        this.requestUser = requestUser;
        this.listener = listener;
        diagnostics = new VideoDiagnostics(context.getFilesDir());
    }
    void start() {
        try {
            diagnostics.record(WORKER_START);
            thread.start();
            worker = new Handler(thread.getLooper());
            if (!worker.post(this::begin)) throw new IllegalStateException("Video worker unavailable");
        } catch (RuntimeException e) {
            diagnostics.record(WORKER_FAILED, e);
            thread.quitSafely();
            listener.finished(this, false, "Cannot launch video worker: " + e);
            throw e;
        }
    }
    long lastHealth() { return lastHealth; }
    void stop(String reason) {
        diagnostics.record(STOP_REQUESTED);
        cancelled.set(true);
        Handler h = worker;
        if (h != null) h.post(() -> finish(reason, false));
    }
    private void allowed() throws IOException {
        if (cancelled.get()) throw new IOException("Video cancelled");
        UserManager users = context.getSystemService(UserManager.class);
        if (requestUser != Process.myUid() / 100_000 || users == null
                || !users.isUserForeground() || !users.isUserUnlocked())
            throw new SecurityException("Video user not foreground/unlocked");
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("CAMERA/RECORD_AUDIO missing or revoked");
        if (audio == null || audio.isMicrophoneMute()) throw new IOException("Microphone muted/unavailable");
    }
    private void begin() {
        try {
            diagnostics.record(PREFLIGHT);
            if (!gate.acquire()) throw new IOException("Previous video still owns camera/recorder");
            audio = context.getSystemService(AudioManager.class);
            allowed();
            cameras = context.getSystemService(CameraManager.class);
            if (cameras == null) throw new IOException("Camera service unavailable");
            config = VideoCameraConfig.select(cameras);
            diagnostics.record(CAMERA_SELECTED);
            orientation = new OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
                @Override public void onOrientationChanged(int degrees) { deviceOrientation = degrees; }
            };
            if (orientation.canDetectOrientation()) orientation.enable();
            deleteAbandonedPending();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, PREFIX + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, PATH);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            pending = context.getContentResolver().insert(collection(), values);
            if (pending == null) throw new IOException("MediaStore insert returned null");
            output = context.getContentResolver().openFileDescriptor(pending, "rw");
            if (output == null) throw new IOException("MediaStore FD unavailable");
            diagnostics.record(OUTPUT_PENDING);
            recorder = new MediaRecorder(context);
            recorder.setOnErrorListener((r, what, extra) -> post(() -> {
                diagnostics.record(RECORDER_ERROR);
                finish("MediaRecorder error " + what + "/" + extra, true);
            }));
            recorder.setOnInfoListener((r, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
                    post(() -> {
                        diagnostics.record(RECORDER_LIMIT);
                        finish("Recorder duration/file size limit reached", false);
                    });
            });
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(VideoCameraConfig.WIDTH, VideoCameraConfig.HEIGHT);
            recorder.setVideoFrameRate(VideoCameraConfig.FPS);
            recorder.setVideoEncodingBitRate(VideoCameraConfig.BITRATE);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(48_000);
            recorder.setAudioEncodingBitRate(128_000);
            recorder.setAudioChannels(1);
            recorder.setMaxDuration((int) MAX_DURATION_MS);
            recorder.setOutputFile(output.getFileDescriptor());
            allowed();
            recorder.setOrientationHint(VideoCameraConfig.orientationHint(config.sensorOrientation, deviceOrientation));
            diagnostics.record(RECORDER_PREPARE);
            recorder.prepare(); // If platform-selected H264 cannot prepare, fail without downgrade.
            diagnostics.record(RECORDER_PREPARED);
            encoder = recorder.getSurface();
            audio.registerAudioRecordingCallback(audioCallback, worker);
            audioMonitor = true;
            allowed();
            gate.openingCamera();
            diagnostics.record(CAMERA_OPEN_REQUEST);
            cameras.openCamera(config.id, this::executeCallback, deviceCallback);
        } catch (Exception e) {
            diagnostics.record(WORKER_FAILED, e);
            gate.cameraClosed(); // openCamera synchronous throws do not deliver a device.
            finish("Video preflight/prepare/open failed: " + e, true);
        }
    }
    private void post(Runnable runnable) {
        Handler h = worker;
        if (h != null) h.post(() -> { if (!finished) runnable.run(); });
    }
    private void executeCallback(Runnable runnable) {
        // Late opened/configured devices must be closed even after worker termination.
        Handler h = worker;
        if (h == null || !h.post(runnable)) runnable.run();
    }
    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) {
            if (finished) { device.close(); return; }
            camera = device;
            diagnostics.record(CAMERA_OPENED);
            try {
                allowed();
                OutputConfiguration outputConfig = new OutputConfiguration(encoder);
                if (config.physicalId != null) outputConfig.setPhysicalCameraId(config.physicalId);
                SessionConfiguration sessionConfig = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        Collections.singletonList(outputConfig), VideoRecording.this::executeCallback, sessionCallback);
                sessionConfig.setSessionParameters(recordRequest().build());
                try {
                    if (!camera.isSessionConfigurationSupported(sessionConfig))
                        throw new IOException("NORMAL encoder-only physical MAIN session is unsupported");
                } catch (UnsupportedOperationException unsupportedQuery) {
                    // Some HALs cannot preflight this public query. Actual onConfigured remains
                    // mandatory, with the identical explicit physical OutputConfiguration.
                    diagnostics.record(SESSION_QUERY_UNAVAILABLE);
                    Log.i(TAG, "Session support query unavailable; requiring actual NORMAL configuration");
                }
                diagnostics.record(SESSION_CREATE);
                camera.createCaptureSession(sessionConfig);
            } catch (Exception e) { diagnostics.record(SESSION_FAILED, e); finish("Video session setup failed: " + e, true); }
        }
        @Override public void onDisconnected(CameraDevice device) { deviceFailed(device, "Camera disconnected"); }
        @Override public void onError(CameraDevice device, int error) { deviceFailed(device, "Camera error " + error); }
        @Override public void onClosed(CameraDevice device) { gate.cameraClosed(); }
        private void deviceFailed(CameraDevice device, String reason) {
            diagnostics.record(CAMERA_FAILED);
            if (finished) { device.close(); return; }
            camera = device;
            finish(reason, false);
        }
    };
    private CaptureRequest.Builder recordRequest() throws android.hardware.camera2.CameraAccessException {
        // Only allocate per-physical settings when we actually override an advertised key.
        // The physical builder clones TEMPLATE_RECORD before logical set() calls; sending that
        // untouched clone can reintroduce template defaults. OutputConfiguration alone pins MAIN.
        CaptureRequest.Builder request = !config.physicalFpsKey
                ? camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                : camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, Collections.singleton(config.physicalId));
        request.addTarget(encoder);
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, VideoCameraConfig.FPS_RANGE);
        request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if (config.physicalFpsKey)
            request.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    VideoCameraConfig.FPS_RANGE, config.physicalId);
        // If per-physical FPS is not advertised, the parent's FPS applies to physical streams.
        // OutputConfiguration pins the lens regardless of the logical camera's active master.
        return request;
    }
    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
        @Override public void onConfigured(CameraCaptureSession configured) {
            if (finished) { configured.close(); return; }
            session = configured;
            diagnostics.record(SESSION_CONFIGURED);
            try {
                allowed();
                CaptureRequest.Builder request = recordRequest();
                diagnostics.record(REPEATING_REQUEST);
                session.setRepeatingRequest(request.build(), captureCallback, worker);
                allowed();
                diagnostics.record(RECORDER_START);
                recorder.start();
                // This timestamp must never be taken at gesture, prepare, open, or first callback.
                nativeStartedAt = SystemClock.elapsedRealtime();
                nativeStarted = true;
                diagnostics.record(RECORDER_STARTED);
                lastCaptureAt = nativeStartedAt;
                lastHealth = nativeStartedAt;
                listener.started(VideoRecording.this, nativeStartedAt);
                worker.post(health);
            } catch (Exception e) { diagnostics.record(WORKER_FAILED, e); finish("Video native start failed: " + e, true); }
        }
        @Override public void onConfigureFailed(CameraCaptureSession configured) {
            diagnostics.record(SESSION_FAILED);
            configured.close();
            if (!finished) finish("Encoder-only regular 1080p60 session rejected", true);
        }
    };
    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession source, CaptureRequest request, TotalCaptureResult result) {
            if (finished || !nativeStarted) return;
            CaptureResult targetResult = result;
            if (config.physicalId != null) {
                java.util.Map<String, TotalCaptureResult> physical = result.getPhysicalCameraTotalResults();
                if (!VideoMainCameraPolicy.physicalResultMatches(config.physicalId, physical.keySet())) {
                    diagnostics.record(PHYSICAL_RESULT_MISSING);
                    finish("Missing physical MAIN result for pinned ID " + config.physicalId, true); return;
                }
                targetResult = physical.get(config.physicalId);
            }
            // This ROM advertises FPS as a request key, but NOT as a result key.
            // Missing optional echoes are legal; require advertised echoes and reject any
            // contradictory echo. Physical timestamp cadence and encoded MP4 remain mandatory.
            if (!fpsEchoValid(result, config.logicalFpsResultKey)
                    || !fpsEchoValid(targetResult, config.physicalFpsResultKey)) {
                diagnostics.record(FPS_ECHO_FAILED);
                finish("MAIN camera did not honor [60,60]", true); return;
            }
            Long timestamp = targetResult.get(CaptureResult.SENSOR_TIMESTAMP);
            if (timestamp == null) { diagnostics.record(FRAME_TIMESTAMP_FAILED); finish("Camera frame timestamp unavailable", true); return; }
            if (!frameDiagnosed) { diagnostics.record(FIRST_FRAME); frameDiagnosed = true; }
            lastCaptureAt = SystemClock.elapsedRealtime();
            if (timestamp <= lastSensorAt && lastSensorAt != 0) {
                diagnostics.record(FRAME_TIMESTAMP_FAILED);
                finish("Camera timestamps not increasing", true); return;
            }
            lastSensorAt = timestamp;
            if (firstSensorAt == 0) { firstSensorAt = timestamp; sensorFrames = 0; }
            ++sensorFrames;
            if (timestamp - firstSensorAt >= 2_000_000_000L) {
                if (!VideoRecordingPolicy.measured60(sensorFrames, firstSensorAt / 1000, timestamp / 1000)) {
                    diagnostics.record(FRAME_CADENCE_FAILED);
                    finish("MAIN camera delivery is not sustained 60fps", true); return;
                }
                firstSensorAt = timestamp;
                sensorFrames = 1;
            }
        }
        @Override public void onCaptureFailed(CameraCaptureSession source, CaptureRequest request, CaptureFailure failure) {
            diagnostics.record(CAPTURE_FAILED);
            if (!finished) finish("Video capture failure " + failure.getReason(), false);
        }
    };
    private static boolean fpsEchoValid(CaptureResult result, boolean advertised) {
        android.util.Range<Integer> range = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE);
        return VideoRecordingPolicy.fpsEchoValid(advertised,
                range == null ? null : range.getLower(), range == null ? null : range.getUpper());
    }
    private final AudioManager.AudioRecordingCallback audioCallback = new AudioManager.AudioRecordingCallback() {
        @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> ignored) {
            if (nativeStarted && !finished) checkHealth();
        }
    };
    private final Runnable health = new Runnable() {
        @Override public void run() {
            if (finished) return;
            checkHealth();
            if (!finished) worker.postDelayed(this, 500);
        }
    };
    private void checkHealth() {
        if (finished || !nativeStarted) return;
        try {
            allowed();
            long now = SystemClock.elapsedRealtime();
            if (now - nativeStartedAt >= MAX_DURATION_MS) { finish("Two-hour video limit", false); return; }
            if (now - lastCaptureAt > 3_000) {
                diagnostics.record(FRAMES_STALLED);
                throw new IOException("Video frame delivery stalled");
            }
            AudioRecordingConfiguration own = recorder.getActiveRecordingConfiguration();
            if (own != null && own.isClientSilenced()) {
                diagnostics.record(MIC_SILENCED);
                throw new IOException("Android silenced the MIC (privacy/arbitration)");
            }
            if (own != null && own.getClientAudioSessionId() > 0) {
                if (!audioVerified) diagnostics.record(AUDIO_VERIFIED);
                audioVerified = true;
                audioMissingSince = 0;
            } else {
                if (audioMissingSince == 0) audioMissingSince = now;
                if (now - audioMissingSince >= (audioVerified ? 1000 : 3000)) {
                    diagnostics.record(MIC_SESSION_MISSING);
                    throw new IOException("Cannot verify recorder's own active MIC session");
                }
            }
            lastHealth = now;
        } catch (Exception e) { diagnostics.record(SAFETY_FAILED, e); finish("Video safety stop: " + e, false); }
    }
    private static Uri collection() { return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); }
    private void deleteAbandonedPending() throws IOException {
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "is_pending=1 AND owner_package_name=? AND relative_path=? AND _display_name LIKE ?");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {context.getPackageName(), PATH, PREFIX + "%"});
        args.putInt(ContentResolver.QUERY_ARG_LIMIT, 64);
        try (Cursor cursor = context.getContentResolver().query(collection(), new String[] {MediaStore.Video.Media._ID}, args, null)) {
            if (cursor == null) throw new IOException("Cannot inspect abandoned video rows");
            int count = 0;
            while (cursor.moveToNext() && count++ < 64)
                context.getContentResolver().delete(Uri.withAppendedPath(collection(), Long.toString(cursor.getLong(0))), null, null);
        }
    }
    private void validateFile() throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(output.getFileDescriptor());
            int video = -1, audioTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); ++i) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)) {
                    if (format.getInteger(MediaFormat.KEY_WIDTH) != 1920 || format.getInteger(MediaFormat.KEY_HEIGHT) != 1080)
                        throw new IOException("MP4 is not 1920x1080");
                    video = i;
                } else if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)) audioTrack = i;
            }
            if (video < 0 || audioTrack < 0 || !audioVerified) throw new IOException("MP4 lacks verified video/MIC audio tracks");
            extractor.selectTrack(audioTrack);
            if (extractor.getSampleTime() < 0) throw new IOException("MP4 AAC track is empty");
            extractor.unselectTrack(audioTrack);
            extractor.selectTrack(video);
            long samples = 0, first = Long.MAX_VALUE, last = -1;
            // Full encoded sample timestamps, not the requested frame-rate tag (which can lie).
            do {
                long at = extractor.getSampleTime();
                if (at < 0) break;
                first = Math.min(first, at); last = Math.max(last, at); ++samples;
            } while (extractor.advance());
            if (!VideoRecordingPolicy.measured60(samples, first, last))
                throw new IOException("Encoded MP4 timestamps are not 60fps");
        } finally { extractor.release(); }
    }
    private void finish(String reason, boolean discard) {
        if (finished) return;
        diagnostics.record(CLEANUP_START);
        finished = true;
        cancelled.set(true);
        if (worker != null) worker.removeCallbacks(health); // Never drop a queued onOpened/onClosed callback.
        boolean saved = false;
        String result = reason;
        try {
            if (orientation != null) safely(orientation::disable);
            if (audioMonitor) safely(() -> audio.unregisterAudioRecordingCallback(audioCallback));
            if (session != null) {
                safely(() -> session.stopRepeating());
                safely(() -> session.abortCaptures());
                safely(session::close);
            }
            if (camera != null) safely(camera::close);
            boolean stopped = false;
            if (recorder != null && nativeStarted) {
                try {
                    diagnostics.record(RECORDER_STOP);
                    recorder.stop(); stopped = true;
                    diagnostics.record(RECORDER_STOPPED);
                }
                catch (RuntimeException e) { diagnostics.record(RECORDER_STOP_FAILED, e); result += "; MP4 stop failed: " + e; }
            }
            if (recorder != null) safely(recorder::release);
            if (encoder != null) safely(encoder::release);
            if (stopped && !discard) {
                try {
                    output.getFileDescriptor().sync();
                    diagnostics.record(FILE_VALIDATE);
                    validateFile();
                    diagnostics.record(FILE_VALIDATED);
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    diagnostics.record(PUBLISH);
                    if (context.getContentResolver().update(pending, values, null, null) != 1)
                        throw new IOException("MediaStore publish failed");
                    saved = true;
                    diagnostics.record(PUBLISHED);
                } catch (Exception e) { diagnostics.record(OUTPUT_REJECTED, e); result += "; rejected output: " + e; }
            }
        } finally {
            if (output != null) safely(output::close);
            if (!saved && pending != null) {
                try { context.getContentResolver().delete(pending, null, null); }
                catch (RuntimeException e) { diagnostics.record(CLEANUP_FAILED, e); result += "; pending cleanup failed: " + e; Log.e(TAG, result, e); }
            }
            // onClosed owns the gate when a camera open was issued. Keep it locked if the
            // vendor never completes close; never overlap a stale native operation with a new one.
            gate.cleanedUp();
            Log.i(TAG, "Video finished saved=" + saved + " " + result);
            diagnostics.record(saved ? FINISHED_SAVED : FINISHED_UNSAVED);
            listener.finished(this, saved, result);
            thread.quitSafely();
        }
    }
    private interface Cleanup { void run() throws Exception; }
    private void safely(Cleanup action) {
        try { action.run(); }
        catch (Exception e) { diagnostics.record(CLEANUP_FAILED, e); Log.w(TAG, "Video cleanup operation failed", e); }
    }
}
