/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps a replacement worker out until BOTH native/file cleanup and Camera2 close complete. */
final class VideoResourceGate {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private final AtomicBoolean owned = new AtomicBoolean();
    private final AtomicBoolean cameraClosed = new AtomicBoolean(true);
    private final AtomicBoolean cleanupDone = new AtomicBoolean();
    boolean acquire() {
        if (!BUSY.compareAndSet(false, true)) return false;
        owned.set(true);
        return true;
    }
    void openingCamera() { cameraClosed.set(false); }
    void cameraClosed() { cameraClosed.set(true); releaseIfComplete(); }
    void cleanedUp() { cleanupDone.set(true); releaseIfComplete(); }
    private void releaseIfComplete() {
        if (cameraClosed.get() && cleanupDone.get() && owned.compareAndSet(true, false)) BUSY.set(false);
    }
}
