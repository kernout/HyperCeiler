/* This file is part of HyperCeiler. Copyright (C) 2023-2026 HyperCeiler Contributions */
package com.sevtinge.hyperceiler.libhook.rules.screenrecorder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.rules.media.CaptureFileNames;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** ROM-specific new-recording hooks. Does not hook update, scan, settings or all CV puts. */
public final class RecorderSaveToXmlOS4 extends BaseHook {
    public static final String SAVE = "screenrecorder_save_to_xml";
    public static final String NAMES = "screenrecorder_remove_filename_prefix";
    private final ThreadLocal<ArrayDeque<Scope>> scopes = ThreadLocal.withInitial(ArrayDeque::new);
    // Only strings created by k0.r.i during this module's current recording session.
    private final Set<String> newPaths = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static final class Scope {
        final boolean miui;
        String path;
        Scope(boolean miui) { this.miui = miui; }
    }

    private final boolean systemUi;

    public RecorderSaveToXmlOS4() { this(false); }
    public RecorderSaveToXmlOS4(boolean systemUi) { this.systemUi = systemUi; }
    @Override public void init() { if (systemUi) initSystemUi(); else initMiui(); }

    public void initMiui() {
        boolean save = PrefsBridge.getBoolean(SAVE, false);
        boolean names = PrefsBridge.getBoolean(NAMES, false);
        if (Build.VERSION.SDK_INT < 37 || (!save && !names)) return;
        try {
            Class<?> controller = findClass("d0.d");
            Class<?> files = findClass("k0.r");
            Method start = exact(controller, "B", void.class, false);
            Method generated = exact(files, "i", String.class, true, Context.class);
            Method finish = exact(files, "c", String.class, true, String.class);
            // Verify every ROM-specific signature before installing a hook.
            Method insert = exact(ContentResolver.class, "insert", Uri.class, false,
                Uri.class, ContentValues.class);
            installInsert(insert, save, names);
            installScope(start, true);
            requireHook(generated, new IMethodHook() {
                @Override public void after(HookParam param) {
                    Scope scope = current();
                    if (scope == null || !scope.miui || param.getThrowable() != null
                        || !(param.getResult() instanceof String path)) return;
                    String basename = new File(path).getName();
                    if (basename.isBlank() || ".".equals(basename) || "..".equals(basename)) return;
                    // The original method only constructs a string: it does not create a file.
                    if (save) path = new File(new File(Environment.getExternalStorageDirectory(),
                        "Pictures/xml"), basename).getPath();
                    if (names) path = CaptureFileNames.recordingPath(path);
                    scope.path = path;
                    String pendingName = new File(path).getName();
                    // Native c() removes this ROM's leading hidden dot at final publication.
                    // Track only a newly generated name whose published prefix will change.
                    if (names && (pendingName.startsWith(".Screenrecorder_")
                        || pendingName.startsWith(".Screenrecorder-"))
                        && !CaptureFileNames.recording(pendingName.substring(1))
                            .equals(pendingName.substring(1))) newPaths.add(path);
                    param.setResult(path);
                }
            });
            requireHook(finish, new IMethodHook() {
                @Override public void after(HookParam param) {
                    Object source = param.getArgs()[0];
                    // c() is also called from UI/history. Only names generated above qualify.
                    if (!names || !(source instanceof String path) || !newPaths.contains(path)
                        || param.getThrowable() != null
                        || !(param.getResult() instanceof String result)) return;
                    param.setResult(CaptureFileNames.recordingPath(result));
                }
            });
            // Controller retains each generated string; weak keys do not retain old sessions.
            // Deoptimize the inspected start and final-publication callers.
            deoptimizeMethods(controller, "B", "C");
            deoptimizeMethods(findClass("d0.b"), "p");
            deoptimizeMethods(files, "a", "b");
            deoptimizeMethods(findClass("l0.k"), "T");
            deoptimizeMethods(findClass("com.miui.screenrecorder.service.RecorderService$e"), "a");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported MIUI recorder save contract", e);
        }
    }

    /** Separate entry point for SystemUI's recorder; no MIUI classes loaded in that process. */
    public void initSystemUi() {
        boolean save = PrefsBridge.getBoolean(SAVE, false);
        boolean names = PrefsBridge.getBoolean(NAMES, false);
        if (Build.VERSION.SDK_INT < 37 || (!save && !names)) return;
        try {
            Class<?> recorder = findClass("com.android.systemui.screenrecord.ScreenMediaRecorder");
            Method create = exact(recorder, "createRecordingUri", Uri.class, false);
            Method insert = exact(ContentResolver.class, "insert", Uri.class, false,
                Uri.class, ContentValues.class);
            installInsert(insert, save, names);
            installScope(create, false);
            deoptimizeMethods(recorder, "createRecordingUri");
            deoptimizeMethods(findClass("com.android.systemui.screenrecord.service.ScreenRecordingService"),
                "stopRecording");
            deoptimizeMethods(findClass("com.android.systemui.screenrecord.RecordingService$$ExternalSyntheticLambda1"),
                "run");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported SystemUI recorder save contract", e);
        }
    }

    private Scope current() { return scopes.get().peek(); }

    private void installScope(Method method, boolean miui) {
        requireHook(method, new IMethodHook() {
            @Override public void before(HookParam param) {
                scopes.get().push(new Scope(miui));
            }
            @Override public void after(HookParam param) {
                ArrayDeque<Scope> stack = scopes.get();
                if (!stack.isEmpty()) {
                    Scope scope = stack.pop();
                    if (param.getThrowable() != null && scope.path != null) newPaths.remove(scope.path);
                }
                if (stack.isEmpty()) scopes.remove();
            }
        });
    }

    private void installInsert(Method method, boolean save, boolean names) {
        requireHook(method, new IMethodHook() {
            @Override public void before(HookParam param) {
                Scope scope = current();
                if (scope == null || !(param.getArgs()[0] instanceof Uri uri)
                    || !"content".equals(uri.getScheme())
                    || !"media".equals(uri.getEncodedAuthority())
                    || !"/external/video/media".equals(uri.getPath())
                        && !"/external_primary/video/media".equals(uri.getPath())
                    || !(param.getArgs()[1] instanceof ContentValues values)) return;
                // Preserve the original object and all other values, pending/URI/errors included.
                if (save) values.put("relative_path", "Pictures/xml/");
                // MIUI already transformed the generated path exactly once; its publisher
                // handles the leading hidden-file dot separately. Never strip a second prefix.
                if (names && !scope.miui && values.get("_display_name") instanceof String name)
                    values.put("_display_name", CaptureFileNames.recording(name));
            }
        });
    }

    private static Method exact(Class<?> owner, String name, Class<?> returns, boolean isStatic,
                                Class<?>... parameters) throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, parameters);
        if (method.getReturnType() != returns || Modifier.isStatic(method.getModifiers()) != isStatic)
            throw new NoSuchMethodException(owner.getName() + "." + name);
        return method;
    }

    private void requireHook(Method method, IMethodHook hook) {
        if (hookMethod(method, hook) == null)
            throw new IllegalStateException("Recorder hook not installed: " + method);
    }
}
