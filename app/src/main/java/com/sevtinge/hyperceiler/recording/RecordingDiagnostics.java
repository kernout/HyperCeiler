/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.recording;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Local, best-effort metadata only. Never accepts paths, URIs, messages or recording identifiers. */
final class RecordingDiagnostics {
    private static final int MAX_BYTES = 64 * 1024;
    private static final Object LOCK = new Object();
    enum Event {
        NATIVE_START, OWN_NULL, OWN_SESSION_INVALID, OWN_VERIFIED, OWN_RECOVERED,
        OWN_TRANSIENT_ERROR, SILENCED, STARTUP_TIMEOUT, MISSING_TIMEOUT, SAFETY_STOP,
        STOP_REQUESTED, START_ERROR, ERROR, FINISH, STOP_FAILED, RELEASE_FAILED,
        CLEANUP_ERROR, PUBLICATION_SUCCESS, PUBLICATION_DISCARDED, PUBLICATION_ERROR
    }
    private final Context app;

    RecordingDiagnostics(Context context) {
        app = context.getApplicationContext();
    }

    void event(Event event) { error(event, null); }

    void error(Event event, Throwable error) {
        // Only the exception class is retained: exception messages/stacks may contain private paths.
        String line = System.currentTimeMillis() + " " + SystemClock.elapsedRealtime() + " "
                + event.name() + (error == null ? "" : " " + error.getClass().getName()) + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            try {
                if (app == null) return;
                File directory = app.getFilesDir();
                if (directory == null) return;
                File current = new File(directory, "recording_events.log");
                if (current.length() + bytes.length > MAX_BYTES) {
                    File previous = new File(directory, "recording_events.log.1");
                    if (previous.exists() && !previous.delete()) return;
                    if (!current.renameTo(previous)) return;
                }
                try (FileOutputStream out = new FileOutputStream(current, true)) {
                    out.write(bytes);
                }
            } catch (IOException | RuntimeException ignored) {
                // Diagnostics must never interfere with stopping/releasing the microphone.
            }
        }
    }
}
