/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.securitycore;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** SecurityCoreAdd native selector only; system_server owns recording execution. */
public final class PowerDoubleClickRecorder extends BaseHook {
    private static final String ENABLED = "system_framework_volume_screen_off_recorder";
    private static final String FUNCTION = "hyperceiler_recording_toggle";
    private static final String ACTION = "double_click_power_key";
    private final List<WeakReference<Object>> owners = new ArrayList<>();
    private Method screen, context, find, add, remove, at, key, order, setOrder;
    private Method setKey, setTitle, persistent, visible, enabled, setListener;
    private Field children, choices;
    private Constructor<?> singleChoice;

    @Override public void init() {
        if (!active()) return;
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            // Raw DEX names, not JADX's p030f2 / p098q1 display aliases. Validate the
            // entire required ABI before installing anything or changing a host tree.
            Class<?> parent = required("f2.h"), power = required("j2.b");
            Class<?> feature = required("f2.t"), labels = required("q1.l");
            Class<?> pref = required("androidx.preference.Preference");
            Class<?> group = required("androidx.preference.PreferenceGroup");
            Class<?> prefScreen = required("androidx.preference.PreferenceScreen");
            Class<?> single = required("miuix.preference.SingleChoicePreference");
            Class<?> listener = required("androidx.preference.o");
            if (!pref.isAssignableFrom(single) || !group.isAssignableFrom(prefScreen)) {
                throw new NoSuchMethodException("Unexpected native preference hierarchy");
            }
            Method map = declared(feature, "j", HashMap.class, int.class);
            Method label = declared(labels, "c", String.class, Context.class, String.class);
            if (!Modifier.isStatic(label.getModifiers())) throw new NoSuchMethodException("q1.l.c must be static");
            Method populate = declared(power, "p", void.class, group);
            Method select = declared(power, "q", boolean.class, pref, Object.class, boolean.class);
            declared(power, "r", void.class, String.class, String.class, String.class);
            declared(power, "onCreate", void.class, Bundle.class);
            Method resume = declared(power, "onResume", void.class);
            declared(parent, "onCreatePreferences", void.class, Bundle.class, String.class);
            declared(parent, "o", void.class);
            field(parent, "h", HashMap.class);
            field(power, "i", HashMap.class);
            choices = field(power, "f", ArrayList.class);
            children = field(group, "c", ArrayList.class);
            screen = method(power, "getPreferenceScreen", prefScreen);
            context = method(power, "requireContext", Context.class);
            find = method(group, "h", pref, CharSequence.class);
            add = method(group, "g", boolean.class, pref);
            remove = method(group, "k", boolean.class, pref);
            at = method(group, "i", pref, int.class);
            key = method(pref, "getKey", String.class);
            order = method(pref, "getOrder", int.class);
            setOrder = method(pref, "setOrder", void.class, int.class);
            setKey = method(pref, "setKey", void.class, String.class);
            setTitle = method(pref, "setTitle", void.class, CharSequence.class);
            persistent = method(pref, "setPersistent", void.class, boolean.class);
            visible = method(pref, "setVisible", void.class, boolean.class);
            enabled = method(pref, "setEnabled", void.class, boolean.class);
            setListener = method(pref, "setOnPreferenceChangeListener", void.class, listener);
            method(single, "setChecked", void.class, boolean.class);
            singleChoice = single.getConstructor(Context.class);

            handles.add(hookMethod(map, new IMethodHook() {
                @Override public void after(HookParam param) {
                    if (!active() || param.getThrowable() != null || !Integer.valueOf(1).equals(param.getArgs()[0])) return;
                    try {
                        if (!(param.getResult() instanceof HashMap<?, ?> original)) return;
                        HashMap<Object, Object> copy = new HashMap<>(original);
                        Object previous = original.get(FUNCTION);
                        List<Object> actions = new ArrayList<>();
                        if (previous instanceof List<?> values) actions.addAll(values);
                        if (!actions.contains(ACTION)) actions.add(ACTION);
                        copy.put(FUNCTION, actions);
                        param.setResult(copy);
                    } catch (Throwable error) { failure("support map", error); }
                }
            }));
            handles.add(hookMethod(label, new IMethodHook() {
                @Override public void before(HookParam param) {
                    if (!active() || !FUNCTION.equals(param.getArgs()[1])) return;
                    try { param.setResult(title((Context) param.getArgs()[0])); }
                    catch (Throwable error) { failure("parent label", error); }
                }
            }));
            handles.add(hookMethod(populate, new IMethodHook() {
                @Override public void before(HookParam param) {
                    if (!active()) return;
                    try {
                        Object owner = param.getThisObject();
                        Object root = screen.invoke(owner);
                        if (root == param.getArgs()[0]) insert(owner, root);
                    } catch (Throwable error) { failure("native list", error); }
                }
            }));
            // A retained native listener must not write our action after the master
            // switch changes. Restoration (self=true) remains entirely native.
            handles.add(hookMethod(select, new IMethodHook() {
                @Override public void before(HookParam param) {
                    try {
                        if (!active() && Boolean.FALSE.equals(param.getArgs()[2])
                            && FUNCTION.equals(key.invoke(param.getArgs()[0]))) param.setResult(false);
                    } catch (Throwable error) { failure("selection gate", error); }
                }
            }));
            handles.add(hookMethod(resume, new IMethodHook() {
                @Override public void before(HookParam param) {
                    try {
                        Object root = screen.invoke(param.getThisObject());
                        if (root == null) return;
                        Object item = find.invoke(root, FUNCTION);
                        if (item != null) {
                            visible.invoke(item, active());
                            enabled.invoke(item, active());
                        }
                    } catch (Throwable error) { failure("visibility gate", error); }
                }
            }));
            registerHotReloadCleanup(this::cleanup);
        } catch (Throwable error) {
            for (int i = handles.size() - 1; i >= 0; i--) {
                try { if (handles.get(i) != null) handles.get(i).unhook(); }
                catch (Throwable rollback) { error.addSuppressed(rollback); }
            }
            failure("required ABI / registration (selector disabled)", error);
        }
    }

    private void insert(Object owner, Object root) throws ReflectiveOperationException {
        if (root == null || find.invoke(root, FUNCTION) != null) return;
        Context host = (Context) context.invoke(owner);
        Object item = singleChoice.newInstance(host);
        setKey.invoke(item, FUNCTION);
        setTitle.invoke(item, title(host));
        persistent.invoke(item, false);
        Object none = find.invoke(root, "none");
        int oldNoneOrder = none == null ? 0 : (Integer) order.invoke(none);
        // OEM g() assigns increasing XML insertion orders. Refuse an ambiguous
        // ordering contract instead of moving camera/payment rows unpredictably.
        int max = -1;
        int size = ((ArrayList<?>) children.get(root)).size();
        for (int i = 0; i < size; i++) {
            Object other = at.invoke(root, i);
            if (other != none) {
                int value = (Integer) order.invoke(other);
                if (value == Integer.MAX_VALUE) throw new IllegalStateException("Native XML order not assigned");
                max = Math.max(max, value);
            }
        }
        if (max >= Integer.MAX_VALUE - 2) throw new IllegalStateException("Native preference order exhausted");
        setOrder.invoke(item, max + 1);
        try {
            if (none != null) setOrder.invoke(none, max + 2);
            if (!Boolean.TRUE.equals(add.invoke(root, item))) throw new IllegalStateException("Native add rejected recorder");
            owners.add(new WeakReference<>(owner));
            // Native p() now sees this item, registers its own listener, and adds
            // it to j2.b.f. Native q/r own mutual exclusion and System writes.
        } catch (ReflectiveOperationException | RuntimeException error) {
            try { remove.invoke(root, item); }
            catch (ReflectiveOperationException rollback) { error.addSuppressed(rollback); }
            if (none != null) setOrder.invoke(none, oldNoneOrder);
            throw error;
        }
    }

    private void cleanup() {
        for (WeakReference<Object> reference : owners) {
            Object owner = reference.get();
            if (owner == null) continue;
            try {
                Object root = screen.invoke(owner);
                Object item = root == null ? null : find.invoke(root, FUNCTION);
                if (item == null) continue;
                setListener.invoke(item, new Object[]{null});
                ((ArrayList<?>) choices.get(owner)).remove(item);
                remove.invoke(root, item);
            } catch (Throwable error) { failure("cleanup", error); }
        }
        owners.clear();
    }

    private Class<?> required(String name) throws ClassNotFoundException {
        return Class.forName(name, false, getClassLoader());
    }

    private static Method declared(Class<?> type, String name, Class<?> result, Class<?>... args)
        throws ReflectiveOperationException {
        Method value = type.getDeclaredMethod(name, args);
        checkReturn(value, result);
        value.setAccessible(true);
        return value;
    }

    private static Method method(Class<?> type, String name, Class<?> result, Class<?>... args)
        throws ReflectiveOperationException {
        Method value = type.getMethod(name, args);
        checkReturn(value, result);
        return value;
    }

    private static void checkReturn(Method method, Class<?> result) throws NoSuchMethodException {
        if (method.getReturnType() != result) throw new NoSuchMethodException(method + " return type != " + result.getName());
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws ReflectiveOperationException {
        Field value = type.getDeclaredField(name);
        if (value.getType() != expected) throw new NoSuchFieldException(type.getName() + "." + name + " type mismatch");
        value.setAccessible(true);
        return value;
    }

    private static boolean active() { return PrefsBridge.getBoolean(ENABLED); }

    private static String title(Context context) {
        return "zh".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage()) ? "录音" : "Recording";
    }

    private void failure(String stage, Throwable error) {
        Log.e(TAG, "SecurityCore native power recorder: " + stage + " failed", error);
    }
}
