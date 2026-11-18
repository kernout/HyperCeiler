/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** App-private bounded breadcrumbs, never arbitrary strings, identifiers, URIs or media. */
final class VideoDiagnostics {
    static final int MAX_BYTES = 32 * 1024;
    enum Event {
        SERVICE_CREATED, MONITORS_READY, MONITORS_FAILED, INTENT_RECEIVED,
        FOREGROUND_START, FOREGROUND_READY, REQUEST_REJECTED, TOGGLE_START, TOGGLE_STOP,
        TOGGLE_IGNORED, ADMISSION_READY, ADMISSION_FAILED, MONITOR_ADMISSION_FAILED,
        PERMISSION_ADMISSION_FAILED, APPOPS_ADMISSION_FAILED, PRIVACY_PREFLIGHT_FAILED, WORKER_START, WORKER_FAILED,
        PREFLIGHT, CAMERA_SELECTED, OUTPUT_PENDING, RECORDER_PREPARE, RECORDER_PREPARED,
        CAMERA_OPEN_REQUEST, CAMERA_OPENED, SESSION_CREATE, SESSION_QUERY_UNAVAILABLE,
        SESSION_CONFIGURED, SESSION_FAILED, REPEATING_REQUEST, RECORDER_START,
        RECORDER_STARTED, FIRST_FRAME, AUDIO_VERIFIED, CAMERA_FAILED, CAPTURE_FAILED,
        SAFETY_FAILED, PHYSICAL_RESULT_MISSING, FPS_ECHO_FAILED, FRAME_TIMESTAMP_FAILED,
        FRAME_CADENCE_FAILED, FRAMES_STALLED, MIC_SILENCED, MIC_SESSION_MISSING, RECORDER_ERROR, RECORDER_LIMIT, STOP_REQUESTED, CLEANUP_START,
        RECORDER_STOP, RECORDER_STOPPED, RECORDER_STOP_FAILED, FILE_VALIDATE,
        FILE_VALIDATED, PUBLISH, PUBLISHED, OUTPUT_REJECTED, CLEANUP_FAILED,
        CLEANUP_STALLED, FINISHED_SAVED, FINISHED_UNSAVED, SERVICE_DESTROYED
    }
    enum Failure { NONE, SECURITY, IO, ARGUMENT, STATE, OTHER }
    private final File directory;
    VideoDiagnostics(File privateFilesDirectory) {
        directory = new File(privateFilesDirectory, "video-diagnostics");
    }
    void record(Event event) { record(event, null); }
    void record(Event event, Throwable failure) {
        // The exception message/stack may contain output URIs or vendor identifiers: never log it.
        Failure kind = failure == null ? Failure.NONE : failure instanceof SecurityException ? Failure.SECURITY
                : failure instanceof IOException ? Failure.IO : failure instanceof IllegalArgumentException ? Failure.ARGUMENT
                : failure instanceof IllegalStateException ? Failure.STATE : Failure.OTHER;
        byte[] line = (Long.toString(System.nanoTime() / 1_000_000L) + " " + event.name()
                + " " + kind.name() + "\n").getBytes(StandardCharsets.US_ASCII);
        synchronized (VideoDiagnostics.class) {
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) return;
                File current = new File(directory, "current.log");
                File previous = new File(directory, "previous.log");
                if (current.length() + line.length > MAX_BYTES) {
                    if (previous.exists() && !previous.delete()) return;
                    if (current.exists() && !current.renameTo(previous)) return;
                }
                try (FileOutputStream stream = new FileOutputStream(current, true)) {
                    stream.write(line);
                }
            } catch (IOException | SecurityException ignored) {
                // Diagnostics are best-effort and must never interfere with sensor safety cleanup.
            }
        }
    }
}
