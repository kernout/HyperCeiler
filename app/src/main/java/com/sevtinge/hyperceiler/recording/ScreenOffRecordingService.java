/*
 * This file is part of HyperCeiler.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.recording;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

import java.util.List;

/**
 * No Activity, display flag, audio focus request, routing change or hidden privacy API.
 * Integration MUST declare this service non-exported, with microphone FGS type/permissions.
 * system_server supplies an explicit startForegroundServiceAsUser with cleared Binder identity;
 * the fresh preflight is a caller contract, not authentication or a runtime permission bypass.
 */
public final class ScreenOffRecordingService extends Service {
    public static final String ACTION_TOGGLE =
            "com.sevtinge.hyperceiler.action.SCREEN_OFF_RECORDING_TOGGLE";
    public static final String ACTION_STOP =
            "com.sevtinge.hyperceiler.action.SCREEN_OFF_RECORDING_STOP";
    public static final String ACTION_STOP_IF_RUNNING =
            "com.sevtinge.hyperceiler.action.SCREEN_OFF_RECORDING_STOP_IF_RUNNING";
    public static final String EXTRA_PRIVACY_CHECKED = "hyperceiler.microphone_privacy_checked";
    public static final String EXTRA_PRIVACY_CHECKED_AT = "hyperceiler.microphone_privacy_checked_at";
    /** Optional String or Long, unique for each completed double-press; reuse only for retransmission. */
    public static final String EXTRA_REQUEST_ID = "hyperceiler.recording_request_id";
    static final String TAG = "HCVolumeUpRecorder";
    static final long MAX_DURATION_MS = 2 * 60 * 60 * 1_000L;
    private static final long STARTUP_TIMEOUT_MS = 15_000;
    private static final long HEALTH_POLL_MS = 5_000;
    private static final long CLEANUP_GRACE_MS = 10_000;
    private static final String CHANNEL = "screen_off_recording_v1";
    private static final int NOTIFICATION_ID = 0x484352;
    // ACTION_USER_SWITCHED is a system broadcast but not a public SDK constant.
    private static final String ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RecordingPolicy policy = new RecordingPolicy();
    private PowerManager power;
    private AudioManager audio;
    private AppOpsManager appOps;
    private PowerManager.WakeLock wakeLock;
    private ScreenOffRecording recording;
    private int latestStartId;
    private boolean foreground;
    private boolean destroyed;
    private boolean receiverRegistered;
    private boolean modeRegistered;
    private boolean appOpsRegistered;
    private boolean monitorsReady;
    private final Runnable startupTimeout = () -> stopRecording(false, "15s startup watchdog");
    private final Runnable workerWatchdog = new Runnable() {
        @Override public void run() {
            if (recording == null || destroyed
                    || policy.state() != RecordingPolicy.State.RECORDING) return;
            if (RecordingPolicy.healthCheckExpired(recording.lastHealthCheck(),
                    SystemClock.elapsedRealtime())) {
                stopRecording(true, "Worker health check stalled");
            } else main.postDelayed(this, HEALTH_POLL_MS);
        }
    };
    private final Runnable maximumDuration = () -> stopRecording(true, "Two-hour recording limit");
    private final Runnable cleanupTimeout = () -> {
        Log.e(TAG, "Native/provider cleanup exceeded grace; releasing service and bounded wake lock");
        // The controller still owns its process-wide gate until native release completes.
        // Do not close/reuse its FD from another thread while stop/prepare might be writing.
        finishService();
    };
    private final AudioManager.OnModeChangedListener modeListener = mode -> {
        if (mode != AudioManager.MODE_NORMAL) stopRecording(true, "Audio mode changed");
    };
    private final AppOpsManager.OnOpChangedListener opListener = (op, pkg) -> main.post(() -> {
        if (recording == null || !getPackageName().equals(pkg)) return;
        try {
            int mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO,
                    Process.myUid(), getPackageName());
            if (!hasAudioPermission() || (mode != AppOpsManager.MODE_ALLOWED
                    && mode != AppOpsManager.MODE_FOREGROUND))
                stopRecording(true, "Microphone permission/AppOps changed");
        } catch (RuntimeException e) {
            stopRecording(true, "Cannot verify microphone AppOps");
        }
    });
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USER_SWITCHED.equals(action) || Intent.ACTION_USER_BACKGROUND.equals(action)
                    || Intent.ACTION_SHUTDOWN.equals(action)) {
                stopRecording(true, "User switched/backgrounded or shutting down");
            } else if (AudioManager.ACTION_MICROPHONE_MUTE_CHANGED.equals(action)
                    && audio != null && audio.isMicrophoneMute()) {
                stopRecording(true, "Microphone muted");
            }
            // Deliberately no SCREEN_ON receiver: the notification remains usable after wake.
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        power = getSystemService(PowerManager.class);
        audio = getSystemService(AudioManager.class);
        appOps = getSystemService(AppOpsManager.class);
        try {
            if (audio == null || appOps == null) throw new IllegalStateException("Audio services unavailable");
            IntentFilter filter = new IntentFilter(ACTION_USER_SWITCHED);
            filter.addAction(Intent.ACTION_USER_BACKGROUND);
            filter.addAction(Intent.ACTION_SHUTDOWN);
            filter.addAction(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED);
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
            audio.addOnModeChangedListener(main::post, modeListener);
            modeRegistered = true;
            appOps.startWatchingMode(AppOpsManager.OPSTR_RECORD_AUDIO, getPackageName(), opListener);
            appOpsRegistered = true;
            monitorsReady = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Recording safety monitors unavailable", e);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        String action = intent == null ? null : intent.getAction();
        // Stop is always routed BEFORE privacy, screen, unlock, permission or conflict gates.
        if (ACTION_STOP.equals(action) || ACTION_STOP_IF_RUNNING.equals(action)) {
            if (policy.stopOnly() == RecordingPolicy.Decision.STOP)
                stopRecording(true, "Explicit stop/stop-if-running");
            else retireIdleStart();
            return START_NOT_STICKY;
        }
        if (!ACTION_TOGGLE.equals(action)) {
            if (recording == null) retireIdleStart();
            return START_NOT_STICKY;
        }
        RecordingPolicy.Decision decision;
        try {
            decision = policy.toggle(requestIdentity(intent), SystemClock.elapsedRealtime());
        } catch (RuntimeException e) {
            Log.w(TAG, "Malformed recording request", e);
            if (recording == null) retireIdleStart();
            return START_NOT_STICKY;
        }
        if (decision == RecordingPolicy.Decision.STOP) {
            stopRecording(true, "Double-press toggle");
            return START_NOT_STICKY;
        }
        if (decision == RecordingPolicy.Decision.IGNORE) {
            if (recording == null) retireIdleStart();
            return START_NOT_STICKY;
        }
        // Permission is checked before attempting a microphone foreground service, even for
        // a rejected first start. A permission-denied start is stopped immediately instead.
        if (!hasAudioPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission not granted");
            finishService();
            return START_NOT_STICKY;
        }
        try {
            enterForeground();
            UserManager users = getSystemService(UserManager.class);
            List<AudioRecordingConfiguration> active = audio == null ? null
                    : audio.getActiveRecordingConfigurations();
            boolean privacy = RecordingPolicy.freshPrivacy(
                    intent.getBooleanExtra(EXTRA_PRIVACY_CHECKED, false),
                    intent.getLongExtra(EXTRA_PRIVACY_CHECKED_AT, -1), SystemClock.elapsedRealtime());
            if (!monitorsReady || !RecordingPolicy.admitted(hasAudioPermission(),
                    power != null && !power.isInteractive(), users != null && users.isUserUnlocked(),
                    users != null && users.isUserForeground(), privacy,
                    audio != null && audio.getMode() == AudioManager.MODE_NORMAL,
                    active != null && active.isEmpty() && !audio.isMicrophoneMute())) {
                Log.w(TAG, "Recording start rejected by admission checks");
                finishService();
                return START_NOT_STICKY;
            }
            policy.starting();
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperCeiler:ScreenOffRecording");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(MAX_DURATION_MS + CLEANUP_GRACE_MS);
            main.postDelayed(startupTimeout, STARTUP_TIMEOUT_MS);
            main.postDelayed(maximumDuration, MAX_DURATION_MS);
            recording = new ScreenOffRecording(getApplicationContext(),
                    SystemClock.elapsedRealtime() + MAX_DURATION_MS, new ScreenOffRecording.Listener() {
                @Override public void started(ScreenOffRecording source) {
                    main.post(() -> {
                        if (recording != source || destroyed) return;
                        main.removeCallbacks(startupTimeout);
                        policy.recording();
                        if (policy.state() == RecordingPolicy.State.RECORDING)
                            main.postDelayed(workerWatchdog, HEALTH_POLL_MS);
                    });
                }
                @Override public void finishing(ScreenOffRecording source) {
                    main.post(() -> {
                        if (recording != source || destroyed) return;
                        main.removeCallbacks(startupTimeout);
                        main.removeCallbacks(maximumDuration);
                        main.removeCallbacks(workerWatchdog);
                        if (policy.stopping()) main.postDelayed(cleanupTimeout, CLEANUP_GRACE_MS);
                    });
                }
                @Override public void finished(ScreenOffRecording source, boolean saved) {
                    main.post(() -> {
                        if (recording != source) return;
                        Log.i(TAG, saved ? "Recording saved" : "Recording discarded or no valid output");
                        recording = null;
                        policy.finished();
                        finishService();
                    });
                }
            });
            recording.start();
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot start microphone foreground recording", e);
            if (recording != null) stopRecording(false, "Startup exception");
            else { policy.finished(); finishService(); }
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation") // Accept the hook's String/Long ID without hidden APIs.
    private static String requestIdentity(Intent intent) {
        Object id = intent.getExtras() == null ? null : intent.getExtras().get(EXTRA_REQUEST_ID);
        if (id instanceof String || id instanceof Long || id instanceof Integer)
            return id.getClass().getSimpleName() + ":" + id;
        if (id != null) throw new IllegalArgumentException("Unsupported recording request ID");
        // No blanket time debounce: distinct completed gestures must still toggle immediately.
        long timestamp = intent.getLongExtra(EXTRA_PRIVACY_CHECKED_AT, -1);
        return timestamp >= 0 ? "preflight:" + timestamp : null;
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void enterForeground() {
        if (foreground) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException("Notification manager unavailable");
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Screen-off recording",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible while the microphone is recording; Stop saves the recording");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
        // Do not silently record when notification permissions/channel settings hide Stop.
        NotificationChannel installed = manager.getNotificationChannel(CHANNEL);
        if (!manager.areNotificationsEnabled() || installed == null
                || installed.getImportance() == NotificationManager.IMPORTANCE_NONE)
            throw new IllegalStateException("Recording notification is disabled");
        PendingIntent stop = PendingIntent.getService(this, NOTIFICATION_ID,
                new Intent(this, ScreenOffRecordingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Microphone recording")
                .setContentText("Double-press Volume Up or tap Stop to save")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                        "Stop and save", stop).build())
                .build();
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        foreground = true;
    }

    /** A fresh idle getService/FGS request must not leave a started, invisible service around. */
    private void retireIdleStart() {
        try { if (hasAudioPermission()) enterForeground(); }
        catch (RuntimeException e) { Log.w(TAG, "Cannot promote idle/rejected request", e); }
        finishService();
    }

    private void stopRecording(boolean save, String reason) {
        if (recording == null) return;
        main.removeCallbacks(startupTimeout);
        main.removeCallbacks(maximumDuration);
        main.removeCallbacks(workerWatchdog);
        if (policy.stopping()) main.postDelayed(cleanupTimeout, CLEANUP_GRACE_MS);
        recording.stop(save, reason);
        // Keep FGS and notification while worker finalizes output, not merely until stop is queued.
    }

    private void finishService() {
        main.removeCallbacks(startupTimeout);
        main.removeCallbacks(maximumDuration);
        main.removeCallbacks(workerWatchdog);
        main.removeCallbacks(cleanupTimeout);
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); }
            catch (RuntimeException e) { Log.w(TAG, "Wake lock release failed", e); }
            wakeLock = null;
        }
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        if (!destroyed) stopSelfResult(latestStartId);
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (recording != null) recording.stop(true, "Service destroyed");
        finishService();
        if (receiverRegistered) {
            try { unregisterReceiver(receiver); }
            catch (RuntimeException e) { Log.w(TAG, "Receiver cleanup failed", e); }
        }
        if (modeRegistered) {
            try { audio.removeOnModeChangedListener(modeListener); }
            catch (RuntimeException e) { Log.w(TAG, "Audio mode listener cleanup failed", e); }
        }
        if (appOpsRegistered) {
            try { appOps.stopWatchingMode(opListener); }
            catch (RuntimeException e) { Log.w(TAG, "AppOps listener cleanup failed", e); }
        }
        super.onDestroy();
    }
}
