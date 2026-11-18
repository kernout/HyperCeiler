/* Copyright (C) 2026 HyperCeiler Contributions */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Exact ROM queue entry, shared arbitration with ScreenOffRecorder regardless of
 * callback order. No input injection, Activity launch, wake lock or display change. */
public final class VolumeVideoChord extends BaseHook {
    private static final String PREF = "system_framework_volume_video_chord";
    private static final String MODULE = "com.sevtinge.hyperceiler";
    private static final long FRESH_MS = 400;
    private static volatile VolumeVideoChord active;
    private final Object lock = new Object();
    private final VolumeVideoChordState state = new VolumeVideoChordState();
    private final ThreadLocal<Boolean> cameraReplay = ThreadLocal.withInitial(() -> false);
    private Context context;
    private Handler handler;
    private Object policy;
    private boolean listeners;
    private int pending;
    private long epoch;
    private Field contextField, handlerField, boot, detectorField, enterprise, dp, front, back;
    private Method currentUser, userOf, unlocked, startService, displayId, reset;
    private Method privacyInstance, privacyAll, privacyCombined, waterInstance, waterActive, dpMode, held;
    private Method registerReceiver;
    // Both callbacks see the same immutable queue-event view; never run accept twice.
    private KeyEvent lastEvent;
    private Object lastTarget;
    private long lastTime, lastDown;
    private int lastAction, lastRepeat, lastKey, lastFlags, lastDevice, lastSource, lastScan, lastMeta, lastEventFlags;
    private boolean lastConsumed;
    private final java.util.Map<String, Long> diagnostics = new java.util.HashMap<>();

    // Diagnostics contain only fixed reason codes: no key timestamps, descriptors,
    // request UUIDs, user IDs or event payloads. Rate limit each code independently.
    private void diagnostic(String reason) {
        long now = SystemClock.uptimeMillis();
        Long previous = diagnostics.get(reason);
        if (previous == null || now - previous >= 5000) {
            diagnostics.put(reason, now);
            XposedLog.i(TAG, "android", "Volume video chord: " + reason);
        }
    }

    /** This board splits its keys across gpio-keys and pmic_resin. Require one
     * unambiguous built-in device for each role, never merely a keyboard source. */
    private VolumeVideoChordState.Source boardSource(int key) {
        if (key != 24 && key != 25) return null;
        String name = key == 24 ? "gpio-keys" : "pmic_resin";
        VolumeVideoChordState.Source found = null;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (id < 0 || d == null || !name.equals(d.getName())) continue;
            if (d.isVirtual()) { diagnostic("reject/virtual-board-device"); continue; }
            if (d.isExternal()) { diagnostic("reject/external-board-device"); continue; }
            if (!d.supportsSource(InputDevice.SOURCE_KEYBOARD)) { diagnostic("reject/board-keyboard-source"); continue; }
            String descriptor = d.getDescriptor();
            boolean[] keys = d.hasKeys(24, 25);
            if (descriptor == null || descriptor.isEmpty()) { diagnostic("reject/board-descriptor"); continue; }
            if (keys == null || keys.length != 2 || !keys[key == 24 ? 0 : 1] || keys[key == 24 ? 1 : 0]) {
                diagnostic("reject/board-key-capabilities"); continue;
            }
            if (found != null) { diagnostic("reject/ambiguous-board-role"); return null; }
            found = new VolumeVideoChordState.Source(id, descriptor, InputDevice.SOURCE_KEYBOARD);
        }
        return found;
    }

    @Override public void init() {
        if (!PrefsBridge.getBoolean(PREF, false)) return;
        try {
            Class<?> owner = required("com.android.server.policy.MiuiPhoneWindowManager");
            Method entry = method(owner, "interceptKeyBeforeQueueing", int.class, KeyEvent.class, int.class);
            Class<?> cameraRule = required("com.android.server.input.shortcut.singlekeyrule.VolumeDownKeyRule");
            Class<?> baseRule = required("com.android.server.policy.MiuiSingleKeyRule");
            if (!baseRule.isAssignableFrom(cameraRule)) throw new IllegalStateException("camera rule owner mismatch");
            Field cameraHandler = field(baseRule, "mHandler", Handler.class);
            Method cameraPress = method(cameraRule, "onMiuiMultiPress", void.class,
                Object.class, long.class, int.class, int.class);
            contextField = field(owner, "mContext", Context.class);
            handlerField = field(owner, "mHandler", Handler.class);
            boot = field(owner, "mSystemBooted", boolean.class);
            Class<?> detector = required("com.android.server.policy.SingleKeyGestureDetector");
            detectorField = field(owner, "mSingleKeyGestureDetector", detector);
            reset = method(detector, "reset", void.class);
            dp = field(owner, "mMiuiInputManagerInternal", null);
            dpMode = method(dp.getType(), "isDpInMonitorMode", boolean.class);
            front = field(owner, "mProximitySensor", null);
            back = field(owner, "mBackScreenProximitySensor", front.getType());
            held = method(front.getType(), "isHeld", boolean.class);
            enterprise = field(required("miui.enterprise.EnterpriseManagerStub"), "ENTERPRISE_ACTIVATED", boolean.class);
            Class<?> water = required("com.android.server.input.UnderwaterInputController");
            waterInstance = method(water, "getInstance", water);
            waterActive = method(water, "isActive", boolean.class);
            currentUser = method(ActivityManager.class, "getCurrentUser", int.class);
            userOf = method(UserHandle.class, "of", UserHandle.class, int.class);
            unlocked = method(UserManager.class, "isUserUnlocked", boolean.class, UserHandle.class);
            startService = method(Context.class, "startForegroundServiceAsUser", ComponentName.class, Intent.class, UserHandle.class);
            registerReceiver = method(Context.class, "registerReceiverAsUser", Intent.class,
                BroadcastReceiver.class, UserHandle.class, IntentFilter.class, String.class, Handler.class, int.class);
            // Validate the actual SDK surface before publishing either hook.
            method(InputDevice.class, "getDeviceIds", int[].class);
            method(InputDevice.class, "hasKeys", boolean[].class, int[].class);
            method(InputDevice.class, "isExternal", boolean.class);
            displayId = method(KeyEvent.class, "getDisplayId", int.class);
            Class<?> privacy = required("android.hardware.SensorPrivacyManager");
            privacyInstance = method(privacy, "getInstance", privacy, Context.class);
            privacyAll = method(privacy, "isAllSensorPrivacyEnabled", boolean.class);
            privacyCombined = method(privacy, "areAnySensorPrivacyTogglesEnabled", boolean.class, int.class);
            Method callback = method(required("com.android.server.wm.InputManagerCallback"),
                "interceptKeyBeforeQueueing", int.class, KeyEvent.class, int.class);
            Method upstream = method(required("com.android.server.input.InputManagerService"),
                "interceptKeyBeforeQueueing", int.class, KeyEvent.class, int.class);
            if (!deoptimize(callback) || !deoptimize(upstream)) throw new IllegalStateException("queue caller deopt rejected");
            // Also arbitrate native camera when the separate screen-off camera pref is OFF.
            Method ruleDispatch = method(baseRule, "onKeyGesture", void.class,
                required("com.android.server.policy.SingleKeyGestureEvent"));
            Method keyDispatch = method(required("com.android.server.policy.SingleKeyGestureDetector$KeyHandler"),
                "handleMessage", void.class, Message.class);
            if (!deoptimize(ruleDispatch) || !deoptimize(keyDispatch))
                throw new IllegalStateException("camera caller deopt rejected");
            var cameraHook = hookMethod(cameraPress, new IMethodHook() {
                @Override public void before(HookParam param) {
                    if (ScreenOffCamera.chordCooperative() || (Integer) param.getArgs()[2] != 2) return;
                    try { deferLegacyCamera(param, (Handler) cameraHandler.get(param.getThisObject())); }
                    catch (Throwable error) {
                        param.setResult(null);
                        XposedLog.w(TAG, "android", "Native camera chord arbitration rejected", error);
                    }
                }
            });
            if (cameraHook == null) throw new IllegalStateException("camera arbitration hook rejected");
            try {
            var handle = hookMethod(entry, new IMethodHook() {
                @Override public void before(HookParam param) {
                    if (route(param.getThisObject(), (KeyEvent) param.getArgs()[0], (Integer) param.getArgs()[1]))
                        param.setResult(0);
                }
            });
            if (handle == null) throw new IllegalStateException("queue hook rejected");
            } catch (Throwable error) { cameraHook.unhook(); throw error; }
            // Publish only after complete validation and successful hook installation.
            active = this;
            XposedLog.i(TAG, "android", "Installed single simultaneous volume chord; UP=gpio-keys DOWN=pmic_resin; slop=120ms; full release");
        } catch (Throwable error) {
            XposedLog.e(TAG, "android", "UNSUPPORTED volume video chord ABI; no alternate key hook", error);
        }
    }

    /** Recorder calls this before its own lock, making hook registration order irrelevant. */
    static boolean route(Object target, KeyEvent event, int flags) {
        VolumeVideoChord instance = active;
        return instance != null && instance.intercept(target, event, flags);
    }
    static long legacyToken() {
        VolumeVideoChord instance = active;
        if (instance == null) return 0;
        synchronized (instance.lock) { return instance.state.legacyGeneration(); }
    }
    static boolean legacyTokenValid(long token) { return token == legacyToken(); }

    /** OEM reaches count=2 on DOWN, before we know if the other key follows.
     * Hold only this ambiguous callback for the remaining slop, then re-enter the
     * normal camera hook. Do not retain a mutable HookParam across the callback. */
    static boolean deferLegacyCamera(HookParam param, Handler legacyHandler) {
        VolumeVideoChord instance = active;
        if (instance == null || instance.cameraReplay.get()) return false;
        synchronized (instance.lock) {
            long delay = instance.state.tentativeDeadline() - SystemClock.uptimeMillis();
            if (!instance.state.tentative() || delay < 0) return false;
            long token = instance.state.legacyGeneration(), gateEpoch = instance.epoch;
            Object rule = param.getThisObject(); Object[] args = param.getArgs().clone();
            try {
                int user = (Integer) instance.currentUser.invoke(null);
                Method callback = method(rule.getClass(), "onMiuiMultiPress", void.class,
                    Object.class, long.class, int.class, int.class);
                if (legacyHandler == null) { instance.diagnostic("reject/camera-handler"); return false; }
                boolean posted = legacyHandler.postDelayed(() -> {
                    // Keep the lock through callback acceptance: claim cannot slip
                    // between token validation and legacy request reservation.
                    synchronized (instance.lock) {
                        try {
                            if (token != instance.state.legacyGeneration() || gateEpoch != instance.epoch
                                || (Integer) instance.currentUser.invoke(null) != user) return;
                            instance.cameraReplay.set(true);
                            callback.invoke(rule, args);
                        } catch (Throwable error) {
                            instance.diagnostic("reject/camera-replay-exception");
                        } finally { instance.cameraReplay.remove(); }
                    }
                }, delay + 1);
                if (!posted) { instance.diagnostic("reject/camera-handler"); return false; }
                param.setResult(null);
            } catch (Throwable error) {
                instance.diagnostic("reject/camera-defer-exception"); return false;
            }
            return true;
        }
    }

    private boolean intercept(Object target, KeyEvent e, int flags) {
        synchronized (lock) {
            if (lastEvent == e && lastTarget == target && lastTime == e.getEventTime()
                && lastDown == e.getDownTime() && lastAction == e.getAction()
                && lastRepeat == e.getRepeatCount() && lastKey == e.getKeyCode() && lastFlags == flags
                && lastDevice == e.getDeviceId() && lastSource == e.getSource() && lastScan == e.getScanCode()
                && lastMeta == e.getMetaState() && lastEventFlags == e.getFlags()) return lastConsumed;
            boolean consumed = false;
            try {
                if (policy == null) {
                    context = (Context) contextField.get(target); handler = (Handler) handlerField.get(target);
                    if (context == null || handler == null) return false;
                    policy = target; installListeners();
                }
                if (policy != target) return false;
                int user = (Integer) currentUser.invoke(null);
                long now = SystemClock.uptimeMillis();
                long elapsedNow = SystemClock.elapsedRealtime();
                VolumeVideoChordState.Source board = boardSource(e.getKeyCode());
                InputDevice device = InputDevice.getDevice(e.getDeviceId());
                boolean role = board != null && board.device() == e.getDeviceId() && device != null
                    && board.descriptor().equals(device.getDescriptor());
                int scan = e.getKeyCode() == 24 ? 115 : e.getKeyCode() == 25 ? 114 : -1;
                boolean physical = role && scan == e.getScanCode() && e.getSource() == InputDevice.SOURCE_KEYBOARD
                    && (flags & (0x01000000 | 0x40000)) == 0;
                if ((e.getKeyCode() == 24 || e.getKeyCode() == 25) && !physical) {
                    diagnostic(!role ? "reject/board-role" : scan != e.getScanCode() ? "reject/scan"
                        : e.getSource() != InputDevice.SOURCE_KEYBOARD ? "reject/source" : "reject/injected-policy");
                }
                int display = (Integer) displayId.invoke(e);
                boolean clean = e.getMetaState() == 0 && (display == 0 || display == -1)
                    && (e.getFlags() & (0x01000000 | 0x400 | 0x40 | 0x20 | 0x80)) == 0
                    && e.getEventTime() <= now && now - e.getEventTime() <= FRESH_MS;
                boolean enabled = PrefsBridge.getBoolean(PREF, false);
                if (!enabled) diagnostic("reject/preference");
                if (pending >= 4) diagnostic("reject/pending-limit");
                boolean allowed = enabled && pending < 4 && liveAllowed(user);
                VolumeVideoChordState.Source source = new VolumeVideoChordState.Source(e.getDeviceId(),
                    device == null ? "" : device.getDescriptor(), e.getSource());
                int result = state.accept(new VolumeVideoChordState.Event(e.getKeyCode(), e.getAction(),
                    e.getRepeatCount(), e.getDownTime(), e.getEventTime(), source, physical, clean, user), allowed);
                consumed = (result & VolumeVideoChordState.CONSUME) != 0;
                if (state.rejection() != null) diagnostic("reject/" + state.rejection());
                if ((result & VolumeVideoChordState.CLAIM) != 0) {
                    // The second DOWN may be pmic_resin. Recorder owns gpio-keys UP.
                    ScreenOffRecorder.cancelForVideoChord(state.volumeUpDevice(), state.volumeDownDevice());
                    diagnostic("accepted/physical-pair");
                    // First DOWN may already be in the OEM single-key detector.
                    // Reset its pending count/long-press messages before swallowing UP.
                    Object detector = detectorField.get(target);
                    if (detector == null) throw new IllegalStateException("single-key detector missing");
                    reset.invoke(detector);
                }
                if ((result & VolumeVideoChordState.TRIGGER) != 0) {
                    diagnostic("accepted/full-release");
                    long token = state.cancellation(), gateEpoch = epoch;
                    String request = UUID.randomUUID().toString();
                    // Use the completed physical event, not input-queue delivery time:
                    // a 9.9s gesture delivered at 10.1s must still be ignored.
                    long requestedAt = elapsedNow - (now - e.getEventTime());
                    pending++;
                    try {
                        if (!handler.post(() -> dispatch(user, token, gateEpoch, request, requestedAt, now))) {
                            pending--; diagnostic("reject/handler");
                        }
                    } catch (Throwable error) { pending--; throw error; }
                }
            } catch (Throwable error) {
                state.cancel();
                // Policy/user/service lookups may fail after DOWN ownership was
                // transferred. Still swallow the exact matching physical release.
                try {
                    InputDevice d = InputDevice.getDevice(e.getDeviceId());
                    int scan = e.getKeyCode() == 24 ? 115 : e.getKeyCode() == 25 ? 114 : -1;
                    if (e.getAction() == KeyEvent.ACTION_UP && d != null && !d.isVirtual() && !d.isExternal()
                        && e.getScanCode() == scan && e.getSource() == InputDevice.SOURCE_KEYBOARD
                        && (flags & (0x01000000 | 0x40000)) == 0)
                        consumed |= state.drainCancelledRelease(e.getKeyCode(),
                            new VolumeVideoChordState.Source(e.getDeviceId(), d.getDescriptor(), e.getSource()), e.getDownTime());
                } catch (Throwable ignored) { /* Device removal listener reclaims vanished roles. */ }
                diagnostic("reject/key-exception");
            }
            lastEvent = e; lastTarget = target; lastTime = e.getEventTime(); lastDown = e.getDownTime();
            lastAction = e.getAction(); lastRepeat = e.getRepeatCount(); lastKey = e.getKeyCode(); lastFlags = flags;
            lastDevice = e.getDeviceId(); lastSource = e.getSource(); lastScan = e.getScanCode();
            lastMeta = e.getMetaState(); lastEventFlags = e.getFlags(); lastConsumed = consumed;
            return consumed;
        }
    }

    private void installListeners() throws ReflectiveOperationException {
        if (listeners) return;
        InputManager inputs = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        if (inputs == null) throw new IllegalStateException("InputManager missing");
        inputs.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            public void onInputDeviceAdded(int id) { changed(id); }
            public void onInputDeviceChanged(int id) { changed(id); }
            public void onInputDeviceRemoved(int id) { changed(id); }
            private void changed(int id) { synchronized (lock) {
                state.deviceChanged(id); epoch++; lastEvent = null; diagnostic("cancel/device-change");
            } }
        }, handler);
        IntentFilter users = new IntentFilter("android.intent.action.USER_SWITCHED");
        users.addAction(Intent.ACTION_USER_UNLOCKED);
        registerReceiver.invoke(context, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                synchronized (lock) { state.cancel(); epoch++; lastEvent = null; diagnostic("cancel/user-change"); }
            }
        }, userOf.invoke(null, -1), users, null, handler, Context.RECEIVER_NOT_EXPORTED);
        listeners = true;
    }

    private boolean liveAllowed(int user) throws ReflectiveOperationException {
        UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
        Object dpManager = dp.get(policy), frontSensor = front.get(policy), backSensor = back.get(policy);
        String reason = user < 0 ? "unknown-user" : !boot.getBoolean(policy) ? "not-booted"
            : users == null || !Boolean.TRUE.equals(unlocked.invoke(users, userOf.invoke(null, user))) ? "user-locked"
            : enterprise.getBoolean(null) ? "enterprise"
            : !Boolean.FALSE.equals(waterActive.invoke(waterInstance.invoke(null))) ? "underwater"
            : dpManager == null || !Boolean.FALSE.equals(dpMode.invoke(dpManager)) ? "dp-mode"
            : frontSensor != null && !Boolean.FALSE.equals(held.invoke(frontSensor)) ? "front-proximity"
            : backSensor != null && !Boolean.FALSE.equals(held.invoke(backSensor)) ? "back-proximity"
            : (Integer) currentUser.invoke(null) != user ? "current-user-changed" : null;
        if (reason != null) diagnostic("reject/" + reason);
        return reason == null;
    }

    private void dispatch(int user, long token, long gateEpoch, String request, long requestedAt, long queuedAt) {
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (lock) {
                if (!dispatchAllowed(user, token, gateEpoch, queuedAt)) return;
                Object privacy = privacyInstance.invoke(null, context);
                if (privacy == null || !Boolean.FALSE.equals(privacyAll.invoke(privacy))
                    || !Boolean.FALSE.equals(privacyCombined.invoke(privacy, 2))
                    || !Boolean.FALSE.equals(privacyCombined.invoke(privacy, 1))) {
                    diagnostic("reject/sensor-privacy"); return;
                }
                if (!dispatchAllowed(user, token, gateEpoch, queuedAt)) return;
                Intent intent = new Intent(MODULE + ".action.VOLUME_VIDEO_TOGGLE")
                    .setComponent(new ComponentName(MODULE, MODULE + ".video.VolumeVideoService"))
                    .putExtra("request_id", request).putExtra("requested_at", requestedAt).putExtra("request_user", user)
                    .putExtra("hyperceiler.sensor_privacy_checked", true)
                    .putExtra("hyperceiler.sensor_privacy_checked_at", SystemClock.elapsedRealtime())
                    .putExtra("hyperceiler.microphone_privacy_checked", true)
                    .putExtra("hyperceiler.microphone_privacy_checked_at", SystemClock.elapsedRealtime());
                if (startService.invoke(context, intent, userOf.invoke(null, user)) == null)
                    diagnostic("reject/service-absent");
                else diagnostic("dispatched/foreground-service");
            }
        } catch (Throwable error) {
            synchronized (lock) { diagnostic("reject/dispatch-exception"); }
        } finally {
            Binder.restoreCallingIdentity(identity);
            synchronized (lock) { pending--; }
        }
    }
    private boolean dispatchAllowed(int user, long token, long gateEpoch, long queuedAt) throws ReflectiveOperationException {
        String reason = !PrefsBridge.getBoolean(PREF, false) ? "preference"
            : token != state.cancellation() ? "cancelled"
            : gateEpoch != epoch ? "epoch"
            : SystemClock.uptimeMillis() < queuedAt || SystemClock.uptimeMillis() - queuedAt > FRESH_MS ? "queue-expired"
            : !liveAllowed(user) ? "live-user-gate" : null;
        if (reason != null) diagnostic("reject/dispatch-" + reason);
        return reason == null;
    }
    private Class<?> required(String name) throws ClassNotFoundException {
        Class<?> cls = findClassIfExists(name, getClassLoader());
        if (cls == null) throw new ClassNotFoundException(name);
        return cls;
    }
    private static Field field(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        for (Class<?> cls = owner; cls != null; cls = cls.getSuperclass()) {
            try {
                Field f = cls.getDeclaredField(name);
                if (type != null && f.getType() != type) throw new NoSuchFieldException(name + " type");
                f.setAccessible(true); return f;
            } catch (NoSuchFieldException error) { if (cls.getSuperclass() == null) throw error; }
        }
        throw new NoSuchFieldException(name);
    }
    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... args)
        throws ReflectiveOperationException {
        Method m = owner.getDeclaredMethod(name, args);
        if (m.getReturnType() != result) throw new NoSuchMethodException(owner.getName() + "." + name);
        m.setAccessible(true); return m;
    }
}
