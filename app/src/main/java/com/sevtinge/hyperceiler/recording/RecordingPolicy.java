/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.recording;

/** Android-free admission and gesture state; confined to the service main thread. */
final class RecordingPolicy {
    static final long PRIVACY_FRESH_MS = 2_500;
    static final long DUPLICATE_MS = 500;
    static final long STOPPED_BURST_MS = 10_000;
    static final long HEALTH_TIMEOUT_MS = 15_000;
    enum State { IDLE, STARTING, RECORDING, STOPPING }
    enum Decision { IGNORE, START, STOP }
    private State state = State.IDLE;
    private final String[] recentIds = new String[32];
    private final long[] recentTimes = new long[32];
    private int cursor;
    // Main-thread-only, process-wide: finishing the double-press may destroy/recreate the
    // service before the third press arrives. Never let that third press restart recording.
    private static final String[] stoppedBursts = new String[32];
    private static final long[] stoppedAt = new long[32];
    private static int stoppedCursor;

    static boolean freshPrivacy(boolean checked, long checkedAt, long now) {
        return checked && checkedAt >= 0 && now >= checkedAt
                && now - checkedAt <= PRIVACY_FRESH_MS;
    }

    static boolean admitted(boolean permission, boolean screenAllowed, boolean unlocked,
            boolean foregroundUser, boolean privacy, boolean microphoneUnmuted) {
        return permission && screenAllowed && unlocked && foregroundUser && privacy
                && microphoneUnmuted;
    }

    static boolean healthCheckExpired(long checkedAt, long now) {
        return checkedAt < 0 || now < checkedAt || now - checkedAt >= HEALTH_TIMEOUT_MS;
    }

    /** An identified retransmission is ignored; distinct gestures are never time-debounced. */
    Decision toggle(String requestId, long now) {
        if (requestId != null) {
            for (int i = 0; i < recentIds.length; i++) {
                if (requestId.equals(recentIds[i]) && now >= recentTimes[i]
                        && now - recentTimes[i] <= DUPLICATE_MS) return Decision.IGNORE;
            }
            recentIds[cursor] = requestId;
            recentTimes[cursor] = now;
            cursor = (cursor + 1) % recentIds.length;
        }
        if (state == State.STOPPING) return Decision.IGNORE;
        return state == State.IDLE ? Decision.START : Decision.STOP;
    }

    Decision stopOnly() {
        return state == State.STARTING || state == State.RECORDING ? Decision.STOP : Decision.IGNORE;
    }

    Decision stopIfRunning(String burstId, long now) {
        Decision decision = stopOnly();
        if (decision == Decision.STOP && burstId != null) {
            stoppedBursts[stoppedCursor] = burstId;
            stoppedAt[stoppedCursor] = now;
            stoppedCursor = (stoppedCursor + 1) % stoppedBursts.length;
        }
        return decision;
    }

    Decision startOnly(String burstId, long now) {
        if (state != State.IDLE) return Decision.IGNORE;
        if (burstId != null) {
            for (int i = 0; i < stoppedBursts.length; i++) {
                if (burstId.equals(stoppedBursts[i]) && now >= stoppedAt[i]
                        && now - stoppedAt[i] <= STOPPED_BURST_MS) return Decision.IGNORE;
            }
        }
        return Decision.START;
    }

    void starting() {
        if (state != State.IDLE) throw new IllegalStateException("Recorder already busy");
        state = State.STARTING;
    }
    void recording() { if (state == State.STARTING) state = State.RECORDING; }
    boolean stopping() {
        if (state == State.IDLE || state == State.STOPPING) return false;
        state = State.STOPPING;
        return true;
    }
    void finished() { state = State.IDLE; }
    State state() { return state; }

    /** Only our recorder's active configuration matters; peers may legitimately coexist. */
    static boolean ownRecordingUsable(int ownSession, boolean ownSilenced) {
        return ownSession > 0 && !ownSilenced;
    }
}
