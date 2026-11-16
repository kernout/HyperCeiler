/*
 * This file is part of HyperCeiler.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.display;

import android.content.Context;
import android.os.Binder;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.util.Log;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Adds capture-only exclusions at HyperOS 4's WMS layer-capture builder. */
public class ScreenshotCaptureWindowExclusion extends BaseHook {
    private static final String TAG = "ScreenshotCaptureWindowExclusion";
    private static final String PACKAGE = "android";
    private final ThreadLocal<CaptureScope> scope = new ThreadLocal<>();
    private Field globalLock;
    private Field root;
    private Field context;
    private Field attrs;
    private Field ownerUid;
    private Field builderExcludeLayers;
    private Method getDisplay;
    private Method forAllWindows;
    private Method forAllLeafTasks;
    private Method getSurface;
    private Method getTask;
    private Method getWindowingMode;
    private Constructor<SurfaceControl> copySurface;
    private Method buildMethod;
    private boolean hideStatusBar;
    private boolean hideOverlays;
    private boolean hideFreeform;
    private boolean hideIsland;

    @Override
    public void init() {
        if (android.os.Build.VERSION.SDK_INT < 37) return;
        try {
            install();
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e("HC-Screenshot", "INSTALL_FAILED target=system_server", e);
            throw new IllegalStateException("Unsupported HyperOS 4 WMS screenshot API", e);
        }
    }

    private void install() throws ReflectiveOperationException {
        hideStatusBar = PrefsBridge.getBoolean("system_ui_status_bar_hide_icon");
        hideOverlays = PrefsBridge.getBoolean("system_ui_status_bar_hide_overlay");
        hideFreeform = PrefsBridge.getBoolean("system_ui_status_bar_hide_freeform");
        hideIsland = PrefsBridge.getBoolean("system_ui_status_bar_hide_island", false);
        if (!hideStatusBar && !hideOverlays && !hideFreeform && !hideIsland) return;

        Class<?> wms = findClass("com.android.server.wm.WindowManagerService");
        Class<?> rootContainer = findClass("com.android.server.wm.RootWindowContainer");
        Class<?> windowContainer = findClass("com.android.server.wm.WindowContainer");
        Class<?> windowState = findClass("com.android.server.wm.WindowState");
        Class<?> task = findClass("com.android.server.wm.Task");
        Class<?> capture = findClass("android.window.ScreenCaptureInternal$CaptureArgs");
        Class<?> listener = findClass("android.window.ScreenCaptureInternal$ScreenCaptureListener");
        Class<?> builder = findClass(
            "android.window.ScreenCaptureInternal$LayerCaptureArgs$Builder");

        globalLock = field(wms, "mGlobalLock");
        root = field(wms, "mRoot");
        context = field(wms, "mContext");
        attrs = field(windowState, "mAttrs");
        if (hideIsland) ownerUid = field(windowState, "mOwnerUid");
        builderExcludeLayers = field(builder, "mExcludeLayers");
        copySurface = SurfaceControl.class.getConstructor(SurfaceControl.class, String.class);
        getDisplay = method(rootContainer, "getDisplayContent", int.class);
        forAllWindows = method(windowContainer, "forAllWindows", Consumer.class, boolean.class);
        try {
            forAllLeafTasks = method(windowContainer, "forAllLeafTasks", Consumer.class, boolean.class);
        } catch (NoSuchMethodException ignored) {
            // Preserve the existing window path on unsupported ROMs.
            forAllLeafTasks = null;
        }
        getSurface = method(windowContainer, "getSurfaceControl");
        getTask = method(windowState, "getTask");
        getWindowingMode = method(task, "getWindowingMode");

        wms.getDeclaredMethod("captureDisplay", int.class, capture, listener);
        buildMethod = dataCaptureBuilderMethod(builder);

        var captureHook = findAndHookMethod(wms, "captureDisplay",
            int.class, capture, listener, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    CaptureScope previous = scope.get();
                    CaptureScope current = new CaptureScope();
                    current.previous = previous;
                    current.service = param.getThisObject();
                    current.displayId = (int) param.getArgs()[0];
                    current.allowed = isScreenshotCaller(current.service);
                    scope.set(current);
                }

                @Override
                public void after(HookParam param) {
                    CaptureScope current = scope.get();
                    if (current == null) return;
                    releaseCopies(current);
                    if (current.previous == null) scope.remove();
                    else scope.set(current.previous);
                }
            });
        if (captureHook == null) throw new IllegalStateException("captureDisplay hook failed");

        try {
            var buildHook = hookMethod(buildMethod, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    CaptureScope current = scope.get();
                    if (current == null || !current.allowed) return;
                    try {
                        appendExclusions(param.getThisObject(), current);
                    } catch (Throwable t) {
                        Log.e("HC-Screenshot", "EXCLUSION_FAILED", t);
                        XposedLog.w(TAG, PACKAGE, "HOOK_STATE=EXCLUSION_FAILED", t);
                    }
                }
            });
            if (buildHook == null) throw new IllegalStateException("LayerCaptureArgs build hook failed");
        } catch (Throwable t) {
            captureHook.unhook();
            throw t;
        }
        diagnostic("INSTALLED statusBar=" + hideStatusBar + " overlay=" + hideOverlays
            + " freeform=" + hideFreeform + " island=" + hideIsland
            + " target=system_server/LayerCaptureArgs.Builder");
    }

    private boolean isScreenshotCaller(Object service) {
        try {
            Context ctx = (Context) context.get(service);
            String[] packages = ctx.getPackageManager()
                .getPackagesForUid(Binder.getCallingUid());
            if (packages == null) {
                diagnostic("CALLER_SKIPPED uid=" + Binder.getCallingUid() + " packages=null");
                return false;
            }
            for (String name : packages) {
                if ("com.android.systemui".equals(name)
                        || "com.miui.screenshot".equals(name)) {
                    diagnostic("CAPTURE_ENTRY caller=" + name + " uid=" + Binder.getCallingUid());
                    return true;
                }
            }
        } catch (Throwable t) {
            XposedLog.w(TAG, PACKAGE, "HOOK_STATE=CALLER_CHECK_FAILED", t);
        }
        return false;
    }

    private void appendExclusions(Object builder, CaptureScope current) throws Exception {
        List<SurfaceControl> additions = new ArrayList<>();
        Set<SurfaceControl> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        Object lock = globalLock.get(current.service);
        synchronized (lock) {
            Object display = getDisplay.invoke(root.get(current.service), current.displayId);
            if (display == null) return;
            Consumer<Object> collect = window -> {
                try {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) attrs.get(window);
                    Object target = null;
                    String kind = null;
                    if (ScreenshotWindowPolicy.statusBar(lp.type, hideStatusBar)) {
                        target = window;
                        kind = "statusbar(type=" + lp.type + ")";
                    } else if (ScreenshotWindowPolicy.overlay(lp.type, hideOverlays)) {
                        target = window;
                        kind = "overlay(type=" + lp.type + ")";
                    } else if (isIslandWindow(window, lp, current.service)) {
                        target = window;
                        kind = "dynamic-island";
                    } else if (isPinBubbleWindow(lp, hideFreeform)) {
                        target = window;
                        kind = "pin-bubbles";
                    } else if (hideFreeform) {
                        Object task = getTask.invoke(window);
                        if (task != null && ScreenshotWindowPolicy.freeform(
                                (int) getWindowingMode.invoke(task), hideFreeform)) {
                            // Capture-only: the concrete task owns its background and
                            // Shell decorations. Never select a shared root task.
                            target = task;
                            kind = "freeform-task";
                        }
                    }
                    if (target == null) return;
                    SurfaceControl source = (SurfaceControl) getSurface.invoke(target);
                    if (source == null || !source.isValid() || !selected.add(source)) return;
                    SurfaceControl copy = copySurface.newInstance(source, TAG);
                    current.copies.add(copy);
                    additions.add(copy);
                    current.labels.add(kind + ":" + lp.packageName);
                } catch (Throwable t) {
                    Log.e("HC-Screenshot", "WINDOW_SKIPPED", t);
                    XposedLog.w(TAG, PACKAGE, "HOOK_STATE=WINDOW_SKIPPED", t);
                }
            };
            forAllWindows.invoke(display, collect, true);
            // Pin visual surfaces may survive without an enumerable application window.
            // Enumerate only leaf tasks, never a shared root/display container.
            if (hideFreeform && forAllLeafTasks != null) {
                Consumer<Object> collectTask = task -> {
                    try {
                        if (!ScreenshotWindowPolicy.freeform(
                                (int) getWindowingMode.invoke(task), hideFreeform)) return;
                        SurfaceControl source = (SurfaceControl) getSurface.invoke(task);
                        if (source == null || !source.isValid() || !selected.add(source)) return;
                        SurfaceControl copy = copySurface.newInstance(source, TAG);
                        current.copies.add(copy);
                        additions.add(copy);
                        current.labels.add("freeform-leaf-task");
                    } catch (Throwable t) {
                        Log.e("HC-Screenshot", "PIN_TASK_SKIPPED", t);
                    }
                };
                try {
                    forAllLeafTasks.invoke(display, collectTask, true);
                } catch (Throwable t) {
                    // Keep already collected normal-task/window exclusions if this
                    // optional ROM enumeration path fails.
                    Log.e("HC-Screenshot", "PIN_ENUMERATION_SKIPPED", t);
                }
            }
        }
        if (additions.isEmpty()) {
            diagnostic("NO_MATCH display=" + current.displayId);
            return;
        }
        List<SurfaceControl> merged = new ArrayList<>();
        addExisting(merged, (SurfaceControl[]) builderExcludeLayers.get(builder));
        merged.addAll(additions);
        builderExcludeLayers.set(builder, merged.toArray(new SurfaceControl[0]));
        diagnostic("CAPTURE_EXCLUSIONS display=" + current.displayId
            + " added=" + additions.size() + " windows=" + current.labels);
    }

    private boolean isIslandWindow(Object window, WindowManager.LayoutParams lp, Object service)
            throws IllegalAccessException {
        // ROM: plugin Context supplies attrs.packageName, but SystemUI creates the window.
        // Match only the exact main window; unrelated keyguard/focus/glow windows stay intact.
        if (!hideIsland || lp.type != 2009
                || lp.getTitle() == null || !"DynamicIslandWindow".contentEquals(lp.getTitle())
                || !("miui.systemui.plugin".equals(lp.packageName)
                    || "com.android.systemui".equals(lp.packageName))) return false;
        Context ctx = (Context) context.get(service);
        String[] packages = ctx.getPackageManager().getPackagesForUid(ownerUid.getInt(window));
        if (packages == null) return false;
        for (String name : packages) {
            // Do not hard-code the ROM's SystemUI UID or use the plugin APK's install UID.
            if ("com.android.systemui".equals(name)) return true;
        }
        return false;
    }

    private static boolean isPinBubbleWindow(WindowManager.LayoutParams lp, boolean enabled) {
        // ROM evidence: MiuiBubbleController.addToWindowManagerMaybe uses this exact
        // SystemUI-owned title and type. Do not exclude unrelated accessibility/gesture overlays.
        return enabled && lp.type == 2027
            && "com.android.systemui".equals(lp.packageName)
            && lp.getTitle() != null && "MiuiBubbles".contentEquals(lp.getTitle());
    }

    private static void diagnostic(String message) {
        Log.i("HC-Screenshot", "HOOK_STATE=" + message);
        XposedLog.i(TAG, PACKAGE, "HOOK_STATE=" + message);
    }

    private static void addExisting(List<SurfaceControl> target, SurfaceControl[] existing) {
        if (existing == null) return;
        for (SurfaceControl surface : existing) {
            if (surface != null && !target.contains(surface)) target.add(surface);
        }
    }

    private static void releaseCopies(CaptureScope current) {
        for (SurfaceControl copy : current.copies) {
            try {
                copy.release();
            } catch (Throwable t) {
                XposedLog.w(TAG, PACKAGE, "HOOK_STATE=COPY_RELEASE_FAILED", t);
            }
        }
    }

    /**
     * HyperOS 4 declares two zero-argument build() methods on this builder: a bridge
     * method returning CaptureArgs and the real one returning LayerCaptureArgs. The
     * screenshot path calls the latter, so resolve it by return type instead of by
     * name alone, which would be ambiguous.
     */
    private static Method dataCaptureBuilderMethod(Class<?> builder) throws NoSuchMethodException {
        for (Method candidate : builder.getDeclaredMethods()) {
            if ("build".equals(candidate.getName())
                    && candidate.getParameterCount() == 0
                    && candidate.getReturnType().getName()
                        .equals("android.window.ScreenCaptureInternal$LayerCaptureArgs")) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        throw new NoSuchMethodException(builder.getName() + "#build():LayerCaptureArgs");
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) {
                // mExcludeLayers belongs to CaptureArgs.Builder, not its subclass.
            }
        }
        throw new NoSuchFieldException(type.getName() + "#" + name);
    }

    private static Method method(Class<?> type, String name, Class<?>... args)
            throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method result = current.getDeclaredMethod(name, args);
                result.setAccessible(true);
                return result;
            } catch (NoSuchMethodException ignored) {
                // WMS exposes several package-private inherited methods.
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static final class CaptureScope {
        Object service;
        int displayId;
        boolean allowed;
        CaptureScope previous;
        final List<SurfaceControl> copies = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
    }
}
