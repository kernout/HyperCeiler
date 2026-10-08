/*
 * This file is part of HyperCeiler.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.display;

final class ScreenshotWindowPolicy {
    private ScreenshotWindowPolicy() {}

    static boolean overlay(int type, boolean enabled) {
        // Match CustoMIUIzer's SYSTEM_OVERLAY and APPLICATION_OVERLAY only.
        return enabled && (type == 2006 || type == 2038);
    }

    static boolean freeform(int windowingMode, boolean enabled) {
        // Do not exclude fullscreen, split-screen or picture-in-picture tasks.
        return enabled && windowingMode == 5;
    }
}
