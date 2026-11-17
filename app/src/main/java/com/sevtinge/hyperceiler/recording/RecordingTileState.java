/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.recording;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Process-local UI state; only the recorder service publishes actual lifecycle transitions. */
final class RecordingTileState {
    enum State { IDLE, STARTING, RECORDING, STOPPING, ERROR }
    private static volatile State state = State.IDLE;
    private static final Set<Runnable> listeners = new CopyOnWriteArraySet<>();

    private RecordingTileState() {}
    static State get() { return state; }
    static void listen(Runnable listener) { listeners.add(listener); }
    static void unlisten(Runnable listener) { listeners.remove(listener); }
    static void publish(State next) {
        state = next;
        for (Runnable listener : listeners) listener.run();
    }
}
