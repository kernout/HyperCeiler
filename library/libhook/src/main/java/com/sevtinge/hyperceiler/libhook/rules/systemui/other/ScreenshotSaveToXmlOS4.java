/*
 * This file is part of HyperCeiler.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemui.other;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.os.Build;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.rules.media.CaptureFileNames;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.ZonedDateTime;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Changes only new native SystemUI screenshot metadata; MediaStore performs the save. */
public final class ScreenshotSaveToXmlOS4 extends BaseHook {
    public static final String PREF = "system_ui_screenshot_save_to_xml";

    public static final String NAMES = "system_ui_screenshot_remove_filename_prefix";

    @Override
    public void init() {
        boolean save = PrefsBridge.getBoolean(PREF, false);
        boolean names = PrefsBridge.getBoolean(NAMES, false);
        if (Build.VERSION.SDK_INT < 37 || (!save && !names)) return;
        Class<?> exporter = findClass("com.android.systemui.screenshot.ImageExporter");
        try {
            Method metadata = exporter.getDeclaredMethod("createMetadata", ZonedDateTime.class,
                Bitmap.CompressFormat.class, String.class);
            if (metadata.getReturnType() != ContentValues.class
                || !Modifier.isStatic(metadata.getModifiers())) {
                throw new NoSuchMethodException("ImageExporter.createMetadata signature changed");
            }
            if (names) {
                Method filename = exporter.getDeclaredMethod("createFilename", ZonedDateTime.class,
                    Bitmap.CompressFormat.class, int.class);
                if (filename.getReturnType() != String.class || !Modifier.isStatic(filename.getModifiers()))
                    throw new NoSuchMethodException("ImageExporter.createFilename signature changed");
                if (hookMethod(filename, new IMethodHook() {
                    @Override public void after(HookParam param) {
                        if (param.getThrowable() == null && param.getResult() instanceof String name)
                            param.setResult(CaptureFileNames.screenshot(name));
                    }
                }) == null) throw new IllegalStateException("ImageExporter filename hook not installed");
                deoptimizeMethods(findClass("com.android.systemui.screenshot.ScreenshotController"),
                    "saveScreenshotInBackground");
                deoptimizeMethods(findClass("com.android.systemui.screenshot.scroll.LongScreenshotActivity"),
                    "startExport");
                deoptimizeMethods(findClass("com.android.systemui.screenshot.appclips.AppClipsViewModel$$ExternalSyntheticLambda1"),
                    "run");
            }
            // ROM call chain: Task.execute -> synthetic createEntry -> createMetadata.
            deoptimizeMethods(exporter, "-$$Nest$smcreateEntry");
            deoptimizeMethods(findClass("com.android.systemui.screenshot.ImageExporter$Task"),
                "execute");
            if (hookMethod(metadata, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    if (param.getThrowable() == null
                        && param.getResult() instanceof ContentValues values) {
                        // Keep the original object, generated filename/extension, MIME, dates and pending
                        // lifecycle. This is a folder named xml; the image format is unchanged.
                        if (save) values.put("relative_path", "Pictures/xml/");
                    }
                }
            }) == null) {
                throw new IllegalStateException("ImageExporter.createMetadata hook not installed");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported HyperOS 4 ImageExporter API", e);
        }
    }
}
