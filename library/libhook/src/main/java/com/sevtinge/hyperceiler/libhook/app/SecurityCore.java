/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.app;

import com.hchen.database.HookBase;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseLoad;
import com.sevtinge.hyperceiler.libhook.rules.securitycore.PowerDoubleClickRecorder;

@HookBase(targetPackage = "com.miui.securitycore")
public class SecurityCore extends BaseLoad {
    @Override public void onPackageLoaded() {
        initHook(new PowerDoubleClickRecorder(),
            PrefsBridge.getBoolean("system_framework_volume_screen_off_recorder"));
    }
}
