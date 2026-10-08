/*
 * This file is part of HyperCeiler.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.display;

import android.content.Context;
import android.os.Binder;
import android.view.SurfaceControl;
import android.view.WindowManager;

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
    private Field builderExcludeLayers;
    private Method getDisplay;
    private Method forAllWindows;
    private Method getSurface;
    private Method getTask;
    private Method getRootTask;
    private Method getWindowingMode;
    private Constructor<SurfaceControl> copySurface;
    private boolean hideOverlays;
    private boolean hideFreeform;

    @Override
    public void init() {
        if (android.os.Build.VERSION.SDK_INT < 37) return;
        try {
            install();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported HyperOS 4 WMS screenshot API", e);
        }
    }

    private void install() throws ReflectiveOperationException {
        hideOverlays = PrefsBridge.getBoolean("system_ui_status_bar_hide_overlay");
        hideFreeform = PrefsBridge.getBoolean("system_ui_status_bar_hide_freeform");

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
        builderExcludeLayers = field(builder, "mExcludeLayers");
        copySurface = SurfaceControl.class.getConstructor(SurfaceControl.class, String.class);
        getDisplay = method(rootContainer, "getDisplayContent", int.class);
        forAllWindows = method(windowContainer, "forAllWindows", Consumer.class, boolean.class);
        getSurface = method(windowContainer, "getSurfaceControl");
        getTask = method(windowState, "getTask");
        getRootTask = method(task, "getRootTask");
        getWindowingMode = method(task, "getWindowingMode");

        wms.getDeclaredMethod("captureDisplay", int.class, capture, listener);
        builder.getDeclaredMethod("build");

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
            var buildHook = findAndHookMethod(builder, "build", new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    CaptureScope current = scope.get();
                    if (current == null || !current.allowed) return;
                    try {
                        appendExclusions(param.getThisObject(), current);
                    } catch (Throwable t) {
                        XposedLog.w(TAG, PACKAGE, "HOOK_STATE=EXCLUSION_FAILED", t);
                    }
                }
            });
            if (buildHook == null) throw new IllegalStateException("LayerCaptureArgs build hook failed");
        } catch (Throwable t) {
            captureHook.unhook();
            throw t;
        }
        XposedLog.i(TAG, PACKAGE, "HOOK_STATE=INSTALLED overlay=" + hideOverlays
            + " freeform=" + hideFreeform + " target=LayerCaptureArgs.Builder");
    }

    private boolean isScreenshotCaller(Object service) {
        try {
            Context ctx = (Context) context.get(service);
            String[] packages = ctx.getPackageManager()
                .getPackagesForUid(Binder.getCallingUid());
            if (packages == null) return false;
            for (String name : packages) {
                if ("com.android.systemui".equals(name)
                        || "com.miui.screenshot".equals(name)) return true;
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
                    if (ScreenshotWindowPolicy.overlay(lp.type, hideOverlays)) {
                        target = window;
                        kind = "overlay(type=" + lp.type + ")";
                    } else if (hideFreeform) {
                        Object task = getTask.invoke(window);
                        if (task != null && ScreenshotWindowPolicy.freeform(
                                (int) getWindowingMode.invoke(task), true)) {
                            Object rootTask = getRootTask.invoke(task);
                            target = rootTask != null && ScreenshotWindowPolicy.freeform(
                                (int) getWindowingMode.invoke(rootTask), true) ? rootTask : task;
                            kind = "freeform";
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
                    XposedLog.w(TAG, PACKAGE, "HOOK_STATE=WINDOW_SKIPPED", t);
                }
            };
            forAllWindows.invoke(display, collect, true);
        }
        if (additions.isEmpty()) {
            XposedLog.i(TAG, PACKAGE, "HOOK_STATE=NO_MATCH display=" + current.displayId);
            return;
        }
        List<SurfaceControl> merged = new ArrayList<>();
        addExisting(merged, (SurfaceControl[]) builderExcludeLayers.get(builder));
        merged.addAll(additions);
        builderExcludeLayers.set(builder, merged.toArray(new SurfaceControl[0]));
        XposedLog.i(TAG, PACKAGE, "HOOK_STATE=CAPTURE_EXCLUSIONS display=" + current.displayId
            + " added=" + additions.size() + " windows=" + current.labels);
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