/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.others;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Pure guards for the ROM-specific hook; no Android state or callback registry. */
final class ScreenCaptureCallbackPolicy {
    private ScreenCaptureCallbackPolicy() { }

    static boolean shouldInstall(int sdk, boolean enabled) {
        return sdk == 37 && enabled;
    }

    static boolean isObserverDispatch(Method method) {
        return method != null
            && method.getName().equals("reportScreenCaptured")
            && method.getParameterCount() == 0
            && method.getReturnType() == void.class
            && !Modifier.isStatic(method.getModifiers())
            && !Modifier.isAbstract(method.getModifiers());
    }
}
