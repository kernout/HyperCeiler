/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

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

/** Explicit non-exported FGS endpoint. No Activity, preview UI, screen lock or privacy API. */
public final class VolumeVideoService extends Service {
    public static final String ACTION = "com.sevtinge.hyperceiler.action.VOLUME_VIDEO_TOGGLE";
    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_REQUESTED_AT = "requested_at";
    public static final String EXTRA_REQUEST_USER = "request_user";
    // Same fresh system_server sensor-privacy attestation used by the existing camera/audio
    // services. Not authentication: this service MUST remain non-exported. The app never calls
    // SensorPrivacyManager methods guarded by OBSERVE_SENSOR_PRIVACY.
    public static final String EXTRA_CAMERA_PRIVACY_CHECKED = "hyperceiler.sensor_privacy_checked";
    public static final String EXTRA_CAMERA_PRIVACY_CHECKED_AT = "hyperceiler.sensor_privacy_checked_at";
    public static final String EXTRA_MIC_PRIVACY_CHECKED = "hyperceiler.microphone_privacy_checked";
    public static final String EXTRA_MIC_PRIVACY_CHECKED_AT = "hyperceiler.microphone_privacy_checked_at";
    private static final String TAG = "HCVolumeVideo";
    private static final String CHANNEL = "volume_video_v1";
    private static final int NOTIFICATION = 0x484356, RESULT_NOTIFICATION = 0x484357;
    private static volatile boolean running;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VideoRecordingPolicy policy = new VideoRecordingPolicy();
    private VideoRecording recording;
    private AppOpsManager appOps;
    private AudioManager audio;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground, destroyed, receiverRegistered, opsRegistered, monitorsReady;
    private int latestStartId;
    private final Runnable startupTimeout = () -> stopRecording("Video startup exceeded 15 seconds");
    private final Runnable cleanupTimeout = () -> {
        report("video_recording_error", "Video cleanup stalled; output remains unpublished", true);
        // Worker still owns FD/native state and process gate. Never close them on this thread.
        retire();
    };
    private final Runnable health = new Runnable() {
        @Override public void run() {
            if (recording == null || destroyed || policy.state() == VideoRecordingPolicy.State.STOPPING) return;
            try {
                if (!allowed() || !opsAllowed()) stopRecording("Camera/MIC permission, AppOps or user changed");
                else if (policy.state() == VideoRecordingPolicy.State.RECORDING
                        && (SystemClock.elapsedRealtime() < recording.lastHealth()
                        || SystemClock.elapsedRealtime() - recording.lastHealth() > 5_000))
                    stopRecording("Video worker health check stalled");
            } catch (RuntimeException e) { stopRecording("Cannot verify video safety: " + e); }
            if (recording != null && policy.state() != VideoRecordingPolicy.State.STOPPING)
                main.postDelayed(this, 1_000);
        }
    };
    private final AppOpsManager.OnOpChangedListener opListener = (op, pkg) -> main.post(() -> {
        if (recording == null || (pkg != null && !getPackageName().equals(pkg))) return;
        try {
            if (!allowed() || !opsAllowed()) stopRecording("Camera/MIC permission or AppOps revoked");
        } catch (RuntimeException e) { stopRecording("AppOps verification failed: " + e); }
    });
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("android.intent.action.USER_SWITCHED".equals(action)
                    || Intent.ACTION_USER_BACKGROUND.equals(action) || Intent.ACTION_SHUTDOWN.equals(action))
                stopRecording("User switched/backgrounded or device shutting down");
            else if (AudioManager.ACTION_MICROPHONE_MUTE_CHANGED.equals(action)) {
                try { if (audio == null || audio.isMicrophoneMute()) stopRecording("Microphone muted"); }
                catch (RuntimeException e) { stopRecording("Cannot verify microphone mute state"); }
            }
            // SCREEN_ON/OFF/USER_PRESENT intentionally do not change recording state.
        }
    };
    public static boolean isRunning() { return running; }
    @Override public void onCreate() {
        super.onCreate();
        try {
            appOps = getSystemService(AppOpsManager.class);
            audio = getSystemService(AudioManager.class);
            if (appOps == null || audio == null) throw new IllegalStateException("AppOps/audio unavailable");
            IntentFilter filter = new IntentFilter("android.intent.action.USER_SWITCHED");
            filter.addAction(Intent.ACTION_USER_BACKGROUND);
            filter.addAction(Intent.ACTION_SHUTDOWN);
            filter.addAction(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED);
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
            appOps.startWatchingMode(AppOpsManager.OPSTR_CAMERA, getPackageName(), opListener);
            opsRegistered = true;
            appOps.startWatchingMode(AppOpsManager.OPSTR_RECORD_AUDIO, getPackageName(), opListener);
            monitorsReady = true;
        } catch (RuntimeException e) { Log.e(TAG, "Video safety monitors unavailable", e); }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        try {
            // Proxy uses startForegroundServiceAsUser for every toggle. Even an idle duplicate
            // must satisfy that contract before it is retired; no sensor is opened by promotion.
            if (!foreground) enterForeground();
            if (intent == null || !ACTION.equals(intent.getAction())) {
                if (recording == null) retire();
                return START_NOT_STICKY;
            }
            UserManager users = getSystemService(UserManager.class);
            long now = SystemClock.elapsedRealtime();
            long at = intent.getLongExtra(EXTRA_REQUESTED_AT, -1);
            if (!VideoRecordingPolicy.accept(intent.getStringExtra(EXTRA_REQUEST_ID), at, now,
                    intent.getIntExtra(EXTRA_REQUEST_USER, -1), Process.myUid() / 100_000,
                    users != null && users.isUserForeground(), users != null && users.isUserUnlocked())) {
                Log.w(TAG, "Rejected duplicate, stale, malformed, locked or wrong-user video request");
                if (recording == null) retire();
                return START_NOT_STICKY;
            }
            // User-stop admission uses gesture elapsedRealtime, so delayed delivery cannot turn
            // a <10s press into an accepted stop. No deferred toggle or delayed stop is retained.
            VideoRecordingPolicy.Decision decision = policy.toggle(at);
            if (decision == VideoRecordingPolicy.Decision.STOP) {
                stopRecording("Video toggle stopped");
                return START_NOT_STICKY;
            }
            if (decision == VideoRecordingPolicy.Decision.IGNORE) return START_NOT_STICKY;
            if (!monitorsReady || !allowed() || !opsAllowed()
                    || !freshPrivacy(intent, EXTRA_CAMERA_PRIVACY_CHECKED, EXTRA_CAMERA_PRIVACY_CHECKED_AT, now)
                    || !freshPrivacy(intent, EXTRA_MIC_PRIVACY_CHECKED, EXTRA_MIC_PRIVACY_CHECKED_AT, now))
                throw new SecurityException("Video requires foreground unlocked user, CAMERA/MIC permission, "
                        + "unmuted MIC and fresh system-server camera/MIC privacy preflight");
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null) throw new IllegalStateException("Power service unavailable");
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyperCeiler:VolumeVideo");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(VideoRecording.MAX_DURATION_MS + 30_000);
            policy.starting();
            running = true;
            recording = new VideoRecording(getApplicationContext(), intent.getIntExtra(EXTRA_REQUEST_USER, -1),
                    new VideoRecording.Listener() {
                @Override public void started(VideoRecording source, long actualStartElapsed) {
                    main.post(() -> {
                        if (destroyed || recording != source || policy.state() != VideoRecordingPolicy.State.STARTING) return;
                        policy.started(actualStartElapsed);
                        main.removeCallbacks(startupTimeout);
                        updateNotification("video_recording_active", "Recording rear main camera · 1080p60 · microphone on");
                    });
                }
                @Override public void finished(VideoRecording source, boolean saved, String reason) {
                    main.post(() -> {
                        if (recording != source) return;
                        recording = null;
                        policy.finished();
                        running = false;
                        if (!destroyed) report(saved ? "video_recording_stopped" : "video_recording_error", reason, !saved);
                        retire();
                    });
                }
            });
            main.postDelayed(startupTimeout, 15_000);
            main.postDelayed(health, 1_000);
            recording.start();
        } catch (RuntimeException e) {
            report("video_recording_error", "Video request failed: " + e, true);
            if (recording != null) stopRecording("Video service admission failed: " + e);
            else retire();
        }
        return START_NOT_STICKY;
    }
    private static boolean freshPrivacy(Intent intent, String flag, String timestamp, long now) {
        return intent.getBooleanExtra(flag, false) && VideoRecordingPolicy.fresh(intent.getLongExtra(timestamp, -1), now);
    }
    private boolean allowed() {
        UserManager users = getSystemService(UserManager.class);
        return users != null && users.isUserForeground() && users.isUserUnlocked()
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && audio != null && !audio.isMicrophoneMute();
    }
    private boolean opsAllowed() {
        if (appOps == null) return false;
        for (String op : new String[] {AppOpsManager.OPSTR_CAMERA, AppOpsManager.OPSTR_RECORD_AUDIO}) {
            int mode = appOps.unsafeCheckOpNoThrow(op, Process.myUid(), getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED && mode != AppOpsManager.MODE_FOREGROUND) return false;
        }
        return true;
    }
    private String text(String name, String fallback) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        return id == 0 ? fallback : getString(id);
    }
    private Notification notification(String state, String detail, boolean ongoing) {
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle(text("video_recording_title", "Rear main camera video"))
                .setContentText(text(state, detail)).setStyle(new Notification.BigTextStyle().bigText(detail))
                .setCategory(ongoing ? Notification.CATEGORY_SERVICE : Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(ongoing).setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE).build();
    }
    private NotificationManager manager() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException("Notification service unavailable");
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                text("video_recording_channel", "Camera and microphone video"), NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false); channel.enableLights(false);
        manager.createNotificationChannel(channel);
        return manager;
    }
    private void enterForeground() {
        manager();
        startForeground(NOTIFICATION, notification("video_recording_starting", "Starting 1080p60 video with microphone", true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        foreground = true;
    }
    private void updateNotification(String state, String detail) {
        try { manager().notify(NOTIFICATION, notification(state, detail, true)); }
        catch (RuntimeException e) { Log.w(TAG, "Video notification update failed", e); }
    }
    private void report(String state, String reason, boolean error) {
        if (error) Log.e(TAG, reason); else Log.i(TAG, reason);
        try { manager().notify(RESULT_NOTIFICATION, notification(state, reason, false)); }
        catch (RuntimeException e) { Log.e(TAG, "Video result notification unavailable", e); }
    }
    private void stopRecording(String reason) {
        if (recording == null) return;
        if (policy.stopping()) {
            main.removeCallbacks(startupTimeout);
            main.removeCallbacks(health);
            main.postDelayed(cleanupTimeout, 10_000);
        }
        recording.stop(reason);
    }
    private void retire() {
        main.removeCallbacks(startupTimeout); main.removeCallbacks(health); main.removeCallbacks(cleanupTimeout);
        if (recording == null) { running = false; policy.finished(); }
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); }
            catch (RuntimeException e) { Log.w(TAG, "Video wake lock release failed", e); }
            wakeLock = null;
        }
        if (foreground) {
            try { stopForeground(STOP_FOREGROUND_REMOVE); }
            catch (RuntimeException e) { Log.w(TAG, "Video foreground retirement failed", e); }
            foreground = false;
        }
        if (!destroyed) stopSelfResult(latestStartId);
    }
    @Override public void onDestroy() {
        destroyed = true;
        if (recording != null) recording.stop("Video service destroyed");
        running = false;
        retire();
        if (receiverRegistered) {
            try { unregisterReceiver(receiver); } catch (RuntimeException e) { Log.w(TAG, "Receiver cleanup failed", e); }
        }
        if (opsRegistered) {
            try { appOps.stopWatchingMode(opListener); } catch (RuntimeException e) { Log.w(TAG, "AppOps cleanup failed", e); }
        }
        super.onDestroy();
    }
}
