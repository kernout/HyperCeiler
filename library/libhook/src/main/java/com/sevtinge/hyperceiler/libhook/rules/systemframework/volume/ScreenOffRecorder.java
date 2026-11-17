/* Copyright (C) 2026 HyperCeiler Contributions */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Dedicated physical volume-UP entry; volume-DOWN and shared detectors remain OEM owned. */
public final class ScreenOffRecorder extends BaseHook {
    private static final String PREF = "system_framework_volume_screen_off_recorder";
    private static final String MODULE = "com.sevtinge.hyperceiler";
    private static final String ACTION = MODULE + ".action.SCREEN_OFF_RECORDING_TOGGLE";
    private static final String START = MODULE + ".action.SCREEN_OFF_RECORDING_START_ONLY";
    private static final String STOP = MODULE + ".action.SCREEN_OFF_RECORDING_STOP_IF_RUNNING";
    private static final long FRESH_MS = 400;
    private static final int MAX_PENDING = 4;
    private final ScreenOffRecorderState state = new ScreenOffRecorderState(
        ViewConfiguration.getMultiPressTimeout(), ViewConfiguration.getLongPressTimeout());
    private boolean refreshPending;
    private final String requestSession = UUID.randomUUID().toString();
    private final Object lock = new Object();
    private final Runnable candidateExpiry = () -> {
        synchronized (lock) { state.cancel(); }
    };
    private volatile Gate gate;
    private volatile Map<Integer, ScreenOffRecorderState.Source> sourceCache = Map.of();
    private volatile boolean ready;
    private int pendingDispatches;
    private long epoch, callbackEpoch;
    private Context context;
    private Handler handler;
    private Object policy;
    private Field contextField, handlerField, bootField, enterprise, dpField, proximity, backProximity;
    private Method currentUser, userOf, unlocked, startService, startStopService, external, displayId;
    private Method privacyInstance, privacyAll, privacyCombined, underwaterInstance, underwaterActive;
    private Method dpMode, proximityHeld, registerReceiverAsUser;
    private record Gate(boolean allowed, boolean interactive, int user, long epoch,
                        Map<Integer, ScreenOffRecorderState.Source> sources) {}

    @Override public void init() {
        if (!PrefsBridge.getBoolean(PREF, false)) return;
        try {
            Class<?> owner = required("com.android.server.policy.MiuiPhoneWindowManager");
            Method entry = method(owner, "interceptKeyBeforeQueueing", int.class, KeyEvent.class, int.class);
            contextField = inherited(owner, "mContext", Context.class);
            handlerField = inherited(owner, "mHandler", Handler.class);
            bootField = inherited(owner, "mSystemBooted", boolean.class);
            dpField = inherited(owner, "mMiuiInputManagerInternal", null);
            dpMode = method(dpField.getType(), "isDpInMonitorMode", boolean.class);
            proximity = inherited(owner, "mProximitySensor", null);
            backProximity = inherited(owner, "mBackScreenProximitySensor", proximity.getType());
            proximityHeld = method(proximity.getType(), "isHeld", boolean.class);
            enterprise = inherited(required("miui.enterprise.EnterpriseManagerStub"), "ENTERPRISE_ACTIVATED", boolean.class);
            Class<?> water = required("com.android.server.input.UnderwaterInputController");
            underwaterInstance = method(water, "getInstance", water);
            underwaterActive = method(water, "isActive", boolean.class);
            currentUser = method(ActivityManager.class, "getCurrentUser", int.class);
            userOf = method(UserHandle.class, "of", UserHandle.class, int.class);
            unlocked = method(UserManager.class, "isUserUnlocked", boolean.class, UserHandle.class);
            startService = method(Context.class, "startForegroundServiceAsUser", ComponentName.class, Intent.class, UserHandle.class);
            startStopService = method(Context.class, "startServiceAsUser", ComponentName.class, Intent.class, UserHandle.class);
            registerReceiverAsUser = method(Context.class, "registerReceiverAsUser", Intent.class,
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
            if (!deoptimize(callback) || !deoptimize(upstream)) throw new IllegalStateException("queueing caller deopt rejected");
            Method boot = method(owner, "systemBooted", void.class);
            var entryHook = hookMethod(entry, new IMethodHook() {
                @Override public void before(HookParam param) { intercept(param); }
            });
            if (entryHook == null) throw new IllegalStateException("entry hook rejected");
            try {
                if (hookMethod(boot, new IMethodHook() {
                    @Override public void after(HookParam param) {
                        synchronized (lock) { bootstrap(param.getThisObject()); }
                    }
                }) == null) throw new IllegalStateException("boot hook rejected");
            } catch (Throwable error) { entryHook.unhook(); throw error; }
            ready = true;
            XposedLog.i(TAG, "android", "Installed physical screen-off volume-up recorder entry");
        } catch (Throwable error) {
            ready = false;
            XposedLog.e(TAG, "android", "UNSUPPORTED screen-off recorder ABI; no alternate key hook", error);
        }
    }

    private void intercept(HookParam param) {
        if (!ready) return;
        try {
            synchronized (lock) {
                Object target = param.getThisObject();
                bootstrap(target);
                if (policy != target) return;
                KeyEvent e = (KeyEvent) param.getArgs()[0];
                int flags = (Integer) param.getArgs()[1];
                long now = SystemClock.uptimeMillis();
                Gate g = gate;
                boolean enabled = PrefsBridge.getBoolean(PREF, false);
                if (!enabled) invalidate();
                // Event-driven eligibility has no idle TTL. Only callbacks invalidate it;
                // event/queue freshness and live gates are checked separately below.
                boolean allowed = enabled && g != null && g.epoch == epoch && g.allowed
                    && (flags & 0x20000000) == 0 && bootField.getBoolean(target);
                if (enabled && e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                    && g == null) requestRefresh();
                if (enabled && pendingDispatches >= MAX_PENDING && !state.isCandidate()
                    && e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP && e.getAction() == KeyEvent.ACTION_DOWN) {
                    XposedLog.w(TAG, "android", "Recorder gesture queue full; new gesture left to OEM");
                    return;
                }
                ScreenOffRecorderState.Source s = sourceCache.get(e.getDeviceId());
                boolean trusted = s != null && e.getScanCode() == s.scan()
                    && e.getSource() == s.source() && e.getMetaState() == 0
                    && (flags & (0x01000000 | 0x40000)) == 0
                    && (e.getFlags() & (0x01000000 | 0x400 | 0x40 | 0x20 | 0x80)) == 0
                    && ((Integer) displayId.invoke(e) == 0 || (Integer) displayId.invoke(e) == -1)
                    && e.getEventTime() <= now && now - e.getEventTime() <= FRESH_MS;
                s = new ScreenOffRecorderState.Source(e.getDeviceId(), s == null ? "" : s.descriptor(),
                    e.getSource(), e.getScanCode());
                int result = state.accept(new ScreenOffRecorderState.Event(e.getKeyCode(), e.getAction(),
                    e.getRepeatCount(), e.getDownTime(), e.getEventTime(), s, trusted), allowed,
                    g == null ? -1 : g.epoch, g != null && g.interactive);
                if ((result & ScreenOffRecorderState.CONSUME) != 0) param.setResult(0);
                handler.removeCallbacks(candidateExpiry);
                if (enabled && state.isCandidate()) handler.postDelayed(candidateExpiry,
                    Math.max(0, state.candidateDeadline() - now));
                int action = result & (ScreenOffRecorderState.TRIGGER
                    | ScreenOffRecorderState.STOP_IF_RUNNING | ScreenOffRecorderState.START_ONLY);
                if (action != 0) {
                    long gesture = state.generation(), cancellation = state.cancellation();
                    pendingDispatches++;
                    try {
                        String burstId = requestSession + ":" + gesture;
                        String requestId = burstId + ":" + action;
                        if (!handler.post(() -> dispatch(g, cancellation, requestId, burstId, action, now))) {
                            pendingDispatches--;
                            XposedLog.w(TAG, "android", "Recorder Handler rejected completed gesture");
                        }
                    } catch (Throwable error) { pendingDispatches--; throw error; }
                }
            }
        } catch (Throwable error) {
            synchronized (lock) { state.cancel(); if (handler != null) handler.removeCallbacks(candidateExpiry); }
            XposedLog.w(TAG, "android", "Recorder key recognition rejected", error);
        }
    }

    private void bootstrap(Object target) {
        if (policy != null) return;
        try {
            context = (Context) contextField.get(target);
            handler = (Handler) handlerField.get(target);
            if (context == null || handler == null) return;
            policy = target;
            requestRefresh();
        } catch (Throwable error) { XposedLog.w(TAG, "android", "Recorder bootstrap unavailable", error); }
    }

    private void requestRefresh() {
        if (!PrefsBridge.getBoolean(PREF, false)) { invalidate(); return; }
        final long refreshEpoch, refreshCallbackEpoch;
        synchronized (lock) {
            if (refreshPending) return;
            refreshPending = true;
            refreshEpoch = epoch;
            refreshCallbackEpoch = callbackEpoch;
        }
        try {
            if (!handler.post(() -> {
                long identity = Binder.clearCallingIdentity();
                boolean retry;
                try {
                    if (!PrefsBridge.getBoolean(PREF, false)) { invalidate(); return; }
                    installListeners();
                    Gate next = inspect();
                    synchronized (lock) {
                        // A callback during inspection invalidates this snapshot, even
                        // if a coalesced refresh request could not post another task.
                        if (epoch != refreshEpoch) return;
                        Gate old = gate;
                        if (old == null || old.user != next.user || old.allowed != next.allowed
                            || old.interactive != next.interactive
                            || !old.sources.equals(next.sources)) {
                            epoch++; state.cancel(); handler.removeCallbacks(candidateExpiry);
                        }
                        sourceCache = next.sources;
                        gate = new Gate(next.allowed, next.interactive, next.user, epoch, next.sources);
                    }
                } catch (Throwable error) {
                    invalidate();
                    XposedLog.w(TAG, "android", "Recorder gate unavailable", error);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                    synchronized (lock) {
                        refreshPending = false;
                        retry = gate == null && callbackEpoch != refreshCallbackEpoch;
                    }
                    // Finite event-driven catch-up only; an inspection failure must
                    // not create a self-sustaining refresh loop.
                    if (retry) requestRefresh();
                }
            })) {
                synchronized (lock) { refreshPending = false; }
            }
        } catch (Throwable error) {
            synchronized (lock) { refreshPending = false; }
            throw error;
        }
    }

    private boolean listeners;
    private void installListeners() throws ReflectiveOperationException {
        if (listeners) return;
        DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        InputManager inputs = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        if (displays == null || inputs == null) throw new IllegalStateException("Input/display manager missing");
        displays.registerDisplayListener(new DisplayManager.DisplayListener() {
            public void onDisplayAdded(int id) { changed(); }
            public void onDisplayRemoved(int id) { changed(); }
            public void onDisplayChanged(int id) { changed(); }
            private void changed() { gatesChanged(); }
        }, handler);
        inputs.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            public void onInputDeviceAdded(int id) { changed(id); }
            public void onInputDeviceRemoved(int id) { changed(id); }
            public void onInputDeviceChanged(int id) { changed(id); }
            private void changed(int id) {
                synchronized (lock) { state.deviceChanged(id); }
                gatesChanged();
            }
        }, handler);
        IntentFilter users = new IntentFilter("android.intent.action.USER_SWITCHED");
        users.addAction(Intent.ACTION_USER_UNLOCKED);
        registerReceiverAsUser.invoke(context, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { gatesChanged(); }
        }, userOf.invoke(null, -1), users, null, handler, Context.RECEIVER_NOT_EXPORTED);
        listeners = true;
    }
    private void gatesChanged() {
        synchronized (lock) { callbackEpoch++; invalidate(); }
        requestRefresh();
    }
    private void invalidate() {
        synchronized (lock) {
            epoch++; gate = null; state.cancel();
            if (handler != null) handler.removeCallbacks(candidateExpiry);
        }
    }

    private Gate inspect() throws ReflectiveOperationException {
        int user = (Integer) currentUser.invoke(null);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
        Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        int screen = display == null ? Display.STATE_UNKNOWN : display.getState();
        boolean off = screen == Display.STATE_OFF || screen == Display.STATE_DOZE || screen == Display.STATE_DOZE_SUSPEND;
        boolean interactive = power != null && power.isInteractive();
        boolean stableScreen = power != null && (interactive ? screen == Display.STATE_ON : off);
        Object dp = dpField.get(policy), front = proximity.get(policy), back = backProximity.get(policy);
        boolean allowed = PrefsBridge.getBoolean(PREF, false) && bootField.getBoolean(policy)
            && user >= 0 && users != null && Boolean.TRUE.equals(unlocked.invoke(users, userOf.invoke(null, user)))
            && stableScreen
            && !enterprise.getBoolean(null) && !Boolean.TRUE.equals(underwaterActive.invoke(underwaterInstance.invoke(null)))
            && dp != null && Boolean.FALSE.equals(dpMode.invoke(dp))
            && (front == null || Boolean.FALSE.equals(proximityHeld.invoke(front)))
            && (back == null || Boolean.FALSE.equals(proximityHeld.invoke(back)));
        Map<Integer, ScreenOffRecorderState.Source> sources = new HashMap<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            // Explicit board whitelist: gpio-keys scan 115 only. Never trust uinput or an ID.
            if (device != null && "gpio-keys".equals(device.getName()) && !device.isVirtual()
                && Boolean.FALSE.equals(external.invoke(device)) && device.supportsSource(InputDevice.SOURCE_KEYBOARD)
                && device.getDescriptor() != null && !device.getDescriptor().isEmpty()) {
                sources.put(id, new ScreenOffRecorderState.Source(id, device.getDescriptor(), InputDevice.SOURCE_KEYBOARD, 115));
            }
        }
        if ((Integer) currentUser.invoke(null) != user) allowed = false;
        return new Gate(allowed, interactive, user, 0, Map.copyOf(sources));
    }

    private void dispatch(Gate candidate, long token, String requestId, String burstId, int action, long queuedAt) {
        long identity = Binder.clearCallingIdentity();
        try {
            Gate fresh = inspect();
            synchronized (lock) {
                if (gate == null || candidate.epoch != gate.epoch || token != state.cancellation()
                    || !fresh.allowed || fresh.interactive != candidate.interactive || fresh.user != candidate.user || !fresh.sources.equals(candidate.sources)
                    || SystemClock.uptimeMillis() - queuedAt > FRESH_MS) return;
            }
            boolean stopping = action == ScreenOffRecorderState.STOP_IF_RUNNING;
            boolean blocked = false;
            long checkedAt = 0;
            if (!stopping) {
                Object privacy = privacyInstance.invoke(null, context);
                if (privacy == null) throw new IllegalStateException("SensorPrivacyManager missing");
                blocked = (Boolean) privacyAll.invoke(privacy) || (Boolean) privacyCombined.invoke(privacy, 1);
                checkedAt = SystemClock.elapsedRealtime();
                // A denied third tap must not accidentally stop a recording started elsewhere.
                if (blocked && action == ScreenOffRecorderState.START_ONLY) return;
            }
            Gate finalGate = inspect();
            synchronized (lock) {
                if (gate == null || gate.epoch != candidate.epoch || token != state.cancellation()
                    || !finalGate.allowed || finalGate.interactive != candidate.interactive || finalGate.user != candidate.user
                    || !finalGate.sources.equals(candidate.sources)
                    || SystemClock.uptimeMillis() - queuedAt > FRESH_MS) return;
            }
            String command = stopping || blocked ? STOP
                : action == ScreenOffRecorderState.START_ONLY ? START : ACTION;
            Intent intent = new Intent(command)
                .setComponent(new ComponentName(MODULE, MODULE + ".recording.ScreenOffRecordingService"))
                .putExtra("hyperceiler.recording_request_id", requestId)
                .putExtra("hyperceiler.recording_burst_id", burstId)
                .putExtra("hyperceiler.recording_interactive", candidate.interactive);
            if (!stopping && !blocked) intent.putExtra("hyperceiler.microphone_privacy_checked", true)
                .putExtra("hyperceiler.microphone_privacy_checked_at", checkedAt);
            // An idle second tap is an ordinary, immediately retired service start: no
            // foreground promotion/5-second deadline or transient recording notification.
            Method launcher = STOP.equals(command) ? startStopService : startService;
            if (launcher.invoke(context, intent, userOf.invoke(null, candidate.user)) == null)
                XposedLog.w(TAG, "android", "Recorder service absent for current user");
        } catch (Throwable error) {
            XposedLog.w(TAG, "android", "Screen-off recorder dispatch rejected", error);
        } finally {
            Binder.restoreCallingIdentity(identity);
            synchronized (lock) { pendingDispatches--; }
        }
    }

    private Class<?> required(String name) throws ClassNotFoundException {
        Class<?> cls = findClassIfExists(name, getClassLoader());
        if (cls == null) throw new ClassNotFoundException(name);
        return cls;
    }
    private static Field inherited(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        for (Class<?> cls = owner; cls != null; cls = cls.getSuperclass()) {
            try {
                Field field = cls.getDeclaredField(name);
                if (type != null && field.getType() != type) throw new NoSuchFieldException(name + " type");
                field.setAccessible(true); return field;
            } catch (NoSuchFieldException error) {
                if (cls.getSuperclass() == null) throw error;
            }
        }
        throw new NoSuchFieldException(name);
    }
    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... args)
        throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, args);
        if (method.getReturnType() != result) throw new NoSuchMethodException(owner.getName() + "." + name);
        method.setAccessible(true); return method;
    }
}
