/*
 * This file is part of HyperCeiler.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.OrientationEventListener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** All camera, EGL, role lookup and file operations run on this controller's HandlerThread. */
final class ScreenOffCapture implements CaptureWorker {
    private static final String TAG = ScreenOffCaptureService.TAG;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final long AVAILABILITY_WAIT_MS = 1_000;
    private static final long THREE_A_WAIT_MS = 1_800;
    private static final long THREE_A_MIN_MS = 350;
    private final Context context;
    private final long deadline;
    private final Completion completion;
    private final CaptureRequestQueue queue;
    private final CaptureRequestQueue.Request request;
    private final AtomicBoolean completionDelivered = new AtomicBoolean();
    private boolean outcomeSuccess;
    private String outcomeReason;
    private boolean retryable = true;
    private final HandlerThread thread = new HandlerThread("HCScreenOffCamera");
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Handler handler;
    private CameraManager manager;
    private ScreenOffCameraSelector.Selection selection;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private ScreenOffPreview preview;
    private OrientationEventListener orientationListener;
    private volatile int deviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    private boolean ownsGate;
    private volatile boolean finished;
    private boolean availabilityRegistered;
    private boolean openIssued;
    private boolean submitted;
    private boolean captureCompleted;
    private boolean imageReceived;
    private boolean afSupported;
    private boolean awbSupported;
    private long previewStarted;
    private final Runnable availabilityTimeout = () -> finish(false, "Camera availability not confirmed", null);
    private final Runnable threeATimeout = () -> takePhoto("3A bounded wait expired");
    private final Runnable openTimeout = () -> finish(false, "Camera open timeout", null);
    private final Runnable sessionTimeout = () -> finish(false, "Camera session configuration timeout", null);
    private final Runnable imageTimeout = () -> finish(false, "JPEG/capture result timeout", null);

    ScreenOffCapture(Context context, long deadline, CaptureRequestQueue queue,
            CaptureRequestQueue.Request request, Completion completion) {
        this.context = context;
        this.queue = queue;
        this.request = request;
        this.deadline = deadline;
        this.completion = completion;
    }

    @Override public void start() {
        thread.start();
        try {
            handler = new Handler(thread.getLooper());
        } catch (RuntimeException e) {
            thread.quitSafely();
            throw e;
        }
        if (!handler.post(() -> {
            try {
                ensureAllowed();
                if (queue.jpeg(request).isFile()) {
                    CapturePublisher.publish(context, queue, request);
                    finish(true, "Durable JPEG publication recovered", null);
                    return;
                }
                ownsGate = BUSY.compareAndSet(false, true);
                if (!ownsGate) throw new IllegalStateException("Another capture is still busy");
                ensureAllowed();
                manager = context.getSystemService(CameraManager.class);
                if (manager == null) throw new IllegalStateException("Camera service unavailable");
                orientationListener = new OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
                    @Override
                    public void onOrientationChanged(int orientation) {
                        deviceOrientation = orientation;
                    }
                };
                if (orientationListener.canDetectOrientation()) orientationListener.enable();
                selection = ScreenOffCameraSelector.select(manager);
                ensureAllowed();
                availabilityRegistered = true;
                manager.registerAvailabilityCallback(availabilityCallback, handler);
                // Only the selected public ID gates opening. Auxiliary cameras may be
                // permanently unavailable even when the main camera is free.
                handler.postDelayed(availabilityTimeout, AVAILABILITY_WAIT_MS);
            } catch (Exception e) {
                finish(false, "Capture preflight failed", e);
            }
        })) {
            thread.quitSafely();
            throw new IllegalStateException("Capture worker rejected startup");
        }
    }

    @Override public void cancel(String reason) {
        cancelled.set(true);
        Handler worker = handler;
        if (worker != null) worker.post(() -> finish(false, reason, null));
    }

    private void ensureAllowed() throws IOException {
        if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline)
            throw new IOException("Capture cancelled or deadline exceeded");
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || !users.isUserForeground() || !users.isUserUnlocked())
            throw new SecurityException("Request user no longer foreground/unlocked");
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("CAMERA permission missing/revoked");
        // system_server checked sensor privacy before this capture was admitted. During
        // capture, Camera2 enforces sensor privacy, CAMERA permission and AppOps itself;
        // any device/session/capture error follows the normal failure/cleanup path.
    }

    private final CameraManager.AvailabilityCallback availabilityCallback =
            new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraAvailable(String id) {
            if (finished || openIssued || !selection.id.equals(id)) return;
            maybeOpen();
        }

        @Override
        public void onCameraUnavailable(String id) {
            if (finished || !selection.id.equals(id)) return;
            // Wait briefly for this camera to become available; unrelated IDs are irrelevant.
            // Availability is advisory: a new client can still win the subsequent open race.
            // Our open can make both the selected camera AND related logical/physical IDs
            // unavailable. After open is issued, rely on onDisconnected/onError for resource
            // conflicts; treating those related notifications as another client is incorrect.
        }
    };

    private void maybeOpen() {
        if (finished || openIssued) return;
        try {
            ensureAllowed();
            if (SystemClock.elapsedRealtime() + 2_000 >= deadline)
                throw new IOException("Insufficient time remaining to open camera");
            handler.removeCallbacks(availabilityTimeout);
            openIssued = true;
            handler.postDelayed(openTimeout, 3_000);
            // Framework delivery is deliberately independent of the worker looper: if a
            // timed-out open completes late, its device is still closed immediately.
            manager.openCamera(selection.id, this::executeCallback, deviceCallback);
        } catch (Exception e) {
            // If openCamera threw synchronously, no device will be delivered to this caller.
            openIssued = false;
            finish(false, "Cannot open verified main camera", e);
        }
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice device) {
            handler.removeCallbacks(openTimeout);
            if (finished) {
                device.close();
                return;
            }
            camera = device;
            try {
                ensureAllowed();
                preview = new ScreenOffPreview(selection.preview, handler,
                        error -> finish(false, "Offscreen preview failed", error));
                reader = ImageReader.newInstance(selection.jpeg.getWidth(), selection.jpeg.getHeight(),
                        ImageFormat.JPEG, 2);
                reader.setOnImageAvailableListener(source -> readJpeg(source), handler);
                handler.postDelayed(sessionTimeout, 3_000);
                camera.createCaptureSession(new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        Arrays.asList(new OutputConfiguration(preview.surface()),
                                new OutputConfiguration(reader.getSurface())),
                        ScreenOffCapture.this::executeCallback, sessionCallback));
            } catch (Exception e) {
                finish(false, "Cannot configure offscreen capture", e);
            }
        }

        @Override
        public void onDisconnected(CameraDevice device) {
            if (camera == null) camera = device;
            if (finished) {
                device.close();
                return;
            }
            finish(false, "Camera disconnected (possibly another camera client)", null);
        }

        @Override
        public void onClosed(CameraDevice device) {
            releaseGate();
        }

        @Override
        public void onError(CameraDevice device, int error) {
            if (camera == null) camera = device;
            if (finished) {
                device.close();
                return;
            }
            if (error == ERROR_CAMERA_DISABLED) retryable = false;
            finish(false, "Camera error=" + error, null);
        }
    };

    private final CameraCaptureSession.StateCallback sessionCallback =
            new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession configured) {
            handler.removeCallbacks(sessionTimeout);
            if (finished) {
                configured.close();
                return;
            }
            session = configured;
            try {
                ensureAllowed();
                CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                request.addTarget(preview.surface());
                applyControls(request);
                previewStarted = SystemClock.elapsedRealtime();
                session.setRepeatingRequest(request.build(), previewCallback, handler);
                handler.postDelayed(threeATimeout, THREE_A_WAIT_MS);
            } catch (Exception e) {
                finish(false, "Preview repeating request failed", e);
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession failed) {
            failed.close();
            finish(false, "Offscreen Camera2 session configuration failed", null);
        }
    };

    private void applyControls(CaptureRequest.Builder request) throws IOException {
        CameraCharacteristics c = selection.characteristics;
        if (!ScreenOffCameraSelector.contains(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AE_MODE_ON)) throw new IOException("AE_ON unsupported");
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        int[] modes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        // Continuous-picture AF avoids the single-trigger lock/precapture state machine.
        afSupported = ScreenOffCameraSelector.contains(modes,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        request.set(CaptureRequest.CONTROL_AF_MODE, afSupported
                ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE : CaptureRequest.CONTROL_AF_MODE_OFF);
        request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        request.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        awbSupported = ScreenOffCameraSelector.contains(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AWB_MODE_AUTO);
        if (awbSupported) request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
    }

    private final CameraCaptureSession.CaptureCallback previewCallback =
            new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            if (finished || submitted) return;
            try {
                ensureAllowed();
                Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
                Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                Integer awb = result.get(CaptureResult.CONTROL_AWB_STATE);
                boolean aeReady = Integer.valueOf(CaptureResult.CONTROL_AE_STATE_CONVERGED).equals(ae)
                        || Integer.valueOf(CaptureResult.CONTROL_AE_STATE_LOCKED).equals(ae)
                        || Integer.valueOf(CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED).equals(ae);
                boolean afReady = !afSupported
                        || Integer.valueOf(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED).equals(af)
                        || Integer.valueOf(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED).equals(af);
                boolean awbReady = !awbSupported
                        || Integer.valueOf(CaptureResult.CONTROL_AWB_STATE_CONVERGED).equals(awb)
                        || Integer.valueOf(CaptureResult.CONTROL_AWB_STATE_LOCKED).equals(awb);
                if (aeReady && afReady && awbReady
                        && SystemClock.elapsedRealtime() - previewStarted >= THREE_A_MIN_MS)
                    takePhoto("3A converged");
            } catch (Exception e) {
                finish(false, "Preview no longer permitted", e);
            }
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
            if (submitted) return; // A stale preview failure must not discard a completed still.
            finish(false, "Preview request failed: reason=" + failure.getReason(), null);
        }
    };

    private void takePhoto(String reason) {
        if (finished || submitted) return;
        try {
            ensureAllowed();
            if (SystemClock.elapsedRealtime() + 1_500 >= deadline)
                throw new IOException("Insufficient time remaining for JPEG");
            submitted = true;
            handler.removeCallbacks(threeATimeout);
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(reader.getSurface());
            applyControls(request);
            Integer orientation = selection.characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orientation != null) request.set(CaptureRequest.JPEG_ORIENTATION,
                    ScreenOffCameraSelector.jpegOrientation(orientation, deviceOrientation));
            request.set(CaptureRequest.JPEG_QUALITY, (byte) 92);
            Log.i(TAG, "Submitting one flash-off JPEG: " + reason);
            session.capture(request.build(), stillCallback, handler);
            handler.postDelayed(imageTimeout, 5_000);
        } catch (Exception e) {
            finish(false, "Still capture submission failed", e);
        }
    }

    private final CameraCaptureSession.CaptureCallback stillCallback =
            new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            if (finished) return;
            captureCompleted = true;
            commitIfReady();
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure failure) {
            finish(false, "Still capture failed: reason=" + failure.getReason(), null);
        }

        @Override
        public void onCaptureSequenceAborted(CameraCaptureSession s, int sequenceId) {
            finish(false, "Still capture sequence aborted", null);
        }

        @Override
        public void onCaptureBufferLost(CameraCaptureSession s, CaptureRequest r,
                android.view.Surface target, long frameNumber) {
            finish(false, "Still JPEG output buffer lost", null);
        }
    };

    private void readJpeg(ImageReader source) {
        if (finished) return;
        try (Image image = source.acquireNextImage()) {
            if (image == null) return;
            if (!submitted || imageReceived) throw new IOException("Unexpected/duplicate JPEG output");
            ensureAllowed();
            if (image.getFormat() != ImageFormat.JPEG || image.getPlanes().length != 1)
                throw new IOException("Unexpected JPEG image format");
            ByteBuffer bytes = image.getPlanes()[0].getBuffer();
            if (bytes.remaining() < 4 || (bytes.get(bytes.position()) & 0xff) != 0xff
                    || (bytes.get(bytes.position() + 1) & 0xff) != 0xd8)
                throw new IOException("Empty/invalid JPEG payload");
            queue.storeJpeg(request, bytes);
            imageReceived = true;
        } catch (Exception e) {
            finish(false, "Durable JPEG write failed; request retained for bounded retry", e);
            return;
        }
        commitIfReady();
    }

    private void commitIfReady() {
        if (finished || !imageReceived || !captureCompleted) return;
        try {
            ensureAllowed();
            // Release scarce camera hardware BEFORE potentially slow provider I/O.
            // FIFO completion still waits for onClosed; bytes are already durable.
            if (session != null) closeSafely("session before publication", session::close);
            if (camera != null) closeSafely("camera before publication", camera::close);
            CapturePublisher.publish(context, queue, request);
            finish(true, "One JPEG published to DCIM/Camera request=" + request.id, null);
        } catch (Exception e) {
            finish(false, "MediaStore publication failed; durable JPEG retained", e);
        }
    }

    private void finish(boolean success, String reason, Throwable error) {
        if (Looper.myLooper() != thread.getLooper()) {
            handler.post(() -> finish(success, reason, error));
            return;
        }
        if (finished) return;
        finished = true;
        outcomeSuccess = success;
        outcomeReason = reason + (error == null ? "" : ": " + error);
        if (error instanceof SecurityException) retryable = false;
        if (success) Log.i(TAG, reason);
        else Log.e(TAG, reason, error);
        // Do not drop queued framework device/session callbacks: onOpened/onConfigured
        // must still run their finished guards and close the newly delivered resources.
        handler.removeCallbacks(availabilityTimeout);
        handler.removeCallbacks(threeATimeout);
        handler.removeCallbacks(openTimeout);
        handler.removeCallbacks(imageTimeout);
        handler.removeCallbacks(sessionTimeout);
        if (orientationListener != null) {
            closeSafely("orientation listener", orientationListener::disable);
            orientationListener = null;
        }
        if (availabilityRegistered && manager != null) {
            closeSafely("availability callback", () -> manager.unregisterAvailabilityCallback(availabilityCallback));
            availabilityRegistered = false;
        }
        if (session != null) closeSafely("session", session::close);
        if (camera != null) closeSafely("camera", camera::close);
        if (reader != null) closeSafely("JPEG reader", reader::close);
        if (preview != null) closeSafely("preview", preview::close);
        session = null;
        camera = null;
        reader = null;
        preview = null;
        // Camera close is asynchronous. Hold the process-wide gate until onClosed, even
        // after this service has stopped; a late open still gets closed by the executor.
        if (!openIssued) releaseGate();
        thread.quitSafely();
        // Completion waits for onClosed, keeping FIFO workers from racing a closing camera.
        if (!ownsGate) deliverCompletion();
    }

    private void executeCallback(Runnable command) {
        if (!handler.post(command)) command.run();
    }

    private synchronized void releaseGate() {
        if (ownsGate) {
            ownsGate = false;
            BUSY.set(false);
        }
        if (finished) deliverCompletion();
    }

    private void deliverCompletion() {
        if (completionDelivered.compareAndSet(false, true))
            completion.finished(this, outcomeSuccess, outcomeReason, retryable);
    }

    private static void closeSafely(String name, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed closing " + name, e);
        }
    }
}
