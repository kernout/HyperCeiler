/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VideoDiagnosticsTest {
    private static int checks;
    private static void check(boolean value, String label) { ++checks; if (!value) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("hc-diagnostics-test-");
        try {
            VideoDiagnostics log = new VideoDiagnostics(root.toFile());
            log.record(VideoDiagnostics.Event.INTENT_RECEIVED);
            log.record(VideoDiagnostics.Event.WORKER_FAILED,
                    new SecurityException("content://private/secret-media user=person device=123"));
            Path current = root.resolve("video-diagnostics/current.log");
            String data = Files.readString(current);
            check(data.contains("INTENT_RECEIVED NONE"), "Lifecycle stage persisted");
            check(data.contains("WORKER_FAILED SECURITY"), "Allowlisted failure category persisted");
            check(!data.contains("secret") && !data.contains("content:") && !data.contains("person"), "Exception data never persisted");
            for (int i = 0; i < 6000; ++i) log.record(VideoDiagnostics.Event.SESSION_CONFIGURED);
            Path previous = root.resolve("video-diagnostics/previous.log");
            check(Files.exists(previous), "Rotation occurred");
            check(Files.size(current) <= VideoDiagnostics.MAX_BYTES, "Current log bounded");
            check(Files.size(previous) <= VideoDiagnostics.MAX_BYTES, "Previous log bounded");
            try (var files = Files.list(current.getParent())) {
                check(files.count() == 2, "No unbounded archive files");
            }
            for (Path file : new Path[] {current, previous}) {
                for (String line : Files.readAllLines(file)) {
                    String[] fields = line.split(" ");
                    if (fields.length != 3) throw new AssertionError("Only clock, stage, failure category allowed");
                    Long.parseLong(fields[0]);
                    VideoDiagnostics.Event.valueOf(fields[1]);
                    VideoDiagnostics.Failure.valueOf(fields[2]);
                }
            }
            ++checks;
            // A failed diagnostic write must not escape into sensor cleanup/admission.
            Path blocked = root.resolve("blocked");
            Files.writeString(blocked, "not a directory");
            new VideoDiagnostics(blocked.toFile()).record(VideoDiagnostics.Event.CLEANUP_START);
            ++checks;
            System.out.println("PASS VideoDiagnosticsTest (" + checks + " checks, bounded private logs)");
        } finally {
            try (var files = Files.walk(root)) {
                for (File file : files.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).toList())
                    if (!file.delete()) throw new AssertionError("Test cleanup failed");
            }
        }
    }
}
