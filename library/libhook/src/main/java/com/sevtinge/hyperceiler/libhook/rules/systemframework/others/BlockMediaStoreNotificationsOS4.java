/*
 * This file is part of HyperCeiler.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.others;

import android.net.Uri;
import android.os.Build;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.reflect.Method;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Suppresses media ContentObserver delivery after ContentService's original validation. */
public final class BlockMediaStoreNotificationsOS4 extends BaseHook {
    public static final String PREF = "system_framework_block_media_store_notifications";

    @Override
    public void init() {
        if (Build.VERSION.SDK_INT < 37 || !PrefsBridge.getBoolean(PREF, false)) return;

        // ROM: notifyChange validates every URI/user/provider before ObserverNode collects it.
        // collectMyObserversLocked also checks self-change, user and descendant flags first.
        // Do not short-circuit notifyChange: sync, cache invalidation and identity restoration
        // must still run, and non-media URIs in the same batch must still be collected.
        Class<?> collector = findClass("com.android.server.content.ContentService$ObserverCollector");
        Class<?> observer = findClass("android.database.IContentObserver");
        try {
            Method collect = collector.getDeclaredMethod("collect", observer, int.class,
                boolean.class, Uri.class, int.class, int.class);
            if (collect.getReturnType() != void.class) {
                throw new NoSuchMethodException("ObserverCollector.collect must return void");
            }
            // collect is small enough to be inlined in this caller on ART.
            deoptimizeMethods(findClass("com.android.server.content.ContentService$ObserverNode"),
                "collectMyObserversLocked", "collectObserversLocked");
            deoptimizeMethods(findClass("com.android.server.content.ContentService"), "notifyChange");
            if (hookMethod(collect, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    if (param.getArgs()[3] instanceof Uri uri && isMediaUri(uri)) {
                        // Skip only this validated URI/observer tuple, for every UID/user
                        // including USER_ALL. Do not change args, registration or stored media.
                        param.setResult(null);
                    }
                }
            }) == null) {
                throw new IllegalStateException("ObserverCollector.collect hook not installed");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported HyperOS 4 ContentService collector API", e);
        }
    }

    private static boolean isMediaUri(Uri uri) {
        if (!"content".equals(uri.getScheme())) return false;
        // Use encoded authority so percent-encoded/lookalike authorities fail open.
        String authority = uri.getEncodedAuthority();
        if ("media".equals(authority)) return true;
        if (authority == null || !authority.endsWith("@media")) return false;
        int end = authority.length() - "@media".length();
        if (end == 0) return false;
        for (int i = 0; i < end; i++) {
            char c = authority.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        try {
            // Android's optional userId@authority prefix. USER_ALL is a separate int arg.
            return Integer.parseInt(authority.substring(0, end)) >= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
