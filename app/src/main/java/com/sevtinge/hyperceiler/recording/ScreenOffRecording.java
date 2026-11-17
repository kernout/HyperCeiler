/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.recording;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.sevtinge.hyperceiler.recording.RecordingDiagnostics.Event.*;

/** Recorder, FD and MediaStore ownership are confined to one worker, including every exit path. */
final class ScreenOffRecording {
    interface Listener {
        void started(ScreenOffRecording recording);
        void finishing(ScreenOffRecording recording);
        void finished(ScreenOffRecording recording, boolean saved);
    }
    private static final String TAG = ScreenOffRecordingService.TAG;
    // Survives service recreation, so a timed-out native call cannot overlap a replacement worker.
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final String PREFIX = "HC_Record_";
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/oppose/";
    private static final long INITIAL_CONFIG_GRACE_MS = 3_000;
    private static final long MISSING_CONFIG_GRACE_MS = 1_000;
    private static final long CONFIG_RETRY_MS = 100;
    private final Context context;
    private final RecordingDiagnostics diagnostics;
    private final Listener listener;
    private final long deadline;
    private final HandlerThread thread = new HandlerThread(TAG + "Worker");
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean discard = new AtomicBoolean();
    private Handler worker;
    private MediaRecorder recorder;
    private AudioManager audio;
    private ParcelFileDescriptor output;
    private Uri pending;
    private boolean ownsGate;
    private boolean callbackRegistered;
    private boolean started;
    private boolean finished;
    private boolean ownershipVerified;
    private long initialConfigDeadline;
    private long missingConfigDeadline;
    private long checkDue = Long.MAX_VALUE;
    // Only successful worker checks advance this timestamp; main must detect a blocked Binder call.
    private volatile long lastHealthCheck;

    ScreenOffRecording(Context context, long deadline, Listener listener) {
        this.context = context;
        diagnostics = new RecordingDiagnostics(context);
        this.deadline = deadline;
        this.listener = listener;
    }

    void start() {
        try {
            thread.start();
            worker = new Handler(thread.getLooper());
            if (!worker.post(this::begin)) throw new IllegalStateException("Worker rejected startup");
        } catch (RuntimeException e) {
            thread.quitSafely();
            listener.finished(this, false);
            throw e;
        }
    }

    long lastHealthCheck() { return lastHealthCheck; }

    void stop(boolean save, String reason) {
        Log.i(TAG, "Stop requested: " + reason);
        if (!save) discard.set(true);
        stopRequested.set(true);
        Handler handler = worker;
        if (handler != null) handler.post(() -> {
            if (finished) return;
            diagnostics.event(STOP_REQUESTED);
            finish(!discard.get());
        });
    }

    private void ensureStartAllowed() throws IOException {
        if (stopRequested.get() || SystemClock.elapsedRealtime() >= deadline)
            throw new IOException("Startup cancelled or deadline expired");
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked()
                || !users.isUserForeground()) throw new IOException("User admission changed");
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) throw new SecurityException("RECORD_AUDIO revoked");
        if (audio == null || audio.isMicrophoneMute())
            throw new IOException("Microphone unavailable");
    }

    private void begin() {
        try {
            ownsGate = BUSY.compareAndSet(false, true);
            if (!ownsGate) throw new IOException("Previous recorder is still cleaning up");
            audio = context.getSystemService(AudioManager.class);
            ensureStartAllowed();
            deleteAbandonedPending();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME,
                    PREFIX + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".m4a");
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH);
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
            pending = context.getContentResolver().insert(collection(), values);
            if (pending == null) throw new IOException("MediaStore insert failed");
            output = context.getContentResolver().openFileDescriptor(pending, "rw");
            if (output == null) throw new IOException("MediaStore FD unavailable");
            recorder = new MediaRecorder(context);
            recorder.setOnErrorListener((source, what, extra) -> postToWorker(() -> {
                Log.w(TAG, "Recorder error " + what + "/" + extra);
                diagnostics.event(ERROR);
                finish(true);
            }));
            recorder.setOnInfoListener((source, what, extra) -> postToWorker(() -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) finish(true);
            }));
            audio.registerAudioRecordingCallback(recordingCallback, worker);
            callbackRegistered = true;
            ensureStartAllowed();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(48_000);
            recorder.setAudioEncodingBitRate(128_000);
            recorder.setAudioChannels(1);
            recorder.setMaxDuration((int) ScreenOffRecordingService.MAX_DURATION_MS);
            recorder.setOutputFile(output.getFileDescriptor());
            recorder.prepare();
            // MIC captures external sound only. Let Android arbitrate coexistence, including calls;
            // do not retry, change routing, audio focus, mode, or priority to win a race.
            ensureStartAllowed();
            recorder.start();
            started = true;
            // The platform publishes the own snapshot asynchronously after native start returns.
            // This deadline is written exactly once, even when callbacks repeatedly report UNKNOWN.
            initialConfigDeadline = SystemClock.elapsedRealtime() + INITIAL_CONFIG_GRACE_MS;
            diagnostics.event(NATIVE_START);
            checkRecording();
        } catch (Exception e) {
            Log.e(TAG, "Recorder startup failed", e);
            diagnostics.error(START_ERROR, e);
            finish(true);
        }
    }

    private void postToWorker(Runnable action) {
        Handler handler = worker;
        if (handler != null) handler.post(() -> {
            if (!finished) action.run();
        });
    }

    private final AudioManager.AudioRecordingCallback recordingCallback =
            new AudioManager.AudioRecordingCallback() {
        @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> ignored) {
            // Callback and timer share one queued check; never trust an old callback snapshot.
            if (started && !finished) scheduleCheck(0);
        }
    };

    private final Runnable healthCheck = () -> {
        checkDue = Long.MAX_VALUE;
        checkRecording();
    };

    /** Called only on the worker, including callback delivery through its Handler. */
    private void scheduleCheck(long delay) {
        if (finished) return;
        long due = SystemClock.elapsedRealtime() + delay;
        if (due >= checkDue) return;
        worker.removeCallbacks(healthCheck);
        checkDue = due;
        worker.postDelayed(healthCheck, delay);
    }

    private void checkRecording() {
        if (finished || !started) return;
        try {
            // UNKNOWN never exempts permission, user, mute, stop or the two-hour deadline.
            ensureStartAllowed();
        } catch (IOException | RuntimeException e) {
            diagnostics.error(SAFETY_STOP, e);
            finish(true);
            return;
        }
        AudioRecordingConfiguration own;
        try {
            own = recorder.getActiveRecordingConfiguration();
        } catch (IllegalStateException e) {
            // Only a transient own-query state shares the bounded UNKNOWN window.
            diagnostics.error(OWN_TRANSIENT_ERROR, e);
            configUnknown();
            return;
        } catch (RuntimeException e) {
            // SecurityException and other fatal query errors are never treated as UNKNOWN.
            diagnostics.error(ERROR, e);
            finish(true);
            return;
        }
        try {
            // A stop/revocation may have arrived while the synchronous Binder query was running.
            ensureStartAllowed();
            if (own != null) {
                // A silenced session=0 is still silenced, not metadata awaiting publication.
                if (own.isClientSilenced()) {
                    diagnostics.event(SILENCED);
                    finish(true);
                    return;
                }
                if (own.getClientAudioSessionId() > 0) {
                    long now = SystemClock.elapsedRealtime();
                    // A usable result obtained too late cannot resurrect an expired window.
                    if (configWindowExpired(now)) return;
                    if (!ownershipVerified) {
                        ownershipVerified = true;
                        diagnostics.event(OWN_VERIFIED);
                        lastHealthCheck = now;
                        listener.started(this);
                    } else {
                        if (missingConfigDeadline != 0) diagnostics.event(OWN_RECOVERED);
                        lastHealthCheck = now;
                    }
                    missingConfigDeadline = 0;
                    scheduleCheck(1_000);
                    return;
                }
                diagnostics.event(OWN_SESSION_INVALID);
            } else {
                diagnostics.event(OWN_NULL);
            }
        } catch (IOException | RuntimeException e) {
            diagnostics.error(ERROR, e);
            finish(true);
            return;
        }
        configUnknown();
    }

    private void configUnknown() {
        try {
            ensureStartAllowed();
        } catch (IOException | RuntimeException e) {
            diagnostics.error(SAFETY_STOP, e);
            finish(true);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (ownershipVerified && missingConfigDeadline == 0)
            missingConfigDeadline = now + MISSING_CONFIG_GRACE_MS;
        if (configWindowExpired(now)) return;
        long expires = ownershipVerified ? missingConfigDeadline : initialConfigDeadline;
        scheduleCheck(Math.min(CONFIG_RETRY_MS, expires - now));
    }

    private boolean configWindowExpired(long now) {
        long expires = ownershipVerified ? missingConfigDeadline : initialConfigDeadline;
        if (expires == 0 || now < expires) return false;
        diagnostics.event(ownershipVerified ? MISSING_TIMEOUT : STARTUP_TIMEOUT);
        finish(true); // finish requires previous verification before it can publish anything.
        return true;
    }

    private static Uri collection() {
        return MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    /** Recover only our own unpublished rows, including leftovers after a process/boot death. */
    private void deleteAbandonedPending() throws IOException {
        Bundle query = new Bundle();
        query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.Audio.Media.IS_PENDING + "=1 AND "
                        + MediaStore.Audio.Media.OWNER_PACKAGE_NAME + "=? AND "
                        + MediaStore.Audio.Media.RELATIVE_PATH + "=? AND "
                        + MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?");
        query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[] {context.getPackageName(), RELATIVE_PATH, PREFIX + "%"});
        query.putInt(ContentResolver.QUERY_ARG_LIMIT, 64);
        try (Cursor cursor = context.getContentResolver().query(collection(),
                new String[] {MediaStore.Audio.Media._ID}, query, null)) {
            if (cursor == null) throw new IOException("Cannot query abandoned recordings");
            int count = 0;
            while (cursor.moveToNext() && count++ < 64) {
                Uri orphan = Uri.withAppendedPath(collection(), Long.toString(cursor.getLong(0)));
                context.getContentResolver().delete(orphan, null, null);
            }
        }
    }

    private void finish(boolean save) {
        if (finished) return;
        finished = true;
        diagnostics.event(FINISH);
        checkDue = Long.MAX_VALUE;
        listener.finishing(this);
        if (worker != null) worker.removeCallbacksAndMessages(null);
        boolean valid = false;
        boolean released = true;
        if (recorder != null) {
            if (started) {
                try {
                    recorder.stop();
                    valid = save && ownershipVerified && !discard.get();
                } catch (RuntimeException e) {
                    diagnostics.error(STOP_FAILED, e);
                    Log.w(TAG, "Recorder stop failed (possibly too short); deleting pending output", e);
                }
            }
            try { recorder.release(); }
            catch (RuntimeException e) {
                diagnostics.error(RELEASE_FAILED, e);
                released = false;
                valid = false;
                Log.e(TAG, "Recorder release failed; blocking replacement until process restart", e);
            }
            recorder = null;
        }
        if (callbackRegistered) {
            try { audio.unregisterAudioRecordingCallback(recordingCallback); }
            catch (RuntimeException e) { diagnostics.error(CLEANUP_ERROR, e); Log.w(TAG, "Callback cleanup failed", e); }
            callbackRegistered = false;
        }
        try {
            // A successful stop alone is insufficient: require actual bytes in the finalized FD.
            valid &= output != null && output.getStatSize() > 0;
        } catch (RuntimeException e) { diagnostics.error(CLEANUP_ERROR, e); valid = false; }
        if (output != null) {
            try { output.close(); }
            catch (IOException e) { diagnostics.error(CLEANUP_ERROR, e); valid = false; Log.w(TAG, "Output close failed", e); }
            output = null;
        }
        boolean saved = false;
        if (pending != null) {
            try {
                if (valid && !discard.get()) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    saved = context.getContentResolver().update(pending, values, null, null) == 1;
                }
            } catch (RuntimeException e) { diagnostics.error(PUBLICATION_ERROR, e); Log.e(TAG, "Cannot publish recording", e); }
            if (!saved) {
                try { context.getContentResolver().delete(pending, null, null); }
                catch (RuntimeException e) { diagnostics.error(CLEANUP_ERROR, e); Log.e(TAG, "Pending delete failed; retry on next start", e); }
            }
            pending = null;
        }
        diagnostics.event(saved ? PUBLICATION_SUCCESS : PUBLICATION_DISCARDED);
        // Never clear another controller's gate. If vendor release failed, fail closed.
        if (ownsGate && released) BUSY.set(false);
        thread.quitSafely();
        listener.finished(this, saved);
    }
}
