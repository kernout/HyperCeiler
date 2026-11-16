/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.recording;

/** Android-free admission and gesture state; confined to the service main thread. */
final class RecordingPolicy {
    static final long PRIVACY_FRESH_MS = 2_500;
    static final long DUPLICATE_MS = 500;
    static final long HEALTH_TIMEOUT_MS = 15_000;
    enum State { IDLE, STARTING, RECORDING, STOPPING }
    enum Decision { IGNORE, START, STOP }
    private State state = State.IDLE;
    private final String[] recentIds = new String[32];
    private final long[] recentTimes = new long[32];
    private int cursor;

    static boolean freshPrivacy(boolean checked, long checkedAt, long now) {
        return checked && checkedAt >= 0 && now >= checkedAt
                && now - checkedAt <= PRIVACY_FRESH_MS;
    }

    static boolean admitted(boolean permission, boolean screenOff, boolean unlocked,
            boolean foregroundUser, boolean privacy, boolean normalAudio, boolean microphoneFree) {
        return permission && screenOff && unlocked && foregroundUser && privacy
                && normalAudio && microphoneFree;
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

    Decision stopOnly() { return state == State.IDLE ? Decision.IGNORE : Decision.STOP; }

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

    /**
     * AOSP anonymization preserves session IDs (it removes UID/package), so an ordinary app
     * can still match its recorder. Reject unknown IDs on vendors that redact more fields.
     */
    static boolean ownRecordingsOnly(int ownSession, boolean ownSilenced, int[] sessions) {
        // Session IDs may be shared by multiple clients; equality alone cannot exclude a peer.
        return ownSession > 0 && !ownSilenced && sessions != null && sessions.length == 1
                && sessions[0] == ownSession;
    }
}
