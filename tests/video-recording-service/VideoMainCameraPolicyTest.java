/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public final class VideoMainCameraPolicyTest {
    private static int checks;
    private static void check(boolean value, String label) { ++checks; if (!value) throw new AssertionError(label); }
    private static Set<String> ids(String value) {
        return value.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(value.split(",")));
    }
    private static int[] ints(String value) {
        return value.isEmpty() ? new int[0] : Arrays.stream(value.split(",")).mapToInt(Integer::parseInt).toArray();
    }
    private static VideoMainCameraPolicy.Camera camera(String id, boolean logical, Integer role, int[] roles, String... physical) {
        return new VideoMainCameraPolicy.Camera(id, true, logical, role, roles, Set.of(physical));
    }
    private static void rejected(List<VideoMainCameraPolicy.Camera> cameras, Set<String> publicIds, String message) {
        boolean rejected = false;
        try { VideoMainCameraPolicy.select(cameras, publicIds); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, message);
    }
    public static void main(String[] args) throws Exception {
        Properties fixture = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) { fixture.load(input); }
        List<VideoMainCameraPolicy.Camera> actual = new ArrayList<>();
        for (String id : fixture.getProperty("cameraIds").split(",")) {
            actual.add(new VideoMainCameraPolicy.Camera(id, Boolean.parseBoolean(fixture.getProperty(id + ".back")),
                    Boolean.parseBoolean(fixture.getProperty(id + ".logical")),
                    Integer.valueOf(fixture.getProperty(id + ".role")), ints(fixture.getProperty(id + ".roles")),
                    ids(fixture.getProperty(id + ".physicalIds"))));
        }
        Set<String> publicIds = ids(fixture.getProperty("publicIds"));
        VideoMainCameraPolicy.Route route = VideoMainCameraPolicy.select(actual, publicIds);
        check(route.openId.equals("0"), "Actual metadata opens public logical 0");
        check(route.physicalId.equals("2"), "Actual role=0 MAIN is physical 2");
        check(!publicIds.contains(route.physicalId), "Hidden physical target is not directly opened");
        for (String id : List.of(route.openId, route.targetId())) {
            int[] ranges = ints(fixture.getProperty(id + ".fpsRanges"));
            boolean exact = false;
            for (int i = 0; i < ranges.length; i += 2) exact |= ranges[i] == 60 && ranges[i + 1] == 60;
            check(VideoRecordingPolicy.normal60(Boolean.parseBoolean(fixture.getProperty(id + ".private1080")),
                    exact, Long.parseLong(fixture.getProperty(id + ".minDurationNs"))), "Fixture target/carrier normal 1080p60");
        }
        route = VideoMainCameraPolicy.select(actual, Set.of("0", "1", "2"));
        check(route.openId.equals("2") && route.physicalId == null, "Public direct MAIN preferred");
        var parent = camera("L", true, 64, new int[] {64}, "P");
        var main = camera("P", false, 0, new int[] {0});
        route = VideoMainCameraPolicy.select(List.of(parent, main), Set.of("L"));
        check(route.physicalId.equals("P"), "Carrier need not have MAIN role itself");
        rejected(List.of(parent, camera("P", false, 0, new int[] {21})), Set.of("L"), "Authoritative non-main array beats scalar 0");
        rejected(List.of(parent, camera("P", false, null, null)), Set.of("L"), "No focal-length or unique-camera guess");
        rejected(List.of(camera("L", true, 0, new int[] {0}, "P"), camera("P", false, 21, new int[] {21})), Set.of("L"), "Logical MAIN role cannot replace a pinned physical MAIN");
        rejected(List.of(parent, main, camera("L2", true, 60, new int[] {60}, "P")), Set.of("L", "L2"), "Ambiguous carriers rejected");
        rejected(List.of(camera("L", true, 64, new int[] {64}, "P", "Q"), main, camera("Q", false, 0, new int[] {0})), Set.of("L"), "Ambiguous physical MAIN rejected");
        var scalar = camera("S", false, 0, null);
        route = VideoMainCameraPolicy.select(List.of(main, camera("Q", false, 0, new int[] {0}), scalar), Set.of("P", "Q", "S"));
        check(route.openId.equals("S"), "Baseline scalar rank 2 beats role-array rank 1 independent of ordering");
        check(VideoMainCameraPolicy.physicalResultMatches("2", Set.of("2")), "Pinned physical result accepted");
        check(!VideoMainCameraPolicy.physicalResultMatches("2", Set.of("3")), "Other lens result cannot validate MAIN");
        check(!VideoMainCameraPolicy.physicalResultMatches("2", Set.of()), "Logical-only result cannot validate physical target");
        check(VideoMainCameraPolicy.physicalResultMatches(null, Set.of()), "Direct camera does not require physical map");
        System.out.println("PASS VideoMainCameraPolicyTest (" + checks + " checks, static metadata fixture)");
    }
}
