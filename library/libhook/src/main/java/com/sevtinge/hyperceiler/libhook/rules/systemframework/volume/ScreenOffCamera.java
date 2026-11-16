/*
 * This file is part of HyperCeiler.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.KeyEvent;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Uses only the new MIUI volume-down rule; never installs a generic key-event hook. */
public final class ScreenOffCamera extends BaseHook {
    private static final String PREF = "system_framework_volume_screen_off_camera";
    private static final String RULE =
        "com.android.server.input.shortcut.singlekeyrule.VolumeDownKeyRule";
    private static final String BASE_RULE = "com.android.server.policy.MiuiSingleKeyRule";
    private static final String MODULE = "com.sevtinge.hyperceiler";
    private static final int POLICY_FLAG_INJECTED = 0x01000000;
    private static final long COOLDOWN_MS = 2500L;
    private static final int SENSOR_CAMERA = 2;

    private final AtomicBoolean dispatchPending = new AtomicBoolean();
    private volatile long nextDispatchUptime;
    private Field contextField;
    private Field handlerField;
    private Field policyFlagField;
    private Method audioActive;
    private Method cameraInSubscreen;
    private Method currentUser;
    private Method userHandleOf;
    private Method startServiceAsUser;

    @Override
    public void init() {
        if (!PrefsBridge.getBoolean(PREF, false)) return;
        if (Build.VERSION.SDK_INT < 35) {
            unsupported("requires SDK 35+ and the new VolumeDownKeyRule", null);
            return;
        }
        Class<?> rule = findClassIfExists(RULE, getClassLoader());
        Class<?> base = findClassIfExists(BASE_RULE, getClassLoader());
        if (rule == null || base == null || !base.isAssignableFrom(rule)) {
            unsupported("new VolumeDownKeyRule / MiuiSingleKeyRule absent", null);
            return;
        }

        // Validate every OEM and hidden API before installing either hook. In particular,
        // mContext/mHandler are private fields of MiuiSingleKeyRule, not BaseHook.
        final Method multiPress;
        final Method[] callers;
        final Method maxCount;
        try {
            contextField = field(base, "mContext", Context.class);
            handlerField = field(base, "mHandler", Handler.class);
            policyFlagField = field(rule, "mPolicyFlag", int.class);
            audioActive = method(rule, "isAudioActive", boolean.class);
            cameraInSubscreen = method(rule, "isCameraShowInSubscreen", boolean.class);
            multiPress = method(rule, "onMiuiMultiPress", void.class,
                Object.class, long.class, int.class, int.class);
            Class<?> gesture = findClassIfExists(
                "com.android.server.policy.SingleKeyGestureEvent", getClassLoader());
            Class<?> detector = findClassIfExists(
                "com.android.server.policy.SingleKeyGestureDetector", getClassLoader());
            Class<?> singleRule = findClassIfExists(
                "com.android.server.policy.SingleKeyGestureDetector$SingleKeyRule", getClassLoader());
            Class<?> keyHandler = findClassIfExists(
                "com.android.server.policy.SingleKeyGestureDetector$KeyHandler", getClassLoader());
            if (gesture == null || detector == null || singleRule == null || keyHandler == null) {
                throw new ClassNotFoundException("New single-key gesture dispatcher absent");
            }
            // Verified on this ROM: onKeyGesture dispatches onMiuiMultiPress;
            // getMaxMultiPressCount delegates to getMiuiMaxMultiPressCount. The
            // detector calls the latter dynamically on key down/up, never at addRule.
            // Deopt callers as well as wrappers to remove already inlined hook targets.
            callers = new Method[] {
                method(base, "onKeyGesture", void.class, gesture),
                method(keyHandler, "handleMessage", void.class, Message.class),
                // Start/cancel also send type=0 gestures directly, outside KeyHandler.
                method(detector, "startDelayedPress", void.class,
                    singleRule, int.class, int.class),
                method(detector, "cancelDelayedPress", void.class, singleRule, int.class),
                method(base, "getMaxMultiPressCount", int.class),
                method(detector, "interceptKeyDown", void.class, KeyEvent.class),
                method(detector, "interceptKeyUp", boolean.class, KeyEvent.class),
                method(detector, "interceptKey", void.class,
                    KeyEvent.class, boolean.class, int.class)
            };
            maxCount = method(rule, "getMiuiMaxMultiPressCount", int.class);
            currentUser = method(ActivityManager.class, "getCurrentUser", int.class);
            userHandleOf = method(UserHandle.class, "of", UserHandle.class, int.class);
            startServiceAsUser = method(Context.class, "startForegroundServiceAsUser",
                ComponentName.class, Intent.class, UserHandle.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            unsupported("new rule or foreground-service API signature mismatch", e);
            return;
        }

        // All deoptimizations must succeed before enabling recognition. No global key
        // method is hooked; these exact existing callers only lose compiled inlining.
        try {
            for (Method caller : callers) {
                if (!deoptimize(caller)) {
                    throw new IllegalStateException("Deoptimization rejected: " + caller);
                }
            }
        } catch (Throwable e) {
            unsupported("cannot fence inlined single-key callers", e);
            return;
        }

        // Intercept the whole double-press branch, not its private camera callee:
        // even an inline copy of volumeDownKeyDoubleClick cannot escape this hook.
        // Install interception first; never expand recognition without interception.
        var captureHook = hookMethod(multiPress, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                if ((Integer) param.getArgs()[2] == 2) interceptDoubleClick(param);
            }
        });
        if (captureHook == null) {
            unsupported("double-click interception failed", null);
            return;
        }
        try {
            var countHook = hookMethod(maxCount, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    try {
                        Context context = (Context) contextField.get(param.getThisObject());
                        if (isScreenOff(context) && PrefsBridge.getBoolean(PREF, false)) {
                            // This rule only implements count == 2. Do not ask the OEM
                            // camera setting: its disabled state otherwise returns 1.
                            param.setResult(2);
                        }
                    } catch (Throwable e) {
                        warn("Cannot enable screen-off double-press recognition", e);
                    }
                }
            });
            if (countHook == null) throw new IllegalStateException("max-count hook failed");
        } catch (Throwable e) {
            captureHook.unhook();
            unsupported("max-count interception failed; capture hook removed", e);
            return;
        }
        XposedLog.i(TAG, "android", "Installed screen-off camera on new VolumeDownKeyRule multi-press entry");
    }

    private void interceptDoubleClick(HookParam param) {
        final Context context;
        try {
            context = (Context) contextField.get(param.getThisObject());
            if (!isScreenOff(context)) return; // Interactive / lit screen: OEM behavior.
        } catch (Throwable e) {
            // An unreadable power state must not accidentally run the wake/UI path.
            param.setResult(null);
            warn("Power state unavailable; native camera action suppressed", e);
            return;
        }

        // Cancel BEFORE all gates, reflection, posting, and IPC. No failure below may
        // fall back to the OEM wake lock / camera UI. AOD is non-interactive as well.
        param.setResult(null);
        try {
            Object rule = param.getThisObject();
            if (!PrefsBridge.getBoolean(PREF, false) || !passesOemGates(rule)) return;
            Handler handler = (Handler) handlerField.get(rule);
            if (handler == null || !reserveDispatch()) return;
            try {
                if (!handler.post(() -> dispatchCapture(context, rule))) {
                    dispatchPending.set(false);
                    warn("Rule handler rejected capture; native action remains suppressed", null);
                }
            } catch (Throwable e) {
                dispatchPending.set(false);
                throw e;
            }
        } catch (Throwable e) {
            warn("Screen-off capture rejected; native action remains suppressed", e);
        }
    }

    private boolean passesOemGates(Object rule) throws ReflectiveOperationException {
        return (policyFlagField.getInt(rule) & POLICY_FLAG_INJECTED) == 0
            && Boolean.FALSE.equals(audioActive.invoke(rule))
            && Boolean.FALSE.equals(cameraInSubscreen.invoke(rule));
    }

    private boolean reserveDispatch() {
        if (!dispatchPending.compareAndSet(false, true)) return false;
        long now = SystemClock.uptimeMillis();
        if (now < nextDispatchUptime) {
            dispatchPending.set(false);
            return false;
        }
        nextDispatchUptime = now + COOLDOWN_MS;
        return true;
    }

    private void dispatchCapture(Context context, Object rule) {
        long identity = Binder.clearCallingIdentity();
        try {
            // Recheck after posting: screen, user and OEM gates can change in the queue.
            if (!isScreenOff(context) || !PrefsBridge.getBoolean(PREF, false)
                || !passesOemGates(rule)) return;
            int userId = (Integer) currentUser.invoke(null);
            if (userId < 0) throw new IllegalStateException("No concrete foreground user");
            UserHandle user = (UserHandle) userHandleOf.invoke(null, userId);
            if (!cameraPrivacyAllowsCapture(context)) return;
            long privacyCheckedAt = SystemClock.elapsedRealtime();
            // The privacy service's combined query concerns its current user. Reject a
            // foreground-user change during the queries instead of dispatching stale state.
            if ((Integer) currentUser.invoke(null) != userId) return;
            Intent intent = new Intent("com.sevtinge.hyperceiler.action.SCREEN_OFF_CAPTURE")
                .setComponent(new ComponentName(MODULE, MODULE + ".camera.ScreenOffCaptureService"))
                .putExtra("hyperceiler.sensor_privacy_checked", true)
                .putExtra("hyperceiler.sensor_privacy_checked_at", privacyCheckedAt);
            Object started = startServiceAsUser.invoke(context, intent, user);
            if (started == null) warn("Capture service not found for current user " + userId, null);
        } catch (Throwable e) {
            warn("Capture service dispatch failed; native action remains suppressed", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
            dispatchPending.set(false);
        }
    }

    private boolean cameraPrivacyAllowsCapture(Context context) throws ReflectiveOperationException {
        // These APIs require OBSERVE_SENSOR_PRIVACY: run only in system_server after
        // clearCallingIdentity, never in the ordinary module service. Resolve at dispatch
        // so a missing privacy API still leaves the native wake/UI branch intercepted.
        Class<?> managerClass = findClassIfExists(
            "android.hardware.SensorPrivacyManager", getClassLoader());
        if (managerClass == null) throw new ClassNotFoundException("SensorPrivacyManager unavailable");
        Object manager = method(managerClass, "getInstance", managerClass, Context.class)
            .invoke(null, context);
        if (manager == null) throw new IllegalStateException("SensorPrivacyManager unavailable");
        boolean allSensorsBlocked = (Boolean) method(managerClass,
            "isAllSensorPrivacyEnabled", boolean.class).invoke(manager);
        // Actual service implementation ORs software (1) and hardware (2) for CAMERA.
        // Do not use the deprecated one-argument isSensorPrivacyEnabled: software only.
        boolean cameraBlocked = (Boolean) method(managerClass,
            "areAnySensorPrivacyTogglesEnabled", boolean.class, int.class)
            .invoke(manager, SENSOR_CAMERA);
        if (allSensorsBlocked || cameraBlocked) {
            warn("Capture blocked by sensor privacy; native action remains suppressed", null);
            return false;
        }
        return true;
    }

    private static boolean isScreenOff(Context context) {
        if (context == null) throw new IllegalStateException("Rule Context unavailable");
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null) throw new IllegalStateException("PowerManager unavailable");
        return !power.isInteractive();
    }

    private static Field field(Class<?> owner, String name, Class<?> type)
        throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type) throw new NoSuchFieldException(owner.getName() + "." + name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... args)
        throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, args);
        if (method.getReturnType() != result) throw new NoSuchMethodException(owner.getName() + "." + name);
        method.setAccessible(true);
        return method;
    }

    private void unsupported(String reason, Throwable error) {
        String message = "UNSUPPORTED: " + reason + "; no legacy key hook installed";
        if (error == null) XposedLog.e(TAG, "android", message);
        else XposedLog.e(TAG, "android", message, error);
    }

    private void warn(String message, Throwable error) {
        if (error == null) XposedLog.w(TAG, "android", message);
        else XposedLog.w(TAG, "android", message, error);
    }
}
