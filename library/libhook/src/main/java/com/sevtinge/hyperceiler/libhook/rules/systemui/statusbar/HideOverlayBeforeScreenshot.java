/*
 * This file is part of HyperCeiler.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemui.statusbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
import android.view.WindowManager;
import androidx.core.content.ContextCompat;
import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Temporarily hides system/application overlay windows while a screenshot is captured. */
public class HideOverlayBeforeScreenshot extends BaseHook {
    private static final String ACTION_TAKE_SCREENSHOT = "miui.intent.TAKE_SCREENSHOT";
    private static final String EXTRA_IS_FINISHED = "IsFinished";
    private final Map<View, OverlayState> states = Collections.synchronizedMap(new IdentityHashMap<>());

    @Override
    public void init() {
        // HyperOS 4 excludes surfaces in system_server; do not touch live Views.
        if (android.os.Build.VERSION.SDK_INT >= 37) return;
        hookAllMethods("android.view.WindowManagerGlobal", "addView", new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object[] args = param.getArgs();
                if (args == null || args.length < 2 || !(args[0] instanceof View)
                        || !(args[1] instanceof WindowManager.LayoutParams)) return;
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) args[1];
                if (lp.type != WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
                        && lp.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) return;
                register((View) args[0]);
            }
        });
    }

    private void register(final View view) {
        if (view == null || states.containsKey(view)) return;
        Context context = view.getContext();
        if (context == null) return;
        final OverlayState state = new OverlayState(view);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!ACTION_TAKE_SCREENSHOT.equals(intent.getAction())) return;
                boolean finished = intent.getBooleanExtra(EXTRA_IS_FINISHED, true);
                if (finished) state.restore(); else state.hide();
                XposedLog.d(TAG, getPackageName(), "overlay screenshot "
                        + (finished ? "restore" : "hide") + " view=" + view.getClass().getName());
            }
        };
        ContextCompat.registerReceiver(context, receiver, new IntentFilter(ACTION_TAKE_SCREENSHOT),
                ContextCompat.RECEIVER_EXPORTED);
        states.put(view, state);
        registerReceiverHotReloadCleanup(context, receiver);
        registerHotReloadCleanup(() -> {
            synchronized (states) {
                for (OverlayState item : states.values()) item.restore();
                states.clear();
            }
        });
    }

    private static final class OverlayState {
        private final View view;
        private int originalVisibility;
        private boolean hidden;

        OverlayState(View view) { this.view = view; }

        void hide() {
            postVisibility(View.GONE, false);
        }
        void restore() {
            postVisibility(-1, true);
        }
        private void postVisibility(final int requestedVisibility, final boolean restoring) {
            try {
                view.post(() -> {
                    try {
                        synchronized (OverlayState.this) {
                            if (restoring) {
                                if (!hidden) return;
                                view.setVisibility(originalVisibility);
                                hidden = false;
                            } else {
                                if (!hidden) {
                                    originalVisibility = view.getVisibility();
                                    hidden = true;
                                }
                                view.setVisibility(requestedVisibility);
                            }
                        }
                    } catch (Throwable t) {
                        XposedLog.w("HideOverlayBeforeScreenshot", "com.android.systemui", "overlay visibility update failed", t);
                    }
                });
            } catch (Throwable t) {
                XposedLog.w("HideOverlayBeforeScreenshot", "com.android.systemui", "overlay visibility post failed", t);
            }
        }
    }
}
