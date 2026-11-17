/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Adds a start-only action to the OEM power gesture dispatcher, not a key recognizer. */
public final class PowerDoubleClickRecorder extends BaseHook {
    private static final String PREF = "system_framework_volume_screen_off_recorder";
    private static final String MODULE = "com.sevtinge.hyperceiler";
    private static final String FUNCTION = "hyperceiler_recording_toggle";
    private static final String SOURCE = "double_click_power_key";
    private static final String START = MODULE + ".action.SCREEN_OFF_RECORDING_START_ONLY";
    private static final long PRIVACY_FRESH_MS = 2_500;
    private Field contextField, readyField, kidField, policyField;
    private Method setupComplete, currentUser, userOf, unlocked, packageContext, startService;
    private Method privacyInstance, privacyAll, privacyCombined;

    @Override public void init() {
        if (!PrefsBridge.getBoolean(PREF, false)) return;
        try {
            Class<?> owner = required("com.miui.server.input.util.ShortCutActionsUtils");
            Method entry = exact(owner, "triggerFunction", boolean.class,
                String.class, String.class, Bundle.class, boolean.class, String.class);
            // Resolve every required ABI before installing; no alternate key hook on failure.
            contextField = field(owner, "mContext", Context.class);
            readyField = field(owner, "mIsSystemReady", boolean.class);
            kidField = field(owner, "mIsKidMode", boolean.class);
            policyField = field(owner, "mWindowManagerPolicy", null);
            setupComplete = exact(policyField.getType(), "isUserSetupComplete", boolean.class);
            currentUser = exact(ActivityManager.class, "getCurrentUser", int.class);
            userOf = exact(UserHandle.class, "of", UserHandle.class, int.class);
            unlocked = exact(UserManager.class, "isUserUnlocked", boolean.class, UserHandle.class);
            packageContext = exact(Context.class, "createPackageContextAsUser", Context.class,
                String.class, int.class, UserHandle.class);
            startService = exact(Context.class, "startForegroundServiceAsUser", ComponentName.class,
                Intent.class, UserHandle.class);
            Class<?> privacy = required("android.hardware.SensorPrivacyManager");
            privacyInstance = exact(privacy, "getInstance", privacy, Context.class);
            privacyAll = exact(privacy, "isAllSensorPrivacyEnabled", boolean.class);
            privacyCombined = exact(privacy, "areAnySensorPrivacyTogglesEnabled", boolean.class, int.class);
            // The four-argument overload delegates here and may already have been inlined.
            Method caller = exact(owner, "triggerFunction", boolean.class,
                String.class, String.class, Bundle.class, boolean.class);
            if (!deoptimize(caller)) throw new IllegalStateException("dispatcher caller deopt rejected");
            if (hookMethod(entry, new IMethodHook() {
                @Override public void before(HookParam param) {
                    Object[] args = param.getArgs();
                    if (FUNCTION.equals(args[0]) && SOURCE.equals(args[1])) {
                        // Consume only our action, including denied/disabled requests. Never
                        // fall through to an unknown OEM action or change power/SOS rules.
                        param.setResult(dispatch(param.getThisObject()));
                    }
                }
            }) == null) throw new IllegalStateException("power recorder action hook rejected");
            XposedLog.i(TAG, "android", "Installed OEM power double-click recorder start action");
        } catch (Throwable error) {
            XposedLog.e(TAG, "android", "UNSUPPORTED power recorder dispatcher ABI", error);
        }
    }

    private boolean oemAllowed(Object owner) throws ReflectiveOperationException {
        Object policy = policyField.get(owner);
        return PrefsBridge.getBoolean(PREF, false) && readyField.getBoolean(owner)
            && !kidField.getBoolean(owner) && policy != null
            && Boolean.TRUE.equals(setupComplete.invoke(policy));
    }

    private boolean dispatch(Object owner) {
        long identity = Binder.clearCallingIdentity();
        try {
            if (!oemAllowed(owner)) return false;
            Context context = (Context) contextField.get(owner);
            int user = (Integer) currentUser.invoke(null);
            if (context == null || user < 0) return false;
            UserHandle handle = (UserHandle) userOf.invoke(null, user);
            UserManager users = context.getSystemService(UserManager.class);
            if (users == null || !Boolean.TRUE.equals(unlocked.invoke(users, handle))) return false;
            // Check the recorder package in the foreground user's namespace, not system's
            // RECORD_AUDIO grant or user 0's package when another user is foreground.
            Context recorder = (Context) packageContext.invoke(context, MODULE, 0, handle);
            if (recorder == null) return false;
            int recorderUid = recorder.getApplicationInfo().uid;
            if (recorderUid < 0 || context.checkPermission(Manifest.permission.RECORD_AUDIO, -1, recorderUid)
                != PackageManager.PERMISSION_GRANTED) return false;
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio == null || audio.isMicrophoneMute()) return false;
            Object privacy = privacyInstance.invoke(null, context);
            if (privacy == null || (Boolean) privacyAll.invoke(privacy)
                || (Boolean) privacyCombined.invoke(privacy, 1)) return false;
            long checkedAt = SystemClock.elapsedRealtime();
            String requestId = "power:" + UUID.randomUUID();
            Intent intent = new Intent(START)
                .setComponent(new ComponentName(MODULE, MODULE + ".recording.ScreenOffRecordingService"))
                .putExtra("hyperceiler.recording_request_id", requestId)
                .putExtra("hyperceiler.recording_burst_id", requestId)
                .putExtra("hyperceiler.recording_interactive", true)
                .putExtra("hyperceiler.microphone_privacy_checked", true)
                .putExtra("hyperceiler.microphone_privacy_checked_at", checkedAt);
            // Revalidate mutable gates after preflight and before submitting to this user.
            if (!oemAllowed(owner) || (Integer) currentUser.invoke(null) != user
                || !Boolean.TRUE.equals(unlocked.invoke(users, handle))
                || context.checkPermission(Manifest.permission.RECORD_AUDIO, -1, recorderUid)
                    != PackageManager.PERMISSION_GRANTED
                || audio.isMicrophoneMute() || (Boolean) privacyAll.invoke(privacy)
                || (Boolean) privacyCombined.invoke(privacy, 1)) return false;
            long now = SystemClock.elapsedRealtime();
            if (now < checkedAt || now - checkedAt > PRIVACY_FRESH_MS
                || (Integer) currentUser.invoke(null) != user) return false;
            // START_ONLY is deliberately idempotent while STARTING/RECORDING. No toggle,
            // activity, synthetic key, wake-up, or stop command is issued by this hook.
            return startService.invoke(context, intent, handle) != null;
        } catch (Throwable error) {
            XposedLog.w(TAG, "android", "Power double-click recorder start rejected", error);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Class<?> required(String name) throws ClassNotFoundException {
        Class<?> type = findClassIfExists(name, getClassLoader());
        if (type == null) throw new ClassNotFoundException(name);
        return type;
    }

    private static Method exact(Class<?> type, String name, Class<?> result, Class<?>... args)
        throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, args);
        if (method.getReturnType() != result) throw new NoSuchMethodException(type.getName() + "." + name);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws NoSuchFieldException {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField(name);
                if (expected != null && field.getType() != expected) throw new NoSuchFieldException(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException error) {
                if (owner.getSuperclass() == null) throw error;
            }
        }
        throw new NoSuchFieldException(name);
    }
}
