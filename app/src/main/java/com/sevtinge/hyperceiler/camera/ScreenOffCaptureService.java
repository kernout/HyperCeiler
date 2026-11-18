/*
 * This file is part of HyperCeiler.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sevtinge.hyperceiler.camera;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/** Explicit, non-exported system-server admission; durable FIFO; never starts an Activity. */
public final class ScreenOffCaptureService extends Service {
    public static final String ACTION = "com.sevtinge.hyperceiler.action.SCREEN_OFF_CAPTURE";
    public static final String EXTRA_REQUEST_ID = "hyperceiler.capture_request_id";
    public static final String EXTRA_REQUESTED_AT = "hyperceiler.capture_requested_at";
    public static final String EXTRA_SCREEN_OFF = "hyperceiler.capture_screen_off";
    private static final String PRIVACY_CHECKED = "hyperceiler.sensor_privacy_checked";
    private static final String PRIVACY_CHECKED_AT = "hyperceiler.sensor_privacy_checked_at";
    private static final long PRIVACY_CHECK_MAX_AGE_MS = 2_500;
    static final String TAG = "HCScreenOffCamera";
    private static final String CHANNEL = "screen_off_capture_v1";
    private static final int NOTIFICATION_ID = 0x484343;
    private static final int FAILURE_ID = 0x484344;
    private static final long MAX_DURATION_MS = 15_000;
    private static final String USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private final Handler main = new Handler(Looper.getMainLooper());
    private CaptureRequestQueue queue;
    private CaptureWorker capture;
    CaptureWorker.Factory workerFactory = ScreenOffCapture::new;
    private CaptureRequestQueue.Request active;
    private PowerManager.WakeLock wakeLock;
    private int latestStartId;
    private boolean foreground;
    private boolean destroyed;
    private boolean receiverRegistered;
    private boolean halted;
    private void refreshForeground() {
        if (!foreground || queue == null) return;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID,
                    notification("Saving queued photos (" + queue.size() + " pending)", true));
        } catch (RuntimeException e) { Log.w(TAG, "Queue notification update failed", e); }
    }
    private final Runnable next = this::drain;
    private final Runnable timeout = () -> {
        if (capture == null) return;
        capture.cancel("Per-request 15s deadline exceeded");
        releaseWakeLock();
        main.postDelayed(this.cleanupTimeout, 3_000);
    };
    private final Runnable cleanupTimeout = () -> {
        if (capture == null) return;
        // Do not open another camera while a vendor close/open callback is stuck.
        halted = true;
        reportFailure("Camera cleanup did not finish; queue paused, durable requests retained");
        stopIdle();
    };
    private final BroadcastReceiver userReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            halted = true;
            try { if (queue != null) queue.failPending("Request user left foreground/shutdown; JPEG retained"); }
            catch (IOException e) { reportFailure("Cannot record user-switch cancellation: " + e); }
            if (capture != null) { capture.cancel("User switch/shutdown"); main.postDelayed(cleanupTimeout, 3_000); }
            else stopIdle();
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        try { queue = new CaptureRequestQueue(new File(getFilesDir(), "screen-off-capture-queue")); }
        catch (IOException e) { halted = true; reportFailure("Capture queue unavailable: " + e); }
        IntentFilter filter = new IntentFilter(USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_BACKGROUND);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(userReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        try { enterForeground(); }
        catch (RuntimeException e) {
            reportFailure("Camera foreground service unavailable; durable queue retained: " + e);
            if (capture != null) capture.cancel("Foreground service unavailable");
            halted = true; stopIdle(); return START_NOT_STICKY;
        }
        if (queue == null || halted) { stopIdle(); return START_NOT_STICKY; }
        if (intent != null && ACTION.equals(intent.getAction())) {
            try {
                String id = intent.getStringExtra(EXTRA_REQUEST_ID);
                long now = SystemClock.elapsedRealtime();
                boolean valid = CaptureRequestQueue.validId(id)
                        && isFreshRequest(intent.getBooleanExtra(EXTRA_SCREEN_OFF, false),
                        intent.getLongExtra(EXTRA_REQUESTED_AT, -1), now)
                        && isFreshPrivacyCheck(intent.getBooleanExtra(PRIVACY_CHECKED, false),
                        intent.getLongExtra(PRIVACY_CHECKED_AT, -1), now);
                // A redelivered ID is already durable; freshness is only for NEW admission.
                if (!valid) {
                    if (!queue.contains(id)) reportFailure("Rejected malformed/stale capture request id=" + id);
                } else if (!userAndPermissionAllowed()) {
                    reportFailure("Rejected capture request: camera permission/user unavailable id=" + id);
                } else {
                    CaptureRequestQueue.Admission admission = queue.accept(id,
                            intent.getLongExtra(EXTRA_REQUESTED_AT, -1), System.currentTimeMillis());
                    Log.i(TAG, "Capture admission=" + admission + " id=" + id + " pending=" + queue.size());
                    if (admission == CaptureRequestQueue.Admission.FULL)
                        reportFailure("Capture queue/receipt storage full; request rejected id=" + id);
                }
            } catch (IOException | RuntimeException e) { reportFailure("Capture admission failed: " + e); }
        } else if (intent != null) reportFailure("Rejected unknown capture action");
        refreshForeground();
        drain();
        // System redelivery is best effort. Next valid request also recovers persisted work.
        return queue.size() > 0 ? START_REDELIVER_INTENT : START_NOT_STICKY;
    }
    static boolean isFreshPrivacyCheck(boolean checked, long checkedAt, long now) {
        return checked && checkedAt >= 0 && now >= checkedAt && now - checkedAt <= PRIVACY_CHECK_MAX_AGE_MS;
    }
    static boolean isFreshRequest(boolean screenOffAtGesture, long at, long now) {
        return isFreshPrivacyCheck(screenOffAtGesture, at, now);
    }
    private boolean userAndPermissionAllowed() {
        UserManager user = getSystemService(UserManager.class);
        return user != null && user.isUserForeground() && user.isUserUnlocked()
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
    private void drain() {
        if (destroyed || halted || capture != null) return;
        active = queue.head();
        if (active == null) { stopIdle(); return; }
        if (!userAndPermissionAllowed()) {
            try { queue.failPending("Camera permission revoked or user not foreground/unlocked; JPEG retained"); }
            catch (IOException e) { reportFailure("Cannot record permission/user failure: " + e); }
            reportFailure("Queued capture cancelled: camera permission/user unavailable");
            stopIdle(); return;
        }
        try {
            queue.begin(active);
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null) throw new IllegalStateException("Power service unavailable");
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperCeiler:ScreenOffCapture");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(MAX_DURATION_MS);
            main.postDelayed(timeout, MAX_DURATION_MS);
            final CaptureRequestQueue.Request request = active;
            capture = workerFactory.create(getApplicationContext(), SystemClock.elapsedRealtime() + MAX_DURATION_MS,
                    queue, request, (controller, success, reason, retryable) -> main.post(() ->
                    onCaptureFinished(controller, request, success, reason, retryable)));
            Log.i(TAG, "Starting capture id=" + request.id + " attempt=" + request.attempts
                    + " queueAgeMs=" + (System.currentTimeMillis() - request.acceptedWall));
            capture.start();
        } catch (IOException | RuntimeException e) {
            capture = null;
            completeAttempt(active, false, "Worker startup failed: " + e, true);
        }
    }
    private void onCaptureFinished(CaptureWorker controller, CaptureRequestQueue.Request request,
            boolean success, String reason, boolean retryable) {
        if (capture != controller) return;
        capture = null;
        if (destroyed) return; // Persisted in-flight request is recovered by the next service.
        completeAttempt(request, success, reason, retryable);
    }
    private void completeAttempt(CaptureRequestQueue.Request request, boolean success, String reason, boolean retryable) {
        main.removeCallbacks(timeout); main.removeCallbacks(cleanupTimeout); releaseWakeLock();
        boolean retry = false;
        try {
            if (!success && !request.terminal()) retry = queue.failedAttempt(request, reason, retryable);
            if (!success) reportFailure("Capture id=" + request.id + " attempt=" + request.attempts
                    + (retry ? " retry scheduled: " : " failed: ") + reason);
            else Log.i(TAG, "Capture saved id=" + request.id + " " + reason);
        } catch (IOException e) {
            halted = true; reportFailure("Queue result persistence failed; queue paused: " + e);
        }
        active = null;
        refreshForeground();
        if (halted) stopIdle();
        else main.postDelayed(next, retry ? 1_000 : 0);
    }
    private Notification notification(String text, boolean ongoing) {
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Screen-off camera").setContentText(text)
                .setCategory(ongoing ? Notification.CATEGORY_SERVICE : Notification.CATEGORY_ERROR)
                .setVisibility(Notification.VISIBILITY_SECRET).setOngoing(ongoing).setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE).build();
    }
    private void ensureChannel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Screen-off camera", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Queued photos and capture failures"); channel.setSound(null, null);
        channel.enableVibration(false); channel.enableLights(false); channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET); manager.createNotificationChannel(channel);
    }
    private void enterForeground() {
        if (foreground) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException("Notification service unavailable");
        ensureChannel(manager);
        startForeground(NOTIFICATION_ID, notification("Saving queued photos", true), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        foreground = true;
    }
    private void reportFailure(String reason) {
        Log.e(TAG, reason);
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) { ensureChannel(manager); manager.notify(FAILURE_ID, notification(reason, false)); }
        } catch (RuntimeException e) { Log.e(TAG, "Failure notification unavailable", e); }
    }
    private void stopIdle() {
        main.removeCallbacks(timeout); main.removeCallbacks(next); releaseWakeLock();
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
        if (!destroyed) stopSelfResult(latestStartId);
    }
    private void releaseWakeLock() {
        if (wakeLock == null) return;
        try { if (wakeLock.isHeld()) wakeLock.release(); }
        catch (RuntimeException e) { Log.w(TAG, "Wake lock release failed", e); }
        wakeLock = null;
    }
    @Override public void onDestroy() {
        destroyed = true; main.removeCallbacks(cleanupTimeout);
        if (receiverRegistered) { unregisterReceiver(userReceiver); receiverRegistered = false; }
        if (capture != null) capture.cancel("Service destroyed; request remains durable");
        stopIdle(); super.onDestroy();
    }
}
