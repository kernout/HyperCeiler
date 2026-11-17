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
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

/**
 * No Activity, display flag, audio focus request, routing change or hidden privacy API.
 * Integration MUST declare this service non-exported, with microphone FGS type/permissions.
 * system_server uses explicit FGS starts for TOGGLE/POWER_TOGGLE/START_ONLY and ordinary service starts for
 * STOP_IF_RUNNING, with cleared Binder identity;
 * the fresh preflight is a caller contract, not authentication or a runtime permission bypass.
 */
public final class ScreenOffRecordingService extends Service {
    public static final String ACTION_TOGGLE =
            "com.sevtinge.hyperceiler.action.SCREEN_OFF_RECORDING_TOGGLE";
    /** Power gesture: may start with the screen on; also stops any existing recording. */
    public static final String ACTION_POWER_TOGGLE =
            "com.sevtinge.hyperceiler.action.SCREEN_OFF_RECORDING_POWER_TOGGLE";
    public static final String ACTION_STOP =
            "com.sevtinge.hyperceiler.action.SCREEN_OFF_RECORDING_STOP";
    public static final String ACTION_STOP_IF_RUNNING =
            "com.sevtinge.hyperceiler.action.SCREEN_OFF_RECORDING_STOP_IF_RUNNING";
    public static final String ACTION_START_ONLY =
            "com.sevtinge.hyperceiler.action.SCREEN_OFF_RECORDING_START_ONLY";
    public static final String EXTRA_BURST_ID = "hyperceiler.recording_burst_id";
    public static final String EXTRA_INTERACTIVE = "hyperceiler.recording_interactive";
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
            // Screen transitions and audio mode/call changes never stop the recorder.
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
        // Stop is always routed BEFORE privacy, screen, unlock or permission gates.
        // Hook must send STOP_IF_RUNNING via ordinary startServiceAsUser, not FGS start:
        // Android 15 bringDownServiceLocked crashes an unpromoted fgRequired service.
        RecordingPolicy.Decision decision;
        boolean startOnly = ACTION_START_ONLY.equals(action);
        try {
            long now = SystemClock.elapsedRealtime();
            boolean stop = ACTION_STOP.equals(action) || ACTION_STOP_IF_RUNNING.equals(action);
            boolean toggle = ACTION_TOGGLE.equals(action) || ACTION_POWER_TOGGLE.equals(action);
            if (stop || toggle || startOnly) {
                if (!policy.newRequest(requestIdentity(intent))) {
                    if (recording == null) {
                        if (stop) retireIdleStart();
                        else retireRejectedForegroundStart();
                    }
                    return START_NOT_STICKY;
                }
            }
            if (stop) {
                decision = ACTION_STOP_IF_RUNNING.equals(action)
                        ? policy.stopIfRunning(burstIdentity(intent), now) : policy.stopOnly(now);
                if (decision == RecordingPolicy.Decision.STOP) {
                    policy.acceptedStop(now);
                    stopRecording(true, "Explicit stop/stop-if-running");
                } else if (recording == null) retireIdleStart();
                return START_NOT_STICKY;
            }
            if (startOnly) decision = policy.startOnly(burstIdentity(intent), now);
            else if (toggle) decision = policy.toggle(now);
            else {
                if (recording == null) retireIdleStart();
                return START_NOT_STICKY;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Malformed recording request", e);
            if (recording == null) retireIdleStart();
            return START_NOT_STICKY;
        }
        if (decision == RecordingPolicy.Decision.STOP) {
            policy.acceptedStop(SystemClock.elapsedRealtime());
            stopRecording(true, "Double-press toggle");
            return START_NOT_STICKY;
        }
        if (decision == RecordingPolicy.Decision.IGNORE) {
            if (recording == null) retireRejectedForegroundStart();
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
            // These actions arrive via startForegroundService: even a rejected start must
            // fulfill that contract. No MediaRecorder is created until all gates pass.
            enterForeground();
            UserManager users = getSystemService(UserManager.class);
            boolean privacy = RecordingPolicy.freshPrivacy(
                    intent.getBooleanExtra(EXTRA_PRIVACY_CHECKED, false),
                    intent.getLongExtra(EXTRA_PRIVACY_CHECKED_AT, -1), SystemClock.elapsedRealtime());
            if (!monitorsReady || !RecordingPolicy.admitted(hasAudioPermission(),
                    power != null && (!power.isInteractive()
                            || ACTION_POWER_TOGGLE.equals(action)
                            || (startOnly && intent.getBooleanExtra(EXTRA_INTERACTIVE, false))),
                    users != null && users.isUserUnlocked(),
                    users != null && users.isUserForeground(), privacy,
                    audio != null && !audio.isMicrophoneMute())) {
                Log.w(TAG, "Recording start rejected by admission checks");
                finishService();
                return START_NOT_STICKY;
            }
            policy.starting(SystemClock.elapsedRealtime());
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

    @SuppressWarnings("deprecation")
    private static String burstIdentity(Intent intent) {
        Object id = intent.getExtras() == null ? null : intent.getExtras().get(EXTRA_BURST_ID);
        if (id != null && !(id instanceof String))
            throw new IllegalArgumentException("Recording burst ID must be a String");
        if (id instanceof String && (((String) id).isEmpty() || ((String) id).length() > 256))
            throw new IllegalArgumentException("Invalid recording burst ID length");
        return (String) id;
    }

    @SuppressWarnings("deprecation") // Accept the hook's String/Long ID without hidden APIs.
    private static String requestIdentity(Intent intent) {
        Object id = intent.getExtras() == null ? null : intent.getExtras().get(EXTRA_REQUEST_ID);
        if (id instanceof String || id instanceof Long || id instanceof Integer)
            return id.getClass().getSimpleName() + ":" + id;
        if (id != null) throw new IllegalArgumentException("Unsupported recording request ID");
        // Legacy callers without a request ID reuse their preflight timestamp as identity.
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
        NotificationChannel channel = new NotificationChannel(CHANNEL, "快捷麦克风录音",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("仅录制麦克风外部声音；可亮屏双击电源键或熄屏双击音量上键停止");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
        // POST_NOTIFICATIONS and user notification/channel settings control visibility,
        // not microphone admission. Always fulfill Android's microphone FGS contract;
        // Android continues to enforce RECORD_AUDIO, AppOps and microphone indicators.
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("正在录制麦克风外部声音")
                .setContentText("亮屏双击电源键或熄屏双击音量上键停止并保存；最长两小时自动保存")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        foreground = true;
    }

    /** Fulfill an idle, ignored TOGGLE/POWER_TOGGLE/START_ONLY FGS delivery without starting a microphone. */
    private void retireRejectedForegroundStart() {
        try { if (hasAudioPermission()) enterForeground(); }
        catch (RuntimeException e) { Log.w(TAG, "Cannot fulfill rejected FGS delivery", e); }
        finishService();
    }

    /** An idle ordinary STOP_IF_RUNNING must never promote or flash a notification. */
    private void retireIdleStart() {
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
        if (appOpsRegistered) {
            try { appOps.stopWatchingMode(opListener); }
            catch (RuntimeException e) { Log.w(TAG, "AppOps listener cleanup failed", e); }
        }
        super.onDestroy();
    }
}
