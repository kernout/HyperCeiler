/*
  * This file is part of HyperCeiler.

  * HyperCeiler is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation, either version 3 of the
  * License.

  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.

  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <https://www.gnu.org/licenses/>.

  * Copyright (C) 2023-2026 HyperCeiler Contributions
*/
package com.sevtinge.hyperceiler.libhook.rules.systemui.statusbar;

import android.graphics.Rect;
import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** OS4 native screenshots; leaves live windows and secure-layer policy unchanged. */
final class NativeScreenshotStatusBar {
    private static final String TAG = "NativeScreenshotStatusBar";
    private static final String PACKAGE = "com.android.systemui";

    private NativeScreenshotStatusBar() {}

    static void install() {
        try {
            Class<?> capture = BaseHook.findClass("com.android.systemui.screenshot.ImageCaptureImpl");
            Class<?> builder = BaseHook.findClass("android.window.ScreenCaptureInternal$CaptureArgs$Builder");
            capture.getDeclaredMethod("captureDisplay", int.class, Rect.class);
            builder.getDeclaredMethod("build");
            Field mode = builder.getDeclaredField("mMiCaptureMode");
            Field names = builder.getDeclaredField("mExcludeOrIncludeLayerNames");
            mode.setAccessible(true);
            names.setAccessible(true);
            Method setMode = builder.getDeclaredMethod("setMiCaptureMode", int.class);
            Method setNames = builder.getDeclaredMethod("setExcludeOrIncludeLayerNames", String[].class);
            setMode.setAccessible(true);
            setNames.setAccessible(true);

            // This entry is synchronous on the inspected ROM. Scope the builder
            // hook to this call, not recording, task snapshots or other callers.
            ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
            var builderHook = BaseHook.findAndHookMethod(builder, "build", new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    if (depth.get() == 0) return;
                    try {
                        Object receiver = param.getThisObject();
                        int oldMode = mode.getInt(receiver);
                        // Mode 1 matches MIUIScreenshot CaptureStrategyX.
                        // Never reinterpret an inclusion request as exclusion.
                        if (oldMode != 0 && oldMode != 1) {
                            XposedLog.w(TAG, PACKAGE, "HOOK_STATE=UNSUPPORTED_CAPTURE_MODE mode=" + oldMode);
                            return;
                        }
                        String[] oldNames = (String[]) names.get(receiver);
                        LinkedHashSet<String> merged = new LinkedHashSet<>();
                        if (oldNames != null) merged.addAll(Arrays.asList(oldNames));
                        merged.add("StatusBar");
                        setNames.invoke(receiver, (Object) merged.toArray(new String[0]));
                        setMode.invoke(receiver, 1);
                        android.util.Log.i("HC-Screenshot", "LAYER_EXCLUSION path=aosp layer=StatusBar");
                        XposedLog.d(TAG, PACKAGE, "HOOK_STATE=LAYER_EXCLUSION path=aosp layer=StatusBar");
                    } catch (Throwable t) {
                        XposedLog.w(TAG, PACKAGE, "HOOK_STATE=LAYER_EXCLUSION_FAILED", t);
                    }
                }
            });
            if (builderHook == null) throw new IllegalStateException("CaptureArgs builder hook failed");
            try {
                var entryHook = BaseHook.findAndHookMethod(capture, "captureDisplay",
                    int.class, Rect.class, new IMethodHook() {
                        @Override
                        public void before(HookParam param) {
                            depth.set(depth.get() + 1);
                        }
                        @Override
                        public void after(HookParam param) {
                            int remaining = depth.get() - 1;
                            if (remaining <= 0) depth.remove();
                            else depth.set(remaining);
                        }
                    });
                if (entryHook == null) throw new IllegalStateException("ImageCaptureImpl hook failed");
            } catch (Throwable t) {
                builderHook.unhook();
                throw t;
            }
            android.util.Log.i("HC-Screenshot", "INSTALLED path=aosp capture=ImageCaptureImpl");
            XposedLog.d(TAG, PACKAGE, "HOOK_STATE=INSTALLED path=aosp capture=ImageCaptureImpl");
        } catch (Throwable t) {
            android.util.Log.e("HC-Screenshot", "INSTALL_FAILED path=aosp", t);
            XposedLog.w(TAG, PACKAGE, "HOOK_STATE=INSTALL_FAILED path=aosp", t);
        }
    }
}
