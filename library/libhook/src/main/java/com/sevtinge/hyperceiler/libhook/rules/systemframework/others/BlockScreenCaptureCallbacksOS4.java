/*
 * This file is part of HyperCeiler.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.others;

import android.os.Build;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Suppresses only the final app screenshot-detection observer broadcast. */
public final class BlockScreenCaptureCallbacksOS4 extends BaseHook {
    public static final String PREF = "system_framework_block_screen_capture_callbacks";

    @Override
    public void init() {
        if (!ScreenCaptureCallbackPolicy.shouldInstall(Build.VERSION.SDK_INT,
                PrefsBridge.getBoolean(PREF, false))) return;

        try {
            Class<?> activity = findClass("com.android.server.wm.ActivityRecord");
            Method report = activity.getDeclaredMethod("reportScreenCaptured");
            if (!ScreenCaptureCallbackPolicy.isObserverDispatch(report)) {
                throw new NoSuchMethodException("ActivityRecord.reportScreenCaptured must be instance void()");
            }

            // SDK 37 / myron OS4.0.4.0.XPMCNXM: these callers can inline the dispatch.
            // Validate the exact ROM call sites before installing the only hook below.
            Class<?> wms = findClass("com.android.server.wm.WindowManagerService");
            Class<?> set = findClass("android.util.ArraySet");
            Method notify = wms.getDeclaredMethod("notifyScreenshotListeners", int.class);
            Method dispatch = wms.getDeclaredMethod("lambda$notifyScreenshotListeners$40", set, activity);
            Class<?> consumer = findClass("com.android.server.wm.WindowManagerService$$ExternalSyntheticLambda44");
            Method accept = consumer.getDeclaredMethod("accept", Object.class);
            if (notify.getReturnType() != java.util.List.class
                    || Modifier.isStatic(notify.getModifiers())
                    || dispatch.getReturnType() != void.class
                    || !Modifier.isStatic(dispatch.getModifiers())
                    || accept.getReturnType() != void.class
                    || Modifier.isStatic(accept.getModifiers())
                    || !java.util.function.Consumer.class.isAssignableFrom(consumer)) {
                throw new NoSuchMethodException("Unsupported screenshot notification callers");
            }
            deoptimizeMethods(wms, "lambda$notifyScreenshotListeners$40", "notifyScreenshotListeners");
            deoptimizeMethods(consumer, "accept");

            if (hookMethod(report, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    // WMS permission/display/visibility/registration checks already ran.
                    // Skip BEFORE beginBroadcast: no list copy, clearing or finishBroadcast
                    // is needed. Registration, binder death and unregister remain untouched.
                    // The WMS caller still adds its ComponentName to notifiedApps; preserve
                    // the system's returned list and any notification UI based on that list.
                    // No package/user filter: every selected ActivityRecord is covered.
                    param.setResult(null);
                }
            }) == null) {
                throw new IllegalStateException("ActivityRecord.reportScreenCaptured hook not installed");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported HyperOS 4 screenshot callback API", e);
        }
    }
}
