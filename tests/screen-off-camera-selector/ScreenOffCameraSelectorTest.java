package com.sevtinge.hyperceiler.camera;

/** Pure checks against the production selector; no Android stub methods are executed. */
public final class ScreenOffCameraSelectorTest {
    private static int checks;

    private static void eq(int expected, int actual, String reason) {
        checks++;
        if (expected != actual) throw new AssertionError(reason + ": expected=" + expected + ", actual=" + actual);
    }

    public static void main(String[] args) {
        eq(2, ScreenOffCameraSelector.mainRoleRank(0, null), "scalar main role fallback");
        eq(0, ScreenOffCameraSelector.mainRoleRank(21, null), "scalar ultrawide rejected");
        eq(0, ScreenOffCameraSelector.mainRoleRank(null, null), "absent tags do not verify main");
        eq(1, ScreenOffCameraSelector.mainRoleRank(null, new int[]{60, 0}), "array main role");
        eq(1, ScreenOffCameraSelector.mainRoleRank(60, new int[]{60, 0}), "array overrides scalar logical role");
        eq(0, ScreenOffCameraSelector.mainRoleRank(0, new int[]{21}), "non-main array overrides default scalar zero");
        eq(0, ScreenOffCameraSelector.mainRoleRank(0, new int[]{}), "empty array does not assert main");
        eq(0, ScreenOffCameraSelector.mainRoleRank(null, new int[]{-1, 20}), "non-main array rejected");
        eq(90, ScreenOffCameraSelector.jpegOrientation(90, -1), "unknown orientation uses natural portrait");
        eq(90, ScreenOffCameraSelector.jpegOrientation(90, 0), "portrait");
        eq(180, ScreenOffCameraSelector.jpegOrientation(90, 90), "one landscape direction");
        eq(270, ScreenOffCameraSelector.jpegOrientation(90, 180), "upside down");
        eq(0, ScreenOffCameraSelector.jpegOrientation(90, 270), "other landscape direction");
        eq(90, ScreenOffCameraSelector.jpegOrientation(90, 44), "round below threshold");
        eq(180, ScreenOffCameraSelector.jpegOrientation(90, 45), "round at threshold");
        eq(90, ScreenOffCameraSelector.jpegOrientation(90, 359), "wrap rounded orientation");
        eq(0, ScreenOffCameraSelector.jpegOrientation(270, 90), "other sensor mount");
        System.out.println("PASS: " + checks + " production selector/orientation checks");
    }
}
