/*
 * This file is part of HyperCeiler.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 HyperCeiler Contributions
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
import android.util.Log;

/** One explicit system-server start, at most one photo, and never an Activity. */
public final class ScreenOffCaptureService extends Service {
    public static final String ACTION = "com.sevtinge.hyperceiler.action.SCREEN_OFF_CAPTURE";
    private static final String PRIVACY_CHECKED = "hyperceiler.sensor_privacy_checked";
    private static final String PRIVACY_CHECKED_AT = "hyperceiler.sensor_privacy_checked_at";
    private static final long PRIVACY_CHECK_MAX_AGE_MS = 2_500;
    static final String TAG = "HCScreenOffCamera";
    private static final String CHANNEL = "screen_off_capture_v1";
    private static final int NOTIFICATION_ID = 0x484343;
    private static final long MAX_DURATION_MS = 15_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    // These fields are main-thread confined. The capture controller owns all Camera2 objects.
    private PowerManager power;
    private PowerManager.WakeLock wakeLock;
    private ScreenOffCapture capture;
    private int latestStartId;
    private boolean foreground;
    private boolean destroyed;
    private boolean receiverRegistered;
    private boolean stopping;

    private final Runnable timeout = () -> cancel("Total capture deadline exceeded");
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) cancel("Screen became interactive");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        power = getSystemService(PowerManager.class);
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON),
                Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        // Even rejected startForegroundService requests must enter foreground promptly.
        try {
            enterForeground();
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot enter camera foreground service (permissions/background policy)", e);
            if (capture != null) cancel("Foreground service unavailable");
            else stopIdle();
            return START_NOT_STICKY;
        }
        if (capture != null) {
            Log.w(TAG, "Dropping duplicate start while capture/cleanup is busy");
            // A new startForegroundService can arrive after cancellation removed foreground.
            // Satisfy that start above, then remove it again instead of leaving a stuck FGS.
            if (stopping) cancel("Duplicate start while stopping");
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION.equals(intent.getAction())) {
            Log.w(TAG, "Ignoring missing or unrecognized capture action");
            stopIdle();
            return START_NOT_STICKY;
        }
        // This non-exported service accepts the system_server preflight only at admission.
        // Extras are an integration contract, not an authentication/permission bypass.
        // Never apply this 2.5s freshness window to the subsequent 15s capture lifetime.
        try {
            if (!isFreshPrivacyCheck(intent.getBooleanExtra(PRIVACY_CHECKED, false),
                    intent.getLongExtra(PRIVACY_CHECKED_AT, -1), SystemClock.elapsedRealtime())) {
                Log.w(TAG, "Rejecting capture: missing/stale system sensor-privacy preflight");
                stopIdle();
                return START_NOT_STICKY;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Rejecting malformed sensor-privacy preflight", e);
            stopIdle();
            return START_NOT_STICKY;
        }
        if (power == null || power.isInteractive()) {
            Log.w(TAG, "Rejecting capture: display is interactive or power service unavailable");
            stopIdle();
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Rejecting capture: CAMERA permission not granted");
            stopIdle();
            return START_NOT_STICKY;
        }
        try {
            stopping = false;
            long deadline = SystemClock.elapsedRealtime() + MAX_DURATION_MS;
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "HyperCeiler:ScreenOffCapture");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(MAX_DURATION_MS);
            main.postDelayed(timeout, MAX_DURATION_MS);
            capture = new ScreenOffCapture(getApplicationContext(), deadline, controller ->
                    main.post(() -> onCaptureFinished(controller)));
            capture.start();
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot start screen-off capture worker", e);
            if (capture != null) capture.cancel("Worker startup failed");
            capture = null;
            stopIdle();
        }
        return START_NOT_STICKY;
    }

    static boolean isFreshPrivacyCheck(boolean checked, long checkedAt, long now) {
        return checked && checkedAt >= 0 && now >= checkedAt
                && now - checkedAt <= PRIVACY_CHECK_MAX_AGE_MS;
    }

    private void enterForeground() {
        if (foreground) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException("Notification service unavailable");
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Screen-off camera",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Brief notification while saving a screen-off photo");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        manager.createNotificationChannel(channel);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Screen-off camera")
                .setContentText("Saving one photo")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
        // The explicit caller must be system_server (with Binder identity cleared): that
        // caller supplies both FGS background-start and while-in-use CAMERA exemptions.
        // This does not grant the app CAMERA permission or bypass camera AppOps/privacy.
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        foreground = true;
    }

    private void cancel(String reason) {
        if (capture == null) return;
        stopping = true;
        main.removeCallbacks(timeout);
        capture.cancel(reason);
        // The main-thread watchdog can release this even if a vendor/provider blocks the worker.
        releaseWakeLock();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        // Keep the busy controller until cleanup finishes: never overlap an old camera with a
        // new start. onCaptureFinished uses the newest startId, including dropped duplicates.
        stopSelfResult(latestStartId);
    }

    private void onCaptureFinished(ScreenOffCapture controller) {
        if (capture != controller) return;
        capture = null;
        stopIdle();
    }

    private void stopIdle() {
        main.removeCallbacks(timeout);
        releaseWakeLock();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        if (!destroyed) stopSelfResult(latestStartId);
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "Wake lock release failed", e);
            }
            wakeLock = null;
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver);
            receiverRegistered = false;
        }
        if (capture != null) capture.cancel("Service destroyed");
        stopIdle();
        super.onDestroy();
    }
}
