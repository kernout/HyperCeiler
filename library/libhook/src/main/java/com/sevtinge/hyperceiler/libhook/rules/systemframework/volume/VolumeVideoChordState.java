/* Copyright (C) 2026 HyperCeiler Contributions */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

import java.util.Objects;

/** Serialized single-chord recognizer. Board validation is performed by the hook;
 * each key keeps its own source and DOWN timestamp, including after cancellation. */
public final class VolumeVideoChordState {
    public static final int PASS = 0, CONSUME = 1, CLAIM = 2, TRIGGER = 4;
    public static final long SLOP_MS = 120, LONG_MS = 500;
    public record Source(int device, String descriptor, int source) {}
    public record Event(int key, int action, int repeat, long down, long time,
                        Source source, boolean physical, boolean clean, int user) {}
    private final Source[] sources = new Source[2];
    private final long[] downs = new long[2];
    private int held, owned, user = -1;
    private boolean blocked, chord;
    private long firstAt, lastTime = -1, cancellation, legacyGeneration;
    private String rejection;

    public long cancellation() { return cancellation; }
    public long legacyGeneration() { return legacyGeneration; }
    public boolean hasOwnedPress() { return owned != 0; }
    public boolean tentative() { return held != 0 && !blocked && !chord; }
    public long tentativeDeadline() { return firstAt + SLOP_MS; }
    public int volumeUpDevice() { return sources[0] == null ? -1 : sources[0].device(); }
    public int volumeDownDevice() { return sources[1] == null ? -1 : sources[1].device(); }
    public String rejection() { return rejection; }
    /** Never relinquish already swallowed releases merely because a gate changed. */
    public void cancel() { blocked = held != 0; chord = false; cancellation++; legacyGeneration++; }
    /** Only the vanished device is reclaimed; the other board key must still drain. */
    public void deviceChanged(int id) {
        cancel();
        for (int i = 0; i < 2; i++) if (sources[i] != null && sources[i].device() == id) {
            held &= ~(1 << i); owned &= ~(1 << i); sources[i] = null;
        }
        if (held == 0) blocked = false;
    }
    /** Emergency drain when a live policy check throws. Only the exact previously
     * owned physical release can be retired; this path can never recognize a chord. */
    public boolean drainCancelledRelease(int key, Source source, long down) {
        int i = key == 24 ? 0 : key == 25 ? 1 : -1;
        if (i < 0 || (owned & (1 << i)) == 0 || !Objects.equals(sources[i], source) || downs[i] != down)
            return false;
        held &= ~(1 << i); owned &= ~(1 << i); sources[i] = null;
        if (held == 0) blocked = false;
        return true;
    }
    private void reject(String reason) {
        rejection = reason;
        blocked = held != 0; chord = false; cancellation++;
        // Invalid input cancels video recognition, not an ordinary camera request
        // waiting through the ambiguity window. Only CLAIM/user/device cancellation
        // may retire that legacy work.
    }
    public int accept(Event e, boolean allowed) {
        rejection = null;
        int index = e.key() == 24 ? 0 : e.key() == 25 ? 1 : -1;
        if (index < 0 || !e.physical()) {
            if (e.action() == 0) reject(index < 0 ? "other-key" : "untrusted-source");
            return PASS;
        }
        int bit = 1 << index;
        boolean pair = (held & bit) != 0 && Objects.equals(sources[index], e.source())
            && downs[index] == e.down();
        boolean consume = pair && (owned & bit) != 0;
        String invalid = !allowed ? "live-gate" : !e.clean() ? "event-flags-or-freshness"
            : e.user() < 0 || (held != 0 && user != e.user()) ? "user-change"
            : e.repeat() != 0 ? "repeat" : e.down() < 0 || e.time() < e.down()
            || e.time() < lastTime ? "event-time" : e.action() != 0 && e.action() != 1 ? "action" : null;
        if (invalid != null) reject(invalid);
        if (e.action() == 1) {
            if (!pair) { reject("unpaired-release"); return PASS; }
            if (e.time() - firstAt >= LONG_MS) reject("long-press");
            held &= ~bit; owned &= ~bit; sources[index] = null;
            boolean trigger = held == 0 && chord && !blocked && invalid == null;
            if (held == 0) { chord = false; blocked = false; }
            lastTime = Math.max(lastTime, e.time());
            return (consume ? CONSUME : PASS) | (trigger ? TRIGGER : 0);
        }
        if (e.action() != 0) return consume ? CONSUME : PASS;
        if (pair || (held & bit) != 0) {
            reject("duplicate-or-source-change");
            return consume ? CONSUME : PASS;
        }
        if (e.time() != e.down()) { reject("down-time"); invalid = "down-time"; }
        boolean first = held == 0;
        sources[index] = e.source(); downs[index] = e.down(); held |= bit;
        if (first) {
            user = e.user(); firstAt = e.time(); lastTime = Math.max(lastTime, e.time());
            blocked = invalid != null; chord = false;
            return PASS;
        }
        lastTime = Math.max(lastTime, e.time());
        if (blocked || chord || invalid != null) { reject("blocked-sequence"); return PASS; }
        if (e.time() - firstAt > SLOP_MS) { reject("not-simultaneous"); return PASS; }
        chord = true; owned = held;
        legacyGeneration++; // cancel only legacy work belonging to this chord
        return CONSUME | CLAIM;
    }
}
