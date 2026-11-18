/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

import java.util.UUID;

/** Android-free, service-main-thread lifecycle. An ignored toggle never schedules a stop. */
public final class VideoRecordingPolicy {
    public static final long MIN_RECORDING_MS = 10_000;
    public static final long REQUEST_FRESH_MS = 2_500;
    public enum State { IDLE, STARTING, RECORDING, STOPPING }
    public enum Decision { IGNORE, START, STOP }
    // Process-wide receipts survive service recreation; never replay intents after process death.
    private static final String[] RECEIPTS = new String[128];
    private static int receiptCursor;
    private State state = State.IDLE;
    private long startedAt = -1;

    public static boolean fresh(long at, long now) {
        return at >= 0 && now >= at && now - at <= REQUEST_FRESH_MS;
    }
    public static boolean validId(String id) {
        if (id == null || id.length() != 36) return false;
        try { return UUID.fromString(id).toString().equalsIgnoreCase(id); }
        catch (IllegalArgumentException e) { return false; }
    }
    public static synchronized boolean accept(String id, long at, long now,
            int requestUser, int ownUser, boolean foregroundUser, boolean unlocked) {
        if (!validId(id) || !fresh(at, now) || requestUser != ownUser
                || !foregroundUser || !unlocked) return false;
        String canonical = id.toLowerCase(java.util.Locale.ROOT);
        for (String receipt : RECEIPTS) if (canonical.equals(receipt)) return false;
        RECEIPTS[receiptCursor] = canonical;
        receiptCursor = (receiptCursor + 1) % RECEIPTS.length;
        return true;
    }
    public Decision toggle(long gestureAt) {
        if (state == State.IDLE) return Decision.START;
        if (state == State.RECORDING && gestureAt >= startedAt
                && gestureAt - startedAt >= MIN_RECORDING_MS) return Decision.STOP;
        return Decision.IGNORE;
    }
    public void starting() {
        if (state != State.IDLE) throw new IllegalStateException("Video already active");
        state = State.STARTING;
    }
    /** Call only after MediaRecorder.start() really returned successfully. */
    public void started(long elapsedRealtime) {
        if (state != State.STARTING || elapsedRealtime < 0)
            throw new IllegalStateException("Unexpected native video start");
        startedAt = elapsedRealtime;
        state = State.RECORDING;
    }
    /** Safety/fault stops intentionally bypass the user gesture minimum. */
    public boolean stopping() {
        if (state == State.IDLE || state == State.STOPPING) return false;
        state = State.STOPPING;
        return true;
    }
    public void finished() { state = State.IDLE; startedAt = -1; }
    public State state() { return state; }
    public long startedAt() { return startedAt; }
    static boolean normal60(boolean exactSize, boolean exactRange, long minDurationNs) {
        return exactSize && exactRange && minDurationNs > 0 && minDurationNs <= 16_666_667L;
    }
    /** Request-key support does not imply a corresponding result key exists. */
    static boolean fpsEchoValid(boolean advertised, Integer lower, Integer upper) {
        if (lower == null || upper == null) return !advertised && lower == null && upper == null;
        return lower == 60 && upper == 60;
    }
    static boolean measured60(long samples, long firstUs, long lastUs) {
        if (samples < 2 || firstUs < 0 || lastUs <= firstUs) return false;
        double fps = (samples - 1) * 1_000_000.0 / (lastUs - firstUs);
        return fps >= 58.5 && fps <= 61.5;
    }
}
