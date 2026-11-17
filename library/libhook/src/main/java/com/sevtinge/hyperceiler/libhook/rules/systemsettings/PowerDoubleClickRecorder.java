/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.systemsettings;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Settings-side selector only. The OEM power-key detector and recorder dispatcher own execution. */
public final class PowerDoubleClickRecorder extends BaseHook {
    private static final String FRAGMENT = "com.android.settings.GestureShortcutSettingsFragment";
    private static final String KEY_CLASS = "com.android.settings.MiuiShortcut$Key";
    private static final String DROPDOWN = "com.android.settingslib.miuisettings.preference.miuix.DropDownPreference";
    private static final String LISTENER = "androidx.preference.Preference$OnPreferenceChangeListener";
    private static final String ENABLED = "system_framework_volume_screen_off_recorder";
    private static final String ACTION = "double_click_power_key";
    private static final String FUNCTION = "hyperceiler_recording_toggle";
    private static final String UI_KEY = "hyperceiler_power_double_click_action";
    private static final int USER_CURRENT = -2;
    private final Map<Object, Selector> selectors = new IdentityHashMap<>();
    private Class<?> keyClass;

    @Override
    public void init() {
        // com.android.settings must also be enabled in the module's LSPosed scope.
        if (!PrefsBridge.getBoolean(ENABLED)) return;
        Class<?> fragment = findClassIfExists(FRAGMENT);
        keyClass = findClassIfExists(KEY_CLASS);
        Class<?> dropdown = findClassIfExists(DROPDOWN);
        Class<?> listener = findClassIfExists(LISTENER);
        if (fragment == null || keyClass == null || dropdown == null || listener == null) return;
        try {
            // Resolve the actual ROM signatures before touching any Preference tree.
            dropdown.getConstructor(Context.class);
            dropdown.getMethod("setEntries", CharSequence[].class);
            dropdown.getMethod("setEntryValues", CharSequence[].class);
            dropdown.getMethod("setValue", String.class);
            dropdown.getMethod("setOnPreferenceChangeListener", listener);
            Settings.System.class.getMethod("getStringForUser", ContentResolver.class, String.class, int.class);
            Settings.System.class.getMethod("putStringForUser", ContentResolver.class, String.class, String.class, int.class);
            findAndHookMethod(fragment, "onCreate", Bundle.class, new IMethodHook() {
                @Override public void after(HookParam param) {
                    if (param.getThrowable() == null) syncSafely(param.getThisObject());
                }
            });
            findAndHookMethod(fragment, "onResume", new IMethodHook() {
                @Override public void after(HookParam param) {
                    if (param.getThrowable() == null) syncSafely(param.getThisObject());
                }
            });
            findAndHookMethod(fragment, "onDestroy", new IMethodHook() {
                @Override public void before(HookParam param) { removeSafely(param.getThisObject()); }
            });
            findAndHookMethod(keyClass, "getResourceForKey", String.class, Context.class, new IMethodHook() {
                @Override public void before(HookParam param) {
                    // Preserve native titles/conflict dialogs for every other binding.
                    if (!FUNCTION.equals(param.getArgs()[0])) return;
                    try {
                        param.setResult(recordingTitle((Context) param.getArgs()[1]));
                    } catch (Throwable error) { warn(error); }
                }
            });
            registerHotReloadCleanup(() -> {
                for (Object owner : new ArrayList<>(selectors.keySet())) removeSafely(owner);
            });
        } catch (Throwable error) { warn(error); }
    }

    private void syncSafely(Object fragment) {
        try {
            if (!PrefsBridge.getBoolean(ENABLED)) { removeSafely(fragment); return; }
            Selector selector = selectors.get(fragment);
            if (selector == null) {
                Object category = callMethod(fragment, "findPreference", "function_shortcut");
                Context context = (Context) callMethod(fragment, "getContext");
                if (category == null || context == null) return;
                // Avoid duplicate entries if a lifecycle callback is repeated.
                if (callMethod(fragment, "findPreference", UI_KEY) != null) return;
                Object preference = findClassIfExists(DROPDOWN).getConstructor(Context.class).newInstance(context);
                callMethod(preference, "setKey", UI_KEY);
                callMethod(preference, "setPersistent", false);
                callMethod(preference, "setTitle", chinese(context) ? "双击电源键" : "Double press power button");
                callMethod(preference, "setSummary", "%s");
                selector = new Selector(context, category, preference);
                refresh(fragment, selector); // Populate before adding; never save while initializing.
                Selector state = selector;
                Class<?> listener = findClassIfExists(LISTENER);
                Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                    (self, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "equals" -> self == args[0];
                                case "hashCode" -> System.identityHashCode(self);
                                default -> "HyperCeilerPowerRecorderListener";
                            };
                        }
                        if (!"onPreferenceChange".equals(method.getName())) return null;
                        try {
                            if (selectors.get(fragment) != state || !PrefsBridge.getBoolean(ENABLED)
                                || args == null || args.length != 2
                                || !(args[1] instanceof String value) || !state.values.contains(value)) return false;
                            // Only an explicit user selection writes the real System string, for the current user.
                            return Boolean.TRUE.equals(Settings.System.class.getMethod("putStringForUser",
                                ContentResolver.class, String.class, String.class, int.class)
                                .invoke(null, context.getContentResolver(), ACTION, value, USER_CURRENT));
                        } catch (Throwable error) { warn(error); return false; }
                    });
                callMethod(preference, "setOnPreferenceChangeListener", proxy);
                if (!Boolean.TRUE.equals(callMethod(category, "addPreference", preference))) return;
                selectors.put(fragment, selector);
                // addPreference dispatches onSetInitialValue; a nonpersistent OEM dropdown can reset to null.
                refresh(fragment, selector);
                selector.observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) { syncSafely(fragment); }
                };
                // Track first, so a failed registration can still be removed on destroy/hot reload.
                try {
                    context.getContentResolver().registerContentObserver(Settings.System.getUriFor(ACTION),
                        false, selector.observer);
                } catch (Throwable error) { warn(error); }
            } else {
                refresh(fragment, selector);
            }
        } catch (Throwable error) {
            removeSafely(fragment);
            warn(error);
        }
    }

    private void refresh(Object fragment, Selector state) throws ReflectiveOperationException {
        LinkedHashMap<String, CharSequence> choices = new LinkedHashMap<>();
        Object raw = getStaticObjectField(keyClass, "sGestureMap");
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalStateException("Missing gesture map");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String function) || !(entry.getValue() instanceof List<?> gestures)
                || !gestures.contains(ACTION)) continue;
            // setGestureMap has applied device/region checks; findPreference also excludes removed native rows.
            Object nativePreference = callMethod(fragment, "findPreference", function);
            if (nativePreference == null || Boolean.FALSE.equals(callMethod(nativePreference, "isVisible"))
                || Boolean.FALSE.equals(callMethod(nativePreference, "isEnabled"))) continue;
            Object title = callMethod(nativePreference, "getTitle");
            if (title instanceof CharSequence label && label.length() > 0) choices.put(function, label);
        }
        choices.put(FUNCTION, recordingTitle(state.context));
        choices.put("none", chinese(state.context) ? "无" : "None");
        String current = (String) Settings.System.class.getMethod("getStringForUser",
            ContentResolver.class, String.class, int.class)
            .invoke(null, state.context.getContentResolver(), ACTION, USER_CURRENT);
        // Null/empty means unassigned in the OEM UI. Never persist that presentation normalization.
        String shown = current == null || current.isEmpty() ? "none" : current;
        if (!choices.containsKey(shown)) {
            choices.put(shown, (chinese(state.context) ? "当前其他功能：" : "Current other action: ") + shown);
        }
        List<String> values = new ArrayList<>(choices.keySet());
        List<CharSequence> labels = new ArrayList<>(choices.values());
        if (!values.equals(state.values) || !labels.equals(state.labels)) {
            // OEM setEntries calls Spinner.setSelection using mEntryValues. Replace values first,
            // and suppress its selection callback while changing the adapter's rows.
            Object spinner = getObjectField(state.preference, "mSpinner");
            Object spinnerListener = null;
            if (spinner != null) {
                spinnerListener = callMethod(spinner, "getOnItemSelectedListener");
                callMethod(spinner, "setOnItemSelectedListener", (Object) null);
            }
            try {
                callMethod(state.preference, "setEntryValues", (Object) values.toArray(new CharSequence[0]));
                callMethod(state.preference, "setValue", shown);
                callMethod(state.preference, "setEntries", (Object) labels.toArray(new CharSequence[0]));
            } finally {
                if (spinner != null) callMethod(spinner, "setOnItemSelectedListener", spinnerListener);
            }
            state.values = values;
            state.labels = labels;
        } else {
            callMethod(state.preference, "setValue", shown);
        }
    }

    private void removeSafely(Object fragment) {
        Selector state = selectors.remove(fragment);
        if (state == null) return;
        if (state.observer != null) {
            try { state.context.getContentResolver().unregisterContentObserver(state.observer); }
            catch (Throwable error) { warn(error); }
        }
        try { callMethod(state.preference, "setOnPreferenceChangeListener", (Object) null); }
        catch (Throwable error) { warn(error); }
        try { callMethod(state.category, "removePreference", state.preference); }
        catch (Throwable error) { warn(error); }
    }

    private static boolean chinese(Context context) {
        return "zh".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    private static String recordingTitle(Context context) {
        return chinese(context) ? "HyperCeiler 录音" : "HyperCeiler recording";
    }

    private void warn(Throwable error) {
        // Do not expose a user's current binding or settings contents in logs.
        Log.w(TAG, "Power recorder selector unavailable: " + error.getClass().getSimpleName());
    }

    private static final class Selector {
        final Context context;
        final Object category;
        final Object preference;
        List<String> values = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        ContentObserver observer;
        Selector(Context context, Object category, Object preference) {
            this.context = context;
            this.category = category;
            this.preference = preference;
        }
    }
}
