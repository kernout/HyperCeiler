/* Copyright (C) 2026 HyperCeiler Contributions */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

import java.util.Objects;

/** Pure, serialized physical-key recognizer. First DOWN remains legacy/OEM owned;
 * only an overlapping second DOWN transfers both releases to this recognizer. */
public final class VolumeVideoChordState {
    public static final int PASS = 0, CONSUME = 1, CLAIM = 2, TRIGGER = 4;
    public static final long SLOP_MS = 120, DOUBLE_MS = 350, LONG_MS = 500;
    public record Source(int device, String descriptor, int source) {}
    public record Event(int key, int action, int repeat, long down, long time,
                        Source source, boolean physical, boolean clean, int user) {}
    private Source source;
    private final long[] downs = new long[2];
    private int held, owned, completed, user = -1;
    private boolean blocked, chord;
    private long firstAt, lastRelease, lastTime = -1, cancellation, legacyGeneration;

    public long cancellation() { return cancellation; }
    public long legacyGeneration() { return legacyGeneration; }
    public boolean hasOwnedPress() { return owned != 0; }
    public boolean tentative() { return held != 0 && !blocked && !chord; }
    public long tentativeDeadline() { return firstAt + SLOP_MS; }
    /** Cancellation never relinquishes swallowed physical releases. */
    public void cancel() { completed = 0; blocked = held != 0; chord = false; cancellation++; legacyGeneration++; }
    /** Removed/reconfigured device cannot deliver a matching release. */
    public void deviceChanged(int id) {
        if (source != null && source.device() == id) {
            cancel(); held = owned = 0; source = null; blocked = false;
        }
    }
    public int accept(Event e, boolean allowed) {
        int index = e.key() == 24 ? 0 : e.key() == 25 ? 1 : -1;
        if (index < 0 || !e.physical()) {
            if (e.action() == 0) cancel();
            return PASS;
        }
        int bit = 1 << index;
        boolean same = Objects.equals(source, e.source());
        boolean pair = same && (held & bit) != 0 && downs[index] == e.down();
        boolean consume = pair && (owned & bit) != 0;
        boolean valid = allowed && e.clean() && e.user() >= 0 && e.repeat() == 0
            && e.down() >= 0 && e.time() >= e.down() && e.time() >= lastTime
            && (e.action() == 0 || e.action() == 1);
        if (held != 0 && (!same || user != e.user())) {
            cancel();
            // A user switch still drains releases belonging to the old physical press.
            valid = false;
        }
        if (e.action() == 1) {
            if (!pair) { cancel(); return PASS; }
            if (!valid || e.time() - firstAt >= LONG_MS) cancel();
            held &= ~bit; owned &= ~bit;
            if (held == 0) {
                boolean complete = chord && !blocked && valid;
                chord = false; blocked = false;
                if (complete) {
                    completed++; lastRelease = e.time();
                    if (completed == 2) { completed = 0; return CONSUME | TRIGGER; }
                } else completed = 0;
            }
            lastTime = Math.max(lastTime, e.time());
            return consume ? CONSUME : PASS;
        }
        if (e.action() != 0) { cancel(); return consume ? CONSUME : PASS; }
        if (!valid || pair || (held & bit) != 0 || e.time() != e.down()) {
            cancel();
            // Track a first rejected physical DOWN so its repeat cannot become a chord.
            if (held == 0) {
                source = e.source(); user = e.user(); held = bit; downs[index] = e.down();
                firstAt = e.time(); blocked = true;
            }
            return consume ? CONSUME : PASS;
        }
        if (held == 0) {
            if (completed != 0 && (!same || user != e.user()
                || e.time() <= lastRelease || e.time() - lastRelease > DOUBLE_MS)) completed = 0;
            source = e.source(); user = e.user(); held = bit; downs[index] = e.down();
            firstAt = lastTime = e.time(); blocked = false; chord = false;
            return PASS;
        }
        if (!same) return PASS;
        held |= bit; downs[index] = e.down(); lastTime = e.time();
        if (blocked || chord || e.time() - firstAt > SLOP_MS) {
            cancel(); return PASS;
        }
        chord = true; owned = held;
        // Invalidate legacy tasks posted by the first DOWN before they can dispatch.
        legacyGeneration++;
        return CONSUME | CLAIM;
    }
}
