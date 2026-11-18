/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

import java.util.UUID;

public final class VideoRecordingPolicyTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        ++checks;
        if (!condition) throw new AssertionError(message);
    }
    private static boolean accept(String id, long at, long now, int from, int own, boolean front, boolean unlocked) {
        return VideoRecordingPolicy.accept(id, at, now, from, own, front, unlocked);
    }
    private static void admission() {
        check(VideoRecordingPolicy.validId(UUID.randomUUID().toString()), "UUID accepted");
        for (String bad : new String[] {null, "", "1-1-1-1-1", "not-a-uuid", "00000000-0000-0000-0000-00000000000x"})
            check(!VideoRecordingPolicy.validId(bad), "Malformed UUID rejected");
        check(VideoRecordingPolicy.fresh(0, 2500), "Inclusive freshness boundary");
        check(!VideoRecordingPolicy.fresh(0, 2501), "Expired request rejected");
        check(!VideoRecordingPolicy.fresh(1, 0), "Future request rejected");
        check(!VideoRecordingPolicy.fresh(-1, 0), "Missing elapsed timestamp rejected");
        String id = "abcdefab-cdef-4abc-8abc-abcdefabcdef";
        check(!accept(id, 1000, 1000, 10, 0, true, true), "Wrong user rejected");
        check(!accept(id, 1000, 1000, 0, 0, false, true), "Background user rejected");
        check(!accept(id, 1000, 1000, 0, 0, true, false), "CE locked storage rejected");
        check(!accept(id, 1000, 3501, 0, 0, true, true), "Stale rejected");
        check(accept(id, 1000, 1000, 0, 0, true, true), "Rejected requests don't consume ID");
        check(!accept(id.toUpperCase(java.util.Locale.ROOT), 1000, 1000, 0, 0, true, true), "UUID case cannot defeat dedup");
        new VideoRecordingPolicy();
        check(!accept(id, 1000, 1000, 0, 0, true, true), "Service recreation preserves receipts");
    }
    private static void lifecycle() {
        VideoRecordingPolicy policy = new VideoRecordingPolicy();
        check(policy.toggle(50) == VideoRecordingPolicy.Decision.START, "Idle toggle starts");
        policy.starting();
        check(policy.toggle(20_000) == VideoRecordingPolicy.Decision.IGNORE, "Preparing 20 seconds is not recording 10 seconds");
        policy.started(50_000);
        check(policy.startedAt() == 50_000, "Native start timestamp retained");
        check(policy.toggle(49_999) == VideoRecordingPolicy.Decision.IGNORE, "Pre-start gesture ignored");
        for (long at : new long[] {50_000, 50_001, 59_998, 59_999})
            check(policy.toggle(at) == VideoRecordingPolicy.Decision.IGNORE, "<10000ms never stops");
        check(policy.state() == VideoRecordingPolicy.State.RECORDING, "Ignored stops keep recording");
        // Advancing the clock is not an operation and cannot trigger a queued early stop.
        check(policy.state() == VideoRecordingPolicy.State.RECORDING, "No delayed stop state");
        check(policy.toggle(60_000) == VideoRecordingPolicy.Decision.STOP, "Exactly 10000ms stops");
        check(policy.stopping(), "First stop accepted");
        check(!policy.stopping(), "Repeated stop idempotent");
        check(policy.toggle(80_000) == VideoRecordingPolicy.Decision.IGNORE, "Stopping cannot restart");
        policy.finished();
        check(policy.startedAt() == -1 && policy.toggle(90_000) == VideoRecordingPolicy.Decision.START, "Completion resets state");
        policy.starting();
        check(policy.stopping(), "Fault can cancel preparation");
        policy.finished();
        policy.starting(); policy.started(100_000);
        check(policy.stopping(), "Privacy/revoke/destroy can stop before minimum");
        policy.finished();
        check(policy.state() == VideoRecordingPolicy.State.IDLE, "Failure cleanup returns idle");
        boolean threw = false;
        try { policy.started(1); } catch (IllegalStateException expected) { threw = true; }
        check(threw, "Late native-start callback cannot resurrect finished policy");
    }
    private static void deliveryAndDedupe() {
        VideoRecordingPolicy p = new VideoRecordingPolicy();
        p.starting(); p.started(10_000);
        String id = UUID.randomUUID().toString();
        check(accept(id, 19_999, 20_500, 0, 0, true, true), "Delayed but fresh press admitted");
        check(p.toggle(19_999) == VideoRecordingPolicy.Decision.IGNORE, "Delivery after minimum does not upgrade early gesture");
        check(!accept(id, 19_999, 21_000, 0, 0, true, true), "Early-stop retransmission ignored forever in receipt window");
        check(accept(UUID.randomUUID().toString(), 20_000, 20_001, 0, 0, true, true), "New >=10s press admitted");
        check(p.toggle(20_000) == VideoRecordingPolicy.Decision.STOP, "New exact-boundary press stops");
    }
    private static void capabilitiesAndOutput() {
        check(VideoRecordingPolicy.fpsEchoValid(false, null, null), "Absent unadvertised FPS result is legal (actual ROM)");
        check(!VideoRecordingPolicy.fpsEchoValid(true, null, null), "Advertised FPS echo cannot disappear");
        check(VideoRecordingPolicy.fpsEchoValid(false, 60, 60), "Unadvertised but correct echo accepted");
        check(VideoRecordingPolicy.fpsEchoValid(true, 60, 60), "Advertised exact echo accepted");
        check(!VideoRecordingPolicy.fpsEchoValid(false, 30, 30), "Contradictory optional echo rejected");
        check(!VideoRecordingPolicy.fpsEchoValid(true, 30, 60), "Variable advertised echo rejected");
        check(!VideoRecordingPolicy.fpsEchoValid(false, 60, null), "Malformed partial range rejected");
        check(VideoRecordingPolicy.normal60(true, true, 16_666_667), "Normal 60fps rounded nanoseconds allowed");
        check(!VideoRecordingPolicy.normal60(true, true, 16_666_668), "Slow normal stream rejected");
        check(!VideoRecordingPolicy.normal60(true, true, 33_333_333), "30fps stream rejected");
        check(!VideoRecordingPolicy.normal60(true, true, 0), "Unknown minimum duration rejected");
        check(!VideoRecordingPolicy.normal60(false, true, 1), "Wrong size rejected");
        check(!VideoRecordingPolicy.normal60(true, false, 1), "[30,60] is not [60,60]");
        check(VideoRecordingPolicy.measured60(601, 0, 10_000_000), "Actual 60fps sample timestamps accepted");
        check(VideoRecordingPolicy.measured60(600, 0, 10_000_000), "Encoder endpoint rounding allowed");
        check(!VideoRecordingPolicy.measured60(301, 0, 10_000_000), "False 60 tag with 30fps samples rejected");
        check(!VideoRecordingPolicy.measured60(1, 0, 0), "Empty/truncated sample sequence rejected");
        check(!VideoRecordingPolicy.measured60(60, 1000, 1000), "Non-increasing timestamps rejected");
        check(!VideoRecordingPolicy.measured60(121, 0, 1_000_000), "120fps is not 60fps");
    }
    public static void main(String[] args) {
        admission(); lifecycle(); deliveryAndDedupe(); capabilitiesAndOutput();
        System.out.println("PASS VideoRecordingPolicyTest (" + checks + " checks)");
    }
}
