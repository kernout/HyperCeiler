/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

public final class VideoResourceGateTest {
    private static int checks;
    private static void check(boolean value, String label) {
        ++checks; if (!value) throw new AssertionError(label);
    }
    private static void cannotAcquire(String label) {
        VideoResourceGate rejected = new VideoResourceGate();
        check(!rejected.acquire(), label);
        rejected.cleanedUp(); rejected.cameraClosed(); // non-owner cannot unlock another worker
        check(!new VideoResourceGate().acquire(), "Rejected worker cannot clear busy gate");
    }
    public static void main(String[] args) throws Exception {
        VideoResourceGate beforeOpen = new VideoResourceGate();
        check(beforeOpen.acquire(), "Initial acquire");
        cannotAcquire("Preflight serialized");
        beforeOpen.cleanedUp(); // prepare/permission failure before camera open
        VideoResourceGate cleanupFirst = new VideoResourceGate();
        check(cleanupFirst.acquire(), "Pre-open failure releases");
        cleanupFirst.openingCamera();
        cleanupFirst.cleanedUp();
        cannotAcquire("Cleanup completed but camera callback outstanding");
        cleanupFirst.cameraClosed();
        VideoResourceGate closeFirst = new VideoResourceGate();
        check(closeFirst.acquire(), "Late close releases gate");
        closeFirst.openingCamera();
        closeFirst.cameraClosed();
        cannotAcquire("Camera closed while recorder.stop is still blocked");
        closeFirst.cleanedUp();
        VideoResourceGate lateOpened = new VideoResourceGate();
        check(lateOpened.acquire(), "Native cleanup after close releases gate");
        lateOpened.openingCamera();
        lateOpened.cleanedUp(); // destroy while openCamera callback is pending
        cannotAcquire("Destroy does not release pending open");
        // Models late onOpened => close => onClosed; callback may execute on another executor.
        Thread callback = new Thread(lateOpened::cameraClosed);
        callback.start(); callback.join();
        VideoResourceGate replacement = new VideoResourceGate();
        check(replacement.acquire(), "Late callback safely releases");
        lateOpened.cameraClosed(); lateOpened.cleanedUp(); // duplicate callback must not unlock replacement
        cannotAcquire("Duplicate old completion cannot unlock new worker");
        replacement.cleanedUp();
        System.out.println("PASS VideoResourceGateTest (" + checks + " checks)");
    }
}
