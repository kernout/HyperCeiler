package com.sevtinge.hyperceiler.camera;

public final class ScreenOffServiceAdmissionTest {
    private static int checks;
    private static void check(boolean expected, boolean checked, long at, long now) {
        checks++;
        if (ScreenOffCaptureService.isFreshPrivacyCheck(checked, at, now) != expected)
            throw new AssertionError("admission case " + checks);
    }
    public static void main(String[] args) {
        check(false, false, 1000, 1000);
        check(false, true, -1, 1000);
        check(false, true, 1001, 1000);
        check(true, true, 1000, 1000);
        check(true, true, 1000, 3500);
        check(false, true, 1000, 3501);
        check(true, true, 0, 0);
        check(false, true, Long.MAX_VALUE, 1000);
        check(false, true, 0, Long.MAX_VALUE);
        System.out.println("PASS: " + checks + " production service admission freshness checks");
    }
}
