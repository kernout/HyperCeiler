/* This file is part of HyperCeiler. Copyright (C) 2023-2026 HyperCeiler Contributions */
package com.sevtinge.hyperceiler.libhook.rules.media;

/** Exact, case-sensitive basename transforms. No clock, extension or uniqueness rewriting. */
public final class CaptureFileNames {
    private CaptureFileNames() {}

    public static String screenshot(String name) { return strip(name, "Screenshot_"); }

    public static String recording(String name) {
        if (name == null) return null;
        // Return after the first matching prefix, including an unsafe suffix fallback.
        // Do not recursively strip product words remaining inside the original basename.
        for (String prefix : new String[]{"Screenrecorder_", "Screenrecorder-", "screen-"}) {
            if (name.startsWith(prefix)) return strip(name, prefix);
        }
        return name;
    }

    private static String strip(String name, String prefix) {
        if (name == null || !name.startsWith(prefix)) return name;
        String suffix = name.substring(prefix.length());
        // Never turn a valid name into an empty, extension-only or path-like name.
        if (suffix.isEmpty() || suffix.charAt(0) == '.' || suffix.isBlank()
            || suffix.indexOf('/') >= 0 || suffix.indexOf('\\') >= 0
            || suffix.indexOf('\0') >= 0) return name;
        int dot = suffix.lastIndexOf('.');
        if (dot <= 0 || dot == suffix.length() - 1
            || suffix.substring(0, dot).isBlank() || suffix.substring(dot + 1).isBlank()) return name;
        return suffix;
    }

    /** Used only on a newly generated recorder path, never to rename an existing file. */
    public static String recordingPath(String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        return path.substring(0, slash + 1) + recording(path.substring(slash + 1));
    }
}
