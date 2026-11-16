/* Copyright (C) 2026 HyperCeiler Contributions */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Pure input state. All entry points are serialized with dispatch reservation. */
public final class ScreenOffRecorderState {
    public static final int PASS = 0, CONSUME = 1, TRIGGER = 2;
    public record Source(int device, String descriptor, int source, int scan) {}
    public record Event(int key, int action, int repeat, long down, long time,
                        Source source, boolean trusted) {}
    private final long multiPress, longPress;
    private final Map<String, Long> pressed = new HashMap<>();
    private int phase;
    private Source source, owned;
    private long firstDown, down, lastTime, ownedDown, generation, gateEpoch, cancellation;

    public ScreenOffRecorderState(long multiPress, long longPress) {
        if (multiPress <= 0 || longPress <= 0) throw new IllegalArgumentException();
        this.multiPress = multiPress;
        this.longPress = longPress;
    }
    public synchronized long generation() { return generation; }
    public synchronized long cancellation() { return cancellation; }
    public synchronized boolean isCandidate() { return phase != 0; }
    public synchronized long candidateDeadline() {
        return phase == 1 || phase == 3 ? down + longPress : firstDown + multiPress;
    }
    /** Invalidating recognition deliberately retains ownership until its matching UP. */
    public synchronized void cancel() { phase = 0; generation++; cancellation++; }
    public synchronized void deviceChanged(int id) {
        cancel();
        pressed.keySet().removeIf(k -> k.startsWith(id + ":"));
        if (owned != null && owned.device() == id) owned = null;
    }
    public synchronized int accept(Event e, boolean allowed, long gateGeneration) {
        String key = e.source().device() + ":" + e.key();
        boolean wasPressed = pressed.containsKey(key);
        if (e.action() == 0) pressed.put(key, e.down());
        else if (e.action() == 1) pressed.remove(key);
        if (e.key() != 24) {
            if (e.action() == 0) cancel();
            return PASS;
        }
        boolean pair = owned != null && owned.equals(e.source()) && ownedDown == e.down();
        if (e.action() == 1 && owned != null && owned.equals(e.source())) owned = null;
        if (e.action() == 0 && owned != null && !pair) {
            // Never transfer a swallowed DOWN to a new source/sequence.
            if (owned.equals(e.source())) owned = null;
            cancel();
            return PASS;
        }
        if (e.action() == 1 && !pair && phase == 0) return PASS;
        boolean valid = allowed && e.trusted() && e.repeat() == 0
            && e.down() >= 0 && e.time() >= e.down() && e.time() >= lastTime
            && (e.action() == 0 || e.action() == 1);
        if (!valid) {
            cancel();
            return pair ? CONSUME : PASS;
        }
        lastTime = e.time();
        if (e.action() == 0) {
            if (wasPressed || pressed.size() != 1 || e.time() != e.down()) {
                cancel();
                return pair ? CONSUME : PASS;
            }
            boolean second = phase == 2 && Objects.equals(source, e.source())
                && gateEpoch == gateGeneration && e.down() > down
                && e.down() - firstDown < multiPress;
            // A different source cannot finish or restart a pending double press.
            if (phase != 0 && !Objects.equals(source, e.source())) {
                cancel(); return PASS;
            }
            if (!second) { phase = 1; firstDown = e.down(); gateEpoch = gateGeneration; generation++; }
            else phase = 3;
            source = e.source(); down = e.down();
            owned = source; ownedDown = down;
            return CONSUME;
        }
        if (!pair || !Objects.equals(source, e.source()) || e.down() != down
            || e.time() - down >= longPress || gateEpoch != gateGeneration
            || !pressed.isEmpty()) {
            cancel(); return pair ? CONSUME : PASS;
        }
        if (phase == 1) { phase = 2; return CONSUME; }
        if (phase == 3) { phase = 0; return CONSUME | TRIGGER; }
        cancel(); return CONSUME;
    }
}
