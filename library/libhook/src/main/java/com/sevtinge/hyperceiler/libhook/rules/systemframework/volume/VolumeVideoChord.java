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
    private Method currentUser, userOf, unlocked, startService, external, displayId, reset;
    private Method privacyInstance, privacyAll, privacyCombined, waterInstance, waterActive, dpMode, held;
    private Method registerReceiver;
    // Both callbacks see the same immutable queue-event view; never run accept twice.
    private KeyEvent lastEvent;
    private Object lastTarget;
    private long lastTime, lastDown;
    private int lastAction, lastRepeat, lastKey, lastFlags;
    private boolean lastConsumed;

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
            external = method(InputDevice.class, "isExternal", boolean.class);
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
            XposedLog.i(TAG, "android", "Installed physical volume double-chord video entry");
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
            param.setResult(null);
            long token = instance.state.legacyGeneration(), gateEpoch = instance.epoch;
            Object rule = param.getThisObject(); Object[] args = param.getArgs().clone();
            try {
                int user = (Integer) instance.currentUser.invoke(null);
                Method callback = method(rule.getClass(), "onMiuiMultiPress", void.class,
                    Object.class, long.class, int.class, int.class);
                if (legacyHandler == null || !legacyHandler.postDelayed(() -> {
                    // Keep the lock through callback acceptance: claim cannot slip
                    // between token validation and legacy request reservation.
                    synchronized (instance.lock) {
                        try {
                            if (token != instance.state.legacyGeneration() || gateEpoch != instance.epoch
                                || (Integer) instance.currentUser.invoke(null) != user) return;
                            instance.cameraReplay.set(true);
                            callback.invoke(rule, args);
                        } catch (Throwable error) {
                            XposedLog.w(instance.TAG, "android", "Deferred volume camera callback rejected", error);
                        } finally { instance.cameraReplay.remove(); }
                    }
                }, delay + 1)) XposedLog.w(instance.TAG, "android", "Camera chord arbitration Handler rejected callback");
            } catch (Throwable error) {
                XposedLog.w(instance.TAG, "android", "Camera chord arbitration rejected callback", error);
            }
            return true;
        }
    }

    private boolean intercept(Object target, KeyEvent e, int flags) {
        synchronized (lock) {
            if (lastEvent == e && lastTarget == target && lastTime == e.getEventTime()
                && lastDown == e.getDownTime() && lastAction == e.getAction()
                && lastRepeat == e.getRepeatCount() && lastKey == e.getKeyCode() && lastFlags == flags) return lastConsumed;
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
                InputDevice device = InputDevice.getDevice(e.getDeviceId());
                boolean board = device != null && "gpio-keys".equals(device.getName()) && !device.isVirtual()
                    && Boolean.FALSE.equals(external.invoke(device)) && device.supportsSource(InputDevice.SOURCE_KEYBOARD)
                    && device.getDescriptor() != null && !device.getDescriptor().isEmpty();
                int scan = e.getKeyCode() == 24 ? 115 : e.getKeyCode() == 25 ? 114 : -1;
                boolean physical = board && scan == e.getScanCode() && e.getSource() == InputDevice.SOURCE_KEYBOARD
                    && (flags & (0x01000000 | 0x40000)) == 0;
                int display = (Integer) displayId.invoke(e);
                boolean clean = e.getMetaState() == 0 && (display == 0 || display == -1)
                    && (e.getFlags() & (0x01000000 | 0x400 | 0x40 | 0x20 | 0x80)) == 0
                    && e.getEventTime() <= now && now - e.getEventTime() <= FRESH_MS;
                boolean allowed = PrefsBridge.getBoolean(PREF, false) && liveAllowed(user) && pending < 4;
                VolumeVideoChordState.Source source = new VolumeVideoChordState.Source(e.getDeviceId(),
                    board ? device.getDescriptor() : "", e.getSource());
                int result = state.accept(new VolumeVideoChordState.Event(e.getKeyCode(), e.getAction(),
                    e.getRepeatCount(), e.getDownTime(), e.getEventTime(), source, physical, clean, user), allowed);
                consumed = (result & VolumeVideoChordState.CONSUME) != 0;
                if ((result & VolumeVideoChordState.CLAIM) != 0) {
                    ScreenOffRecorder.cancelForVideoChord(e.getDeviceId());
                    // First DOWN may already be in the OEM single-key detector.
                    // Reset its pending count/long-press messages before swallowing UP.
                    Object detector = detectorField.get(target);
                    if (detector == null) throw new IllegalStateException("single-key detector missing");
                    reset.invoke(detector);
                }
                if ((result & VolumeVideoChordState.TRIGGER) != 0) {
                    long token = state.cancellation(), gateEpoch = epoch;
                    String request = UUID.randomUUID().toString();
                    // Use the completed physical event, not input-queue delivery time:
                    // a 9.9s gesture delivered at 10.1s must still be ignored.
                    long requestedAt = elapsedNow - (now - e.getEventTime());
                    pending++;
                    try {
                        if (!handler.post(() -> dispatch(user, token, gateEpoch, request, requestedAt, now))) pending--;
                    } catch (Throwable error) { pending--; throw error; }
                }
            } catch (Throwable error) {
                state.cancel();
                XposedLog.w(TAG, "android", "Video chord key rejected", error);
            }
            lastEvent = e; lastTarget = target; lastTime = e.getEventTime(); lastDown = e.getDownTime();
            lastAction = e.getAction(); lastRepeat = e.getRepeatCount(); lastKey = e.getKeyCode(); lastFlags = flags;
            lastConsumed = consumed;
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
            private void changed(int id) { synchronized (lock) { state.deviceChanged(id); epoch++; } }
        }, handler);
        IntentFilter users = new IntentFilter("android.intent.action.USER_SWITCHED");
        users.addAction(Intent.ACTION_USER_UNLOCKED);
        registerReceiver.invoke(context, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                synchronized (lock) { state.cancel(); epoch++; }
            }
        }, userOf.invoke(null, -1), users, null, handler, Context.RECEIVER_NOT_EXPORTED);
        listeners = true;
    }

    private boolean liveAllowed(int user) throws ReflectiveOperationException {
        UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
        Object dpManager = dp.get(policy), frontSensor = front.get(policy), backSensor = back.get(policy);
        return user >= 0 && boot.getBoolean(policy) && users != null
            && Boolean.TRUE.equals(unlocked.invoke(users, userOf.invoke(null, user)))
            && !enterprise.getBoolean(null) && Boolean.FALSE.equals(waterActive.invoke(waterInstance.invoke(null)))
            && dpManager != null && Boolean.FALSE.equals(dpMode.invoke(dpManager))
            && (frontSensor == null || Boolean.FALSE.equals(held.invoke(frontSensor)))
            && (backSensor == null || Boolean.FALSE.equals(held.invoke(backSensor)))
            && (Integer) currentUser.invoke(null) == user;
    }

    private void dispatch(int user, long token, long gateEpoch, String request, long requestedAt, long queuedAt) {
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (lock) {
                if (!dispatchAllowed(user, token, gateEpoch, queuedAt)) return;
                Object privacy = privacyInstance.invoke(null, context);
                if (privacy == null || !Boolean.FALSE.equals(privacyAll.invoke(privacy))
                    || !Boolean.FALSE.equals(privacyCombined.invoke(privacy, 2))
                    || !Boolean.FALSE.equals(privacyCombined.invoke(privacy, 1))) return;
                if (!dispatchAllowed(user, token, gateEpoch, queuedAt)) return;
                Intent intent = new Intent(MODULE + ".action.VOLUME_VIDEO_TOGGLE")
                    .setComponent(new ComponentName(MODULE, MODULE + ".video.VolumeVideoService"))
                    .putExtra("request_id", request).putExtra("requested_at", requestedAt).putExtra("request_user", user)
                    .putExtra("hyperceiler.sensor_privacy_checked", true)
                    .putExtra("hyperceiler.sensor_privacy_checked_at", SystemClock.elapsedRealtime())
                    .putExtra("hyperceiler.microphone_privacy_checked", true)
                    .putExtra("hyperceiler.microphone_privacy_checked_at", SystemClock.elapsedRealtime());
                if (startService.invoke(context, intent, userOf.invoke(null, user)) == null)
                    XposedLog.w(TAG, "android", "Video service absent for current user");
            }
        } catch (Throwable error) {
            XposedLog.w(TAG, "android", "Video chord FGS dispatch rejected", error);
        } finally {
            Binder.restoreCallingIdentity(identity);
            synchronized (lock) { pending--; }
        }
    }
    private boolean dispatchAllowed(int user, long token, long gateEpoch, long queuedAt) throws ReflectiveOperationException {
        return PrefsBridge.getBoolean(PREF, false) && token == state.cancellation() && gateEpoch == epoch
            && SystemClock.uptimeMillis() - queuedAt <= FRESH_MS && liveAllowed(user);
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
