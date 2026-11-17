/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.recording;

/** Android-free admission and gesture state; confined to the service main thread. */
final class RecordingPolicy {
    static final long PRIVACY_FRESH_MS = 2_500;
    static final long SWITCH_INTERVAL_MS = 1_000;
    static final long STOPPED_BURST_MS = 10_000;
    static final long HEALTH_TIMEOUT_MS = 15_000;
    enum State { IDLE, STARTING, RECORDING, STOPPING }
    enum Decision { IGNORE, START, STOP }
    private State state = State.IDLE;
    // Main-thread-only, process-wide: service recreation must not reset the gesture interval
    // or turn an identified retransmission into a new toggle. IDs are unique per gesture.
    private static final String[] recentIds = new String[64];
    private static int cursor;
    private static long lastSwitchAt = -1;
    private static long switchVersion;
    private long pendingStartVersion = -1;
    private long beforeStartAt;
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

    /** Deduplicate across actions and service instances, independently of the switch interval. */
    boolean newRequest(String requestId) {
        if (requestId == null) return true;
        for (String recentId : recentIds) {
            if (requestId.equals(recentId)) return false;
        }
        recentIds[cursor] = requestId;
        cursor = (cursor + 1) % recentIds.length;
        return true;
    }

    private static boolean intervalElapsed(long now) {
        return lastSwitchAt < 0 || (now >= lastSwitchAt
                && now - lastSwitchAt >= SWITCH_INTERVAL_MS);
    }

    /** Decisions do not consume the interval; rejected/no-op requests never postpone it. */
    Decision toggle(long now) {
        if (state == State.STOPPING || !intervalElapsed(now)) return Decision.IGNORE;
        return state == State.IDLE ? Decision.START : Decision.STOP;
    }

    Decision stopOnly(long now) {
        return (state == State.STARTING || state == State.RECORDING) && intervalElapsed(now)
                ? Decision.STOP : Decision.IGNORE;
    }

    Decision stopIfRunning(String burstId, long now) {
        Decision decision = stopOnly(now);
        if (decision == Decision.STOP && burstId != null) {
            stoppedBursts[stoppedCursor] = burstId;
            stoppedAt[stoppedCursor] = now;
            stoppedCursor = (stoppedCursor + 1) % stoppedBursts.length;
        }
        return decision;
    }

    Decision startOnly(String burstId, long now) {
        if (state != State.IDLE || !intervalElapsed(now)) return Decision.IGNORE;
        if (burstId != null) {
            for (int i = 0; i < stoppedBursts.length; i++) {
                if (burstId.equals(stoppedBursts[i]) && now >= stoppedAt[i]
                        && now - stoppedAt[i] <= STOPPED_BURST_MS) return Decision.IGNORE;
            }
        }
        return Decision.START;
    }

    void starting(long now) {
        if (state != State.IDLE) throw new IllegalStateException("Recorder already busy");
        state = State.STARTING;
        beforeStartAt = lastSwitchAt;
        lastSwitchAt = now;
        pendingStartVersion = ++switchVersion;
    }
    void recording() {
        if (state == State.STARTING) {
            state = State.RECORDING;
            pendingStartVersion = -1;
        }
    }
    /** User stop only; safety/watchdog stops bypass the gesture interval. */
    void acceptedStop(long now) {
        lastSwitchAt = now;
        ++switchVersion;
        pendingStartVersion = -1;
    }
    boolean stopping() {
        if (state == State.IDLE || state == State.STOPPING) return false;
        state = State.STOPPING;
        return true;
    }
    void finished() {
        // Admission/FGS failures never call starting(); worker/native failures roll back the
        // provisional start unless a later accepted switch has already replaced it.
        if (pendingStartVersion >= 0 && pendingStartVersion == switchVersion) {
            lastSwitchAt = beforeStartAt;
            ++switchVersion;
        }
        pendingStartVersion = -1;
        state = State.IDLE;
    }
    State state() { return state; }

    /** Only our recorder's active configuration matters; peers may legitimately coexist. */
    static boolean ownRecordingUsable(int ownSession, boolean ownSilenced) {
        return ownSession > 0 && !ownSilenced;
    }
}
