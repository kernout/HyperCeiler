/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecList;
import android.util.Range;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Executes the real production select method against bounded, fault-injected API fixtures. */
public final class VideoCameraConfigSelectTest {
    private static final String ROLES = "com.xiaomi.cameraid.role.cameraIds";
    private static final String ROLE = "com.xiaomi.cameraid.role.cameraId";
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static CameraCharacteristics camera(boolean back, boolean logical, int role) {
        CameraCharacteristics c = new CameraCharacteristics();
        c.values.put("facing", back ? 1 : 0);
        c.values.put("capabilities", logical ? new int[]{11} : new int[0]);
        c.values.put(ROLES, new int[]{role});
        c.values.put(ROLE, role);
        c.values.put("orientation", 90);
        c.values.put("streams", new StreamConfigurationMap());
        c.values.put("fps", new Range<?>[]{new Range<>(60, 60)});
        return c;
    }
    private static CameraManager physical() {
        MediaCodecList.supported = true; MediaCodecList.failure = null;
        CameraManager m = new CameraManager();
        CameraCharacteristics parent = camera(true, true, 64);
        parent.physical.addAll(List.of("3", "2", "4"));
        m.metadata.put("0", parent);
        m.metadata.put("1", camera(false, false, 1));
        m.metadata.put("2", camera(true, false, 0));
        m.metadata.put("3", camera(true, false, 21));
        CameraCharacteristics tele = camera(true, false, 23);
        tele.values.put(ROLES, new int[]{23, 24});
        m.metadata.put("4", tele);
        return m;
    }
    private static CameraManager direct() {
        CameraManager m = physical(); m.ids = new String[]{"0", "1", "2"}; return m;
    }
    private static CameraManager appUidVisible() {
        CameraManager m = physical();
        m.ids = new String[]{"0", "1", "2", "3", "4", "5"};
        CameraCharacteristics parent = camera(true, true, 60);
        parent.values.put(ROLES, new int[]{60, 62, 61, 63});
        parent.physical.addAll(List.of("2", "3", "4"));
        m.metadata.put("5", parent);
        return m;
    }
    /** Test-only old role-discovery algorithm, not a second production selector. */
    private static VideoMainCameraPolicy.Route legacyEnumeratedRoleRoute(CameraManager m) {
        java.util.ArrayList<VideoMainCameraPolicy.Camera> descriptors = new java.util.ArrayList<>();
        for (String id : m.getCameraIdList()) {
            CameraCharacteristics c = m.getCameraCharacteristics(id);
            Integer role = null; int[] roles = null;
            for (CameraCharacteristics.Key<?> key : c.getKeys()) {
                if (!key.getName().equals(ROLE) && !key.getName().equals(ROLES)) continue;
                Object value = c.get(key);
                if (value instanceof Integer scalar) role = scalar;
                if (value instanceof int[] array) roles = array;
            }
            int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean logical = capabilities != null && java.util.Arrays.stream(capabilities).anyMatch(v -> v == 11);
            descriptors.add(new VideoMainCameraPolicy.Camera(id,
                    Integer.valueOf(1).equals(c.get(CameraCharacteristics.LENS_FACING)),
                    logical, role, roles, c.getPhysicalCameraIds()));
        }
        return VideoMainCameraPolicy.select(descriptors,
                new java.util.HashSet<>(java.util.Arrays.asList(m.getCameraIdList())));
    }
    private static void filteredEnumerationRegression() throws Exception {
        for (List<CameraCharacteristics.Key<?>> keys : List.of(
                List.<CameraCharacteristics.Key<?>>of(),
                List.<CameraCharacteristics.Key<?>>of(CameraCharacteristics.LENS_FACING,
                        CameraCharacteristics.SENSOR_ORIENTATION, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))) {
            CameraCharacteristics.enumeratedKeys = keys;
            CameraManager m = appUidVisible();
            check(m.getCameraIdList().length == 6, "App numerical UID fixture exposes 0..5");
            check(java.util.Arrays.equals(m.metadata.get("2").get(
                    new CameraCharacteristics.Key<>(ROLES, int[].class)), new int[]{0}),
                    "Typed MAIN array exists despite filtered enumeration");
            try { legacyEnumeratedRoleRoute(m); throw new AssertionError("Old enumeration unexpectedly found MAIN"); }
            catch (VideoMainCameraPolicy.SelectionException expected) {
                check(expected.reason == VideoMainCameraPolicy.SelectionFailure.NO_MAIN,
                        "Old algorithm fails NO_MAIN for empty/standard-only enumeration");
            }
            check(CameraCharacteristics.getKeysCalls > 0, "Old regression actually enumerated metadata");
            CameraCharacteristics.getKeysCalls = 0;
            VideoCameraConfig selected = VideoCameraConfig.select(m);
            check(selected.id.equals("2") && selected.physicalId == null,
                    "Real production select prefers direct MAIN 2 over logical carriers 0/5");
            check(selected.sensorOrientation == 90, "Direct MAIN orientation preserved");
            check(selected.characteristics == m.metadata.get("2")
                            && selected.logicalCharacteristics == selected.characteristics,
                    "Direct MAIN is both target and carrier; no ambiguous logical carrier chosen");
            check(CameraCharacteristics.getKeysCalls == 0, "New selector never calls filtered getKeys");
            check(!m.metadata.get("0").reads.contains("streams:StreamConfigurationMap")
                            && !m.metadata.get("5").reads.contains("streams:StreamConfigurationMap"),
                    "Direct route does not validate unrelated logical-carrier streams");
        }
        CameraCharacteristics.enumeratedKeys = null;
    }
    private static void route(CameraManager m, String open, String target, String label) throws Exception {
        VideoCameraConfig c = VideoCameraConfig.select(m);
        check(c.id.equals(open) && java.util.Objects.equals(c.physicalId, target), label);
    }
    private static void rejects(CameraManager m, String label) throws Exception {
        try { VideoCameraConfig.select(m); throw new AssertionError("Unexpected acceptance: " + label); }
        catch (IllegalStateException expected) { ++checks; }
    }
    private static String diagnosed(CameraManager m) throws Exception {
        Path root = Files.createTempDirectory("hc-config-diagnostics-");
        try {
            try { VideoCameraConfig.select(m, new VideoDiagnostics(root.toFile())); }
            catch (Exception | LinkageError expected) { /* Check only bounded diagnostic below. */ }
            return Files.readString(root.resolve("video-diagnostics/current.log"));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        CameraManager m = physical();
        // Demonstrate that this fixture reproduces the actual ROM path, not just policy.
        try { m.metadata.get("0").getKeys(); throw new AssertionError("Missing ROM fault"); }
        catch (NullPointerException e) {
            check(e.getStackTrace()[0].getMethodName().equals("getExtendedSceneModeCapabilities"), "Actual ROM derived-key failure shape");
        }
        CameraCharacteristics.getKeysCalls = 0;
        route(m, "0", "2", "ROM physical MAIN route survives getKeys NPE");
        for (Throwable failure : new Throwable[]{new SecurityException("private UID"),
                new IllegalArgumentException("ROM"), new NoSuchFieldError("ROM")}) {
            CameraCharacteristics.getKeysFailure = failure;
            route(physical(), "0", "2", "Different UID getKeys throwable must never matter");
        }
        CameraCharacteristics.getKeysFailure = null;
        filteredEnumerationRegression();
        check(CameraCharacteristics.getKeysCalls == 0, "Production never enumerates characteristics keys");
        check(!m.metadata.get("2").reads.contains(ROLE + ":Integer"), "Array authoritative: no scalar read");
        m = physical(); m.metadata.get("2").values.put(ROLES, new int[]{21});
        rejects(m, "array excludes MAIN, scalar zero cannot override");
        m = physical(); m.metadata.get("2").values.put(ROLES, new int[0]);
        rejects(m, "empty array authoritative");
        m = physical(); m.metadata.get("2").values.put(ROLES, new NullPointerException("broken array"));
        rejects(m, "broken array cannot silently fallback scalar");
        for (Object scalar : new Object[]{Integer.valueOf(0), new int[]{0}}) {
            m = physical(); m.metadata.get("2").values.remove(ROLES); m.metadata.get("2").values.put(ROLE, scalar);
            route(m, "0", "2", "Scalar Integer/int[1] compatibility");
        }
        m = physical(); m.metadata.get("2").values.remove(ROLES);
        m.metadata.get("2").values.put(ROLE, new int[]{0});
        m.metadata.get("2").scalarTypeFailure = new IllegalArgumentException("wrong marshaler shape");
        route(m, "0", "2", "Scalar marshaler type mismatch int[] retry");
        m = physical(); m.metadata.get("2").values.put(ROLES, new IllegalArgumentException("unregistered tag"));
        route(m, "0", "2", "Missing vendor array can use valid scalar");
        m = physical(); m.metadata.get("2").values.remove(ROLES);
        m.metadata.get("2").values.put(ROLE, new int[]{0, 21});
        rejects(m, "scalar cardinality must equal one");
        m = physical(); m.metadata.get("2").values.remove(ROLES); m.metadata.get("2").values.remove(ROLE);
        rejects(m, "no roles: never guess by id/focal length");
        m = direct(); m.failures.put("3", new SecurityException("hidden metadata denied"));
        m.metadata.get("0").physicalFailure = new NullPointerException("broken optional physical IDs");
        route(m, "2", null, "Direct verified MAIN survives unrelated logical metadata fault");
        check(!m.queried.contains("3") && !m.queried.contains("4"), "No hidden queries for direct MAIN");
        for (Consumer<CameraManager> fault : List.<Consumer<CameraManager>>of(
                x -> x.failures.put("1", new SecurityException("front denied")),
                x -> x.failures.put("3", new IllegalStateException("tele unavailable")),
                x -> x.metadata.get("3").values.put(ROLES, new NullPointerException("unrelated roles")),
                x -> x.metadata.get("3").values.put("streams", new NullPointerException("unrelated streams")))) {
            m = physical(); fault.accept(m); route(m, "0", "2", "Unrelated optional camera failure isolated");
        }
        m = physical(); m.metadata.get("1").values.put(ROLES, new NullPointerException("front optional vendor metadata"));
        route(m, "0", "2", "Front roles must not be consulted");
        check(!m.metadata.get("1").reads.contains(ROLES + ":int[]"), "Front vendor key not read");
        m = direct(); m.ids = new String[]{"0", "1", "2", "3"};
        m.metadata.get("3").values.put(ROLES, new int[]{0});
        rejects(m, "two directly public MAIN arrays reject ambiguity");
        m = physical(); m.failures.put("2", new SecurityException("physical metadata inaccessible to UID"));
        String log = diagnosed(m);
        check(log.contains("CAMERA_METADATA_SKIPPED SECURITY") && log.contains("CAMERA_ROUTE_FAILED MAIN_UNAVAILABLE"), "Hidden metadata denial never guesses physical MAIN");
        m = physical(); m.metadata.get("3").values.put(ROLES, new int[]{0});
        rejects(m, "two physical MAIN arrays reject ambiguity");
        check(diagnosed(m).contains("CAMERA_ROUTE_FAILED MAIN_AMBIGUOUS"), "Fixed ambiguous MAIN code");
        m = physical(); m.ids = new String[]{"0", "1", "5"};
        CameraCharacteristics parent2 = camera(true, true, 64); parent2.physical.add("2"); m.metadata.put("5", parent2);
        check(diagnosed(m).contains("CAMERA_ROUTE_FAILED CARRIER_AMBIGUOUS"), "Fixed ambiguous carrier code");
        m = physical(); m.metadata.get("2").values.put("fps", new Range<?>[]{new Range<>(30, 60)});
        rejects(m, "30-60 range cannot stand in for 60-60");
        check(diagnosed(m).contains("CAMERA_STREAM_FAILED STREAM_UNSUPPORTED"), "Fixed unsupported stream code");
        m = physical(); ((StreamConfigurationMap)m.metadata.get("0").values.get("streams")).duration=33333333;
        rejects(m, "carrier must also support 60fps");
        m = physical(); ((StreamConfigurationMap)m.metadata.get("2").values.get("streams")).duration=0;
        rejects(m, "unknown duration cannot pass");
        m = physical(); ((StreamConfigurationMap)m.metadata.get("2").values.get("streams")).size=false;
        rejects(m, "high speed only/missing regular 1080 rejects");
        m = physical(); m.metadata.get("2").values.put("streams", new NullPointerException("private metadata details"));
        log=diagnosed(m); check(log.contains("CAMERA_STREAM NULL_METADATA") && !log.contains("private"), "Stage-specific NPE classification without exception data");
        m = physical(); m.metadata.get("2").resultsMissing=true;
        check(diagnosed(m).contains("CAMERA_SELECTED_METADATA STATE"), "Selected result metadata failure is not optional");
        m = physical(); m.metadata.get("2").values.put("orientation", 7);
        rejects(m, "bad selected orientation rejects");
        m = physical(); MediaCodecList.supported=false;
        check(diagnosed(m).contains("CAMERA_ENCODER_FAILED ENCODER_UNSUPPORTED"), "Strict encoder rejection diagnostic");
        m = physical(); MediaCodecList.failure=new NullPointerException("private encoder details");
        check(diagnosed(m).contains("CAMERA_ENCODER NULL_METADATA"), "Encoder metadata failure stage");
        m = physical(); m.idsFailure=new SecurityException("camera ids private");
        check(diagnosed(m).contains("CAMERA_IDS SECURITY"), "Camera ID enumeration failure stage");
        log=diagnosed(physical());
        for(String stage : new String[]{"CAMERA_IDS", "CAMERA_ROLES", "CAMERA_PHYSICAL_IDS", "CAMERA_ROUTE", "CAMERA_SELECTED_METADATA", "CAMERA_STREAM", "CAMERA_ENCODER", "CAMERA_PREFLIGHT_READY"})
            check(log.contains(stage + " NONE"), "Successful preflight stage " + stage);
        for(String line:log.split("\n")) {
            String[] fields=line.split(" "); check(fields.length==3, "No arbitrary diagnostic payload");
            Long.parseLong(fields[0]); VideoDiagnostics.Event.valueOf(fields[1]); VideoDiagnostics.Failure.valueOf(fields[2]);
        }
        check(CameraCharacteristics.getKeysCalls == 0, "All production fixture paths avoid getKeys");
        System.out.println("PASS VideoCameraConfigSelectTest ("+checks+" checks, REAL select + fault-injected host metadata, no hardware)");
    }
}
