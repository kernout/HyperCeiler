/*
  * This file is part of HyperCeiler.

  * HyperCeiler is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation, either version 3 of the
  * License.

  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.

  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <https://www.gnu.org/licenses/>.

  * Copyright (C) 2023-2026 HyperCeiler Contributions
*/
package com.sevtinge.hyperceiler.libhook.rules.screenshot

import android.content.ContentResolver
import android.content.Context
import android.graphics.Rect
import android.os.Build
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook

object HideStatusBarWhenShot : BaseHook() {
    override fun init() {
        if (Build.VERSION.SDK_INT >= 37) {
            // HyperOS 4 merges this argument with its own default exclusions.
            // Exclude at capture time, without broadcasts or blocking sleeps.
            loadClass("com.miui.screenshot.core.util.DisplayCapture").findMethod {
                name("captureDisplay")
                parameterTypes(
                    Context::class.java,
                    Integer.TYPE,
                    Rect::class.java,
                    Array<String>::class.java
                )
            }.createBeforeHook {
                @Suppress("UNCHECKED_CAST")
                val exclusions = it.args[3] as? Array<String>
                it.args[3] = ((exclusions ?: emptyArray()) + "StatusBar").distinct().toTypedArray()
                XposedLog.d(TAG, packageName, "HOOK_STATE=LAYER_EXCLUSION path=miui layer=StatusBar")
            }
            return
        }

        // Keep the legacy broadcast-triggering workaround on older systems only.
        loadClass($$"android.provider.Settings$System").findMethod {
            name("getInt")
            parameterTypes(ContentResolver::class.java, String::class.java, Integer.TYPE)
        }.createBeforeHook {
            if (it.args[1] == "touch_assistant_enabled") {
                it.result = 1
            }
        }
    }
}
