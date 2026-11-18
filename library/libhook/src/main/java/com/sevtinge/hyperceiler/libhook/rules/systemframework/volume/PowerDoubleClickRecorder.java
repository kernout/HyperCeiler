/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Uses OEM recognition; only a power double press begun interactive can toggle recording. */
public final class PowerDoubleClickRecorder extends BaseHook {
    private static final String PREF = "system_framework_volume_screen_off_recorder";
    private static final String MODULE = "com.sevtinge.hyperceiler";
    private static final String FUNCTION = "hyperceiler_recording_toggle";
    private static final String SOURCE = "double_click_power_key";
    private static final String TOGGLE = MODULE + ".action.SCREEN_OFF_RECORDING_POWER_TOGGLE";
    private static final String META = "hyperceiler.power_gesture.";
    private static final long PRIVACY_FRESH_MS = 2_500;
    // This stack exists ONLY during synchronous onMiuiMultiPress -> postTriggerFunction.
    // The posted Runnable receives a cloned Bundle, never this ThreadLocal or a last-event field.
    private final ThreadLocal<ArrayDeque<Gesture>> gestures = new ThreadLocal<>();
    private Field contextField, readyField, kidField, policyField;
    private Method setupComplete, currentUser, userOf, unlocked, startService, getFunction;
    private Method eventAction, eventType, eventCount, eventStart, eventKey, eventDisplay, eventDevice;
    private Method beganNonInteractive, privacyInstance, privacyAll, privacyCombined;
    private volatile boolean installed;

    private static final class Gesture {
        final Object rule;
        boolean interactive;
        int user = -1;
        long elapsed;
        String id;
        Gesture(Object rule) { this.rule = rule; }
    }

    @Override public void init() {
        if (!PrefsBridge.getBoolean(PREF, false)) return;
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            Class<?> owner = required("com.miui.server.input.util.ShortCutActionsUtils");
            Class<?> rule = required("com.android.server.input.shortcut.singlekeyrule.PowerKeyRule");
            Class<?> base = required("com.android.server.policy.MiuiSingleKeyRule");
            Class<?> event = required("com.android.server.policy.SingleKeyGestureEvent");
            Method entry = exact(owner, "triggerFunction", boolean.class,
                String.class, String.class, Bundle.class, boolean.class, String.class);
            Method caller = exact(owner, "triggerFunction", boolean.class,
                String.class, String.class, Bundle.class, boolean.class);
            Method multi = exact(rule, "onMiuiMultiPress", void.class,
                Object.class, long.class, int.class, int.class);
            Method doubleClick = exact(rule, "triggerDoubleClick", boolean.class);
            Method triggerMulti = exact(rule, "triggerMultiPress", boolean.class, int.class);
            Method post = exact(base, "postTriggerFunction", boolean.class,
                String.class, String.class, Bundle.class, boolean.class);
            Method onGesture = exact(base, "onKeyGesture", void.class, event);
            Method delivery = exact(base, "lambda$postTriggerFunction$0", void.class,
                String.class, String.class, Bundle.class, boolean.class);
            getFunction = exact(base, "getFunction", String.class, String.class);
            eventAction = exact(event, "getAction", int.class);
            eventType = exact(event, "getType", int.class);
            eventCount = exact(event, "getPressCount", int.class);
            eventStart = exact(event, "getStartTime", long.class);
            eventKey = exact(event, "getKeyCode", int.class);
            eventDisplay = exact(event, "getDisplayId", int.class);
            eventDevice = exact(event, "getDeviceId", int.class);
            beganNonInteractive = exact(event, "wasInteractiveOnGestureStart", boolean.class);
            contextField = field(owner, "mContext", Context.class);
            readyField = field(owner, "mIsSystemReady", boolean.class);
            kidField = field(owner, "mIsKidMode", boolean.class);
            policyField = field(owner, "mWindowManagerPolicy", null);
            setupComplete = exact(policyField.getType(), "isUserSetupComplete", boolean.class);
            currentUser = exact(ActivityManager.class, "getCurrentUser", int.class);
            userOf = exact(UserHandle.class, "of", UserHandle.class, int.class);
            unlocked = exact(UserManager.class, "isUserUnlocked", boolean.class, UserHandle.class);
            startService = exact(Context.class, "startForegroundServiceAsUser", ComponentName.class,
                Intent.class, UserHandle.class);
            Class<?> privacy = required("android.hardware.SensorPrivacyManager");
            privacyInstance = exact(privacy, "getInstance", privacy, Context.class);
            privacyAll = exact(privacy, "isAllSensorPrivacyEnabled", boolean.class);
            privacyCombined = exact(privacy, "areAnySensorPrivacyTogglesEnabled", boolean.class, int.class);
            // ROM startup/user-switch normalization validates persisted functions against
            // this process-local table before loading the observer's in-memory action map.
            // SecurityCore's f2.t.j(1) copy is in another process and cannot protect it.
            Class<?> config = required("miui.hardware.input.shortcut.ShortcutFunctionManager");
            Class<?> feature = required("miui.hardware.input.shortcut.MiInputShortcutFeature");
            Class<?> observer = required("com.android.server.policy.MiuiShortcutObserver");
            Class<?> singleObserver = required("com.android.server.policy.MiuiSingleKeyObserver");
            Method support = exact(config, "getAllSupportShortcutInfoMap", Map.class);
            Method supportReinit = exact(config, "getAllSupportShortcutInfoMap", Map.class, boolean.class);
            Method featureSupport = exact(feature, "getAllSupportShortcutInfoMap", Map.class);
            Method featureReinit = exact(feature, "getAllSupportShortcutInfoMap", Map.class, boolean.class);
            Method feasible = exact(observer, "isFeasibleFunction", boolean.class,
                String.class, String.class, Context.class);
            Method normalize = exact(singleObserver, "setDefaultFunction", void.class, boolean.class);
            // Deopt both getters' actual callers, including the boot-time validation
            // path. Never skip validation, setDefaultFunction, or its reset flag.
            for (Method method : new Method[]{caller, delivery, onGesture, multi, triggerMulti, doubleClick,
                    featureSupport, featureReinit, feasible, normalize, supportReinit}) {
                if (!deoptimize(method)) throw new IllegalStateException("power caller deopt rejected: " + method);
            }
            IMethodHook supportExtension = new IMethodHook() {
                @Override public void after(HookParam param) {
                    if (!installed || !PrefsBridge.getBoolean(PREF, false)
                        || param.getThrowable() != null) return;
                    // Return a copy; don't leave a module-owned entry in the ROM singleton
                    // after disable/hot reload. Reinit=true receives the same extension.
                    if (!(param.getResult() instanceof Map<?, ?> original)) return;
                    Map<Object, Object> copy = new LinkedHashMap<>(original);
                    Map<Object, Object> info = new LinkedHashMap<>();
                    if (original.get(FUNCTION) instanceof Map<?, ?> previous) info.putAll(previous);
                    String type = "gesture_shortcut_show_for_function";
                    List<Object> actions = new ArrayList<>();
                    if (info.get(type) instanceof List<?> previous) actions.addAll(previous);
                    if (!actions.contains(SOURCE)) actions.add(SOURCE);
                    info.put(type, actions);
                    copy.put(FUNCTION, info);
                    param.setResult(copy);
                }
            };
            handles.add(hookMethod(support, supportExtension));
            handles.add(hookMethod(supportReinit, supportExtension));
            handles.add(hookMethod(multi, new IMethodHook() {
                @Override public void before(HookParam param) {
                    ArrayDeque<Gesture> stack = gestures.get();
                    if (stack == null) { stack = new ArrayDeque<>(); gestures.set(stack); }
                    Gesture gesture = new Gesture(param.getThisObject());
                    stack.push(gesture);
                    if (installed) capture(gesture, param.getArgs());
                }
                @Override public void after(HookParam param) {
                    ArrayDeque<Gesture> stack = gestures.get();
                    if (stack != null) { stack.pop(); if (stack.isEmpty()) gestures.remove(); }
                }
            }));
            handles.add(hookMethod(doubleClick, new IMethodHook() {
                @Override public void before(HookParam param) {
                    if (!installed || !ours(param.getThisObject())) return;
                    Gesture gesture = active(param.getThisObject());
                    // Returning false here preserves OriginalPowerKeyRuleBridge's native fallback.
                    // Suppressing only the asynchronous dispatcher would already have consumed power.
                    if (gesture == null || !gesture.interactive) param.setResult(false);
                }
            }));
            handles.add(hookMethod(post, new IMethodHook() {
                @Override public void before(HookParam param) {
                    Object[] args = param.getArgs();
                    if (!installed || !SOURCE.equals(args[0]) || !FUNCTION.equals(args[1])) return;
                    Gesture gesture = active(param.getThisObject());
                    if (gesture == null || !gesture.interactive) { param.setResult(false); return; }
                    Bundle bundle = args[2] == null ? new Bundle() : new Bundle((Bundle) args[2]);
                    bundle.putBoolean(META + "interactive", gesture.interactive);
                    bundle.putInt(META + "user", gesture.user);
                    bundle.putLong(META + "elapsed", gesture.elapsed);
                    bundle.putString(META + "id", gesture.id);
                    args[2] = bundle;
                }
            }));
            // Four arguments delegate here. Hook only this overload: one physical gesture -> one request.
            handles.add(hookMethod(entry, new IMethodHook() {
                @Override public void before(HookParam param) {
                    Object[] args = param.getArgs();
                    if (installed && FUNCTION.equals(args[0]) && SOURCE.equals(args[1])) {
                        param.setResult(dispatch(param.getThisObject(), (Bundle) args[2]));
                    }
                }
            }));
            if (handles.contains(null)) throw new IllegalStateException("power recorder hook rejected");
            installed = true;
            registerHotReloadCleanup(() -> { installed = false; gestures.remove(); });
            XposedLog.i(TAG, "android", "Installed OEM interactive-start power recording toggle");
        } catch (Throwable error) {
            installed = false;
            for (XposedInterface.HookHandle handle : handles) {
                if (handle != null) try { handle.unhook(); } catch (Throwable ignored) { }
            }
            XposedLog.e(TAG, "android", "UNSUPPORTED power recorder gesture/dispatcher ABI", error);
        }
    }

    private boolean ours(Object rule) {
        try { return FUNCTION.equals(getFunction.invoke(rule, SOURCE)); }
        catch (Throwable error) { return false; }
    }

    private Gesture active(Object rule) {
        ArrayDeque<Gesture> stack = gestures.get();
        Gesture gesture = stack == null ? null : stack.peek();
        return gesture != null && gesture.rule == rule ? gesture : null;
    }

    private void capture(Gesture gesture, Object[] args) {
        if ((Integer) args[2] != 2 || !ours(gesture.rule)) return; // SOS and other functions untouched.
        try {
            Object event = args[0];
            long start = (Long) eventStart.invoke(event);
            if ((Integer) eventAction.invoke(event) != 1 || (Integer) eventType.invoke(event) != 0
                || (Integer) eventCount.invoke(event) != 2 || (Integer) eventKey.invoke(event) != 26
                || start != (Long) args[1] || start < 0
                || !eventDisplay.invoke(event).equals(args[3])) return;
            // ROM services-current.jar classes3.dex: this misleading getter directly returns
            // mBeganFromNonInteractive (smali line 867), NOT "was interactive". Invert it.
            if ((Boolean) beganNonInteractive.invoke(event)) return;
            gesture.user = (Integer) currentUser.invoke(null);
            gesture.elapsed = SystemClock.elapsedRealtime();
            gesture.id = "power:" + gesture.user + ":" + eventDevice.invoke(event)
                + ":" + args[3] + ":" + start;
            gesture.interactive = gesture.user >= 0;
        } catch (Throwable error) {
            XposedLog.w(TAG, "android", "Power gesture metadata unavailable; preserving native power fallback", error);
        }
    }

    private boolean oemAllowed(Object owner) throws ReflectiveOperationException {
        Object policy = policyField.get(owner);
        return PrefsBridge.getBoolean(PREF, false) && readyField.getBoolean(owner)
            && !kidField.getBoolean(owner) && policy != null
            && Boolean.TRUE.equals(setupComplete.invoke(policy));
    }

    private boolean dispatch(Object owner, Bundle gesture) {
        long identity = Binder.clearCallingIdentity();
        try {
            if (gesture == null || !gesture.getBoolean(META + "interactive", false)
                || !oemAllowed(owner)) return false;
            String requestId = gesture.getString(META + "id");
            int user = gesture.getInt(META + "user", -1);
            long captured = gesture.getLong(META + "elapsed", -1);
            if (requestId == null || !requestId.startsWith("power:") || user < 0
                || captured < 0 || captured > SystemClock.elapsedRealtime()
                || (Integer) currentUser.invoke(null) != user) return false;
            Context context = (Context) contextField.get(owner);
            if (context == null) return false;
            UserHandle handle = (UserHandle) userOf.invoke(null, user);
            UserManager users = context.getSystemService(UserManager.class);
            if (users == null || !Boolean.TRUE.equals(unlocked.invoke(users, handle))) return false;
            Object privacy = privacyInstance.invoke(null, context);
            if (privacy == null) return false;
            // A known blocked microphone must still reach the service's STOP-before-gates path.
            // A failed privacy query is unknown, so reject rather than attest a false success.
            boolean allBlocked = (Boolean) privacyAll.invoke(privacy);
            boolean micBlocked = (Boolean) privacyCombined.invoke(privacy, 1);
            boolean privacyAllowed = !allBlocked && !micBlocked;
            long checkedAt = SystemClock.elapsedRealtime();
            Intent intent = new Intent(TOGGLE)
                .setComponent(new ComponentName(MODULE, MODULE + ".recording.ScreenOffRecordingService"))
                .putExtra("hyperceiler.recording_request_id", requestId)
                .putExtra("hyperceiler.recording_burst_id", requestId)
                .putExtra("hyperceiler.recording_interactive", true)
                .putExtra("hyperceiler.microphone_privacy_checked", privacyAllowed)
                .putExtra("hyperceiler.microphone_privacy_checked_at", checkedAt);
            if (!oemAllowed(owner) || (Integer) currentUser.invoke(null) != user
                || !Boolean.TRUE.equals(unlocked.invoke(users, handle))) return false;
            long now = SystemClock.elapsedRealtime();
            if (now < checkedAt || now - checkedAt > PRIVACY_FRESH_MS
                || (Integer) currentUser.invoke(null) != user) return false;
            // No RECORD_AUDIO/mute check and no local debounce: service routes STOP first,
            // then checks start admission, deduplicates IDs and applies its shared 1s interval.
            return startService.invoke(context, intent, handle) != null;
        } catch (Throwable error) {
            XposedLog.w(TAG, "android", "Power double-click recording toggle rejected", error);
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
