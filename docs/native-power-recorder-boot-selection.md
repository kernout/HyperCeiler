# 双击电源录音选择跨重启保留

## 原因与 ROM 证据

本修复针对 `hyperceiler_recording_toggle` 与 `double_click_power_key`。设置页与系统框架持有不同进程的支持表；给 SecurityCore 的列表添加一行，不能使 system_server 的启动校验认识这个 function。

已核对设备实际文件 SHA-256 与分析输入一致：

| ROM 文件 | SHA-256 |
| --- | --- |
| `/system_ext/framework/miui-framework.jar` | `d137241933ba43c1bca7a57d14f04b4b93e2720da907898e016b23b0adb4b4ec` |
| `/system_ext/framework/miui-services.jar` | `1e792b29fcfc277f4bf1777711aece00e228e0adecd3894039fc1e6af03ca63f` |
| `/system/framework/services.jar` | `bfd88ca65771d9e6c4229f9dc30c2b5916c0fe1b310afa77819c637de777a0f8` |

对应反编译证据位于 `/workspace/camera-shortcut-inspect/analysis/{MiuiSingleKeyRule,MiuiSingleKeyObserver,MiuiShortcutObserver}.java` 与 `/workspace/native-power-inspect/{ShortcutFunctionManager,MiInputShortcutFeature}.java`。SecurityCore 证据在 `/workspace/native-power-inspect/securitycore/sources/j2/b.java` 和 `p030f2/t.java`；JADX 显示别名不用于运行时类名。

调用链如下：

1. `MiuiSingleKeyRule.init()` 创建 `MiuiSingleKeyObserver`，随后调用 `setDefaultFunction(false)`。
2. `MiuiSingleKeyObserver.setDefaultFunction` 按 `mCurrentUserId` 读取 Settings.System。已保存值非 null 时优先使用已保存值；reset=true 则使用 ROM 默认值。
3. `MiuiShortcutObserver.isFeasibleFunction(action,function,context)` 调用 `MiInputShortcutFeature.getAllSupportShortcutInfoMap()`，转到 `ShortcutFunctionManager`。function 不在表中时进入 ROM 的少数自定义功能例外检查，录音自定义 key 不属于这些例外。
4. 校验失败会写 `Settings.System.putStringForUser(..., action, null, mCurrentUserId)`，日志为 `set <action> to null, because function not support`。随后原生 `updateRuleInfo()` → `onChange` → `updateFunction()` 读取设置、更新 `mActionAndFunctionMap`。
5. `PowerKeyRule.getFunction()` 最终读取这个 observer 内存表。只改数据库、未让原生 observer 刷新，不能据此认为按键内存已一致。
6. SecurityCore `j2.b.onResume()` 读取当前用户设置，值为 null 或不在其 `i` 表时，仅将本次 UI 选择解释成 `none`。`q(..., self=true)` 恢复单选状态，不调用 `r` 保存；用户手动选择的 `q(..., self=false)` 才调用 `r` 写设置。

因此界面“无”可能来自设置已被清成 null，也可能来自 UI 支持表缺项；不能仅凭“无”断言持久值是字面量 `none`。

ROM 支持表的确切 schema 为 `Map<function, Map<type, List<action>>>`。本修复加入：

```text
hyperceiler_recording_toggle
  gesture_shortcut_show_for_function
    [double_click_power_key]
```

type 取自 `f2.t.j(1)` 的原生映射，以及 `ShortcutFunctionManager` 的 XML 解析逻辑。原生验证会遍历内部各 type 的 action 列表。本修复没有添加其他 action。

## 实现与用户选择

只改系统框架录音电源 Hook `volume/PowerDoubleClickRecorder.java`。在已有完整 ABI 预检内新增 manager 两个 getter、feature 两个入口、原生验证和规范化方法的精确签名检查。对 getter 的原生调用方去优化，防止 ART 已内联的读取绕过扩展；安装任一 Hook 失败则撤销本次全部 Hook。

两个 manager getter 的 after 回调给成功返回的表添加上述条目，覆盖普通读取及 `needReinit=true`。外层 map、被修改的 function map 和 action list 都复制，保留其他项；不向 ROM singleton 写入模块项，避免热重载或关闭后留下修改。只有 Hook 完整安装、录音总开关启用时扩展返回值，ROM 读取异常保持原样。

ROM 继续执行完整校验、Settings 写入、AOSP double-tap 同步、observer 更新和原生监听通知。没有跳过 `isFeasibleFunction`、没有截断 `setDefaultFunction`、没有开机恢复任务、没有持久化历史录音选择、没有新增 Settings 写操作。用户后来选择相机、手电筒、`none`，以及 ROM 显式重置或新用户初始化，都按原生规则生效。

多用户仍由 ROM 的 `mCurrentUserId` 控制：初始用户使用构造参数；`onUserSwitch` 使用 ROM 的 USER_CURRENT（-2）重新读取并规范化当前用户。Hook 本身不读取、缓存或写任何用户选择。录音执行仍沿用当前用户校验与 `startForegroundServiceAsUser`，不改变录音 service 或 camera 代码。

## 回归与验证边界

执行命令：

```sh
python3 tests/power-double-click-recorder/run.py --output /workspace/recorder-power-evidence/boot-selection/tests
python3 tests/power-double-click-recorder/verify_rom_abi.py
python3 tests/native-power-recorder/run.py --output /workspace/recorder-power-evidence/boot-selection/native-ui-tests
python3 tests/recorder-toggle-interval/run.py
```

`run.py` 编译实际生产 Hook，使用真实 Android 35 SDK、EzHook 1.3.0、libxposed API102；主机执行使用真实 HookParam/InvocationContext，Android/ROM 部分是有界测试模型。`BootSelectionTest` 先复现未修复时持久值和 observer 内存被清空，再执行生产支持表 Hook，覆盖重启后保留、后来选无/相机/手电筒、user 0/10/999 切换、显式 reset、新用户默认、重新初始化、原生未知功能拒绝、异常保留、关闭/热重载不泄漏条目。原有异步按键、隐私门控及回滚回归继续执行；UI 测试另覆盖 null/unknown/none 显示回退不写设置。

`verify_rom_abi.py` 直接读取原始 DEX class_data，检查 36 个精确方法/字段，并继续校验事件 getter 的实际字节码极性。新增 boot fixture 明确仅模拟设置规范化相关分支，没有模拟地区钱包或运营商定制的完整实现。

设备只读检查结果见 `/workspace/recorder-power-evidence/boot-selection/device-readonly.txt`：前台 user 0 的持久值与 `dumpsys window policy` 的 KeyCode=26 observer 内存都是自定义录音 key，MaxCount=2；user 999 的持久值为 null。该快照可能已在用户重新选择之后取得，不能据此还原此前重启过程。尚未取得此前启动的清理日志，也没有执行新的安装、重启、用户切换、按键注入或录音。此次没有完整 APK 构建；SDK 编译和主机回归不能证明实机 ART Hook 安装时序及跨重启效果。

修复部署后需在授权的实机验收中确认：启用模块和两个必要作用域，选择录音重启后观察数据库/observer/UI 三处一致；再手动选无重启，确认没有恢复录音；其他用户的选择独立。已被旧版本清为 null 的选择无法与用户有意选无可靠区分，需要用户重新选择一次，修复不会猜测或复活历史选择。
