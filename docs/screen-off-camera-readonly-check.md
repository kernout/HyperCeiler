# 息屏音量下拍照只读排查

本次未确定失效根因；不凭无日志推断 hook 安装失败，也不因此改写已经验证的拍照链路。生产 `ScreenOffCamera`、camera service、loader 及全部 power 文件均未修改。

## 已取得的设备证据

设备 `myron`，ROM `OS4.0.4.0.XPMCNXM`，SDK 37。已安装版本为 `2.10.166-recorder-startup-fix`（4773）。设备 DE prefs 中 `prefs_key_system_framework_volume_screen_off_camera=true`，recorder 同为 true；这些是读取结果，未修改。

原始 ROM JAR 的 SHA-256 与工作区现成副本完全一致：

|文件|SHA-256|
|---|---|
|`/system_ext/framework/miui-services.jar`|`1e792b29fcfc277f4bf1777711aece00e228e0adecd3894039fc1e6af03ca63f`|
|`/system/framework/services.jar`|`bfd88ca65771d9e6c4229f9dc30c2b5916c0fe1b310afa77819c637de777a0f8`|
|`/system/framework/framework.jar`|`bfe477943ca7c58ea151897b2498f705ec4e9470ae727b6aa7d18305d342f5b8`|

匹配副本位于 `/workspace/edge-fw`，`miui-services.jar` 也与 `/workspace/camera-shortcut-inspect/miui-services.jar` 一致。复用原有反编译，并从匹配 `services.jar` 单类复核 `SingleKeyGestureDetector`。新增 `tests/screen-off-camera-hook/rom_abi.py` 直接解析 DEX 的声明表，检查本 hook 的 21 个精确字段/方法签名（包括隐式当前用户、FGS、隐私 API）；全部通过。精确声明检查不依赖 JADX 对方法名/桥接符号的格式化。

设备 `dumpsys window policy`：key 25 已注册，`volumekey_launch_camera=-1`，`MaxCount=1`。同时 `dumpsys power` 为 Awake，所以该 MaxCount 与 hook 只在非交互态扩展为 2 的设计一致，不能作为 hook 未安装的证据。`settings get system volumekey_launch_camera` 为 null；已有 hook 在息屏识别阶段不依赖 OEM 设置，不应改系统设置来掩盖问题。

包管理信息显示 CAMERA 与 FOREGROUND_SERVICE_CAMERA 已授权，包 `stopped=false`。`cmd appops get ... CAMERA` 显示 UID mode foreground、CAMERA allow，以及一次距读取约 6 小时 24 分的约 725 ms 历史访问。这不证明那次访问来自息屏功能，也不证明当前 FGS 能成功准入。读取时没有运行中的模块 service。

现有 logcat 的 main/system 缓冲仅 64 KiB，system 缓冲为空；现有 LSPosed modules 日志只有 21 字节的 part header，应用中保存的日志副本也没有 ScreenOffCamera 的安装、失败或 dispatch 记录。有限日志中未找到 `HCScreenOffCamera`、capture dispatch 或准入拒绝证据；不是证明它们从未发生。

已安装 APK 被只读导出到 `/workspace/camera-hook-recheck/installed.apk` 后单类反编译：`ScreenOffCamera` 仍有 base context/handler、八个 caller deopt、两项 volume-down hook、隐私 preflight 和显式 startForegroundServiceAsUser 的原链路。未发现相机 hook 被构建遗漏的证据。混淆后的 APK 源码不能单凭文本等同于未混淆源码。

## 入口与共存判断

匹配 ROM 的 `MiuiSingleKeyRule.onKeyGesture` 仍把 type 0、count >1 分派到 `onMiuiMultiPress(Object,long,int,int)`；`getMaxMultiPressCount()` 仍委托到 `getMiuiMaxMultiPressCount()`。`VolumeDownKeyRule` 的音频、副屏、policy flag 字段/方法及 detector start/cancel/down/up ABI 未变。没有支持“本 ROM 双击入口已改名/换签名”的证据。

源码中 power hook 针对 PowerKeyRule 及双击电源 source/function；共享 `postTriggerFunction` callback 在 source/function 不匹配时返回，不直接截获 volume-down 原始事件。录音的通用 queue hook 则把实际事件交给 `ScreenOffRecorderState`，其 key !=24 分支返回 PASS。新增共存测试编译并执行该生产 state：volume-down/power 的 DOWN/UP 在录音资格开关两种状态都 PASS；已有 volume-up 所有权也不能吞掉 volume-down DOWN/UP，只保留自己的 matching UP。24 项断言通过。此测试覆盖纯状态机，不冒充真机 ART 上所有 hook 顺序的集成验证。

当前生产 ScreenOffCamera 已有隐私检查、异步显式 FGS、当前用户与队列复查、busy/cooldown、失败时抑制原生亮屏 UI 等措施，未发现足够明确的错误依据，故保留这些逻辑。FGS 相机准入失败、OEM 音频 gate、实际去优化失败或运行时 prefs/loader 状态仍不能排除。

## 实际验证

```sh
python3 tests/screen-off-camera-hook/rom_abi.py \
  --miui-services /workspace/edge-fw/miui-services.jar \
  --services /workspace/edge-fw/services.jar \
  --framework /workspace/edge-fw/framework.jar
python3 tests/screen-off-camera-hook/run.py \
  --android-classes /workspace/camera-service-check/android-35-classes
python3 tests/screen-off-camera-selector/run.py \
  --android-classes /workspace/camera-service-check/android-35-classes \
  --hidden-api-jar /workspace/camera-service-check/hiddenapibypass-6.1.jar
```

结果：21 项 ROM ABI 声明检查、145 项 hook 断言、24 项共存断言、17 项生产 selector/orientation 检查和 9 项生产 service freshness 检查通过。hook 与全部生产 camera Java 使用真实公开 Android API 35 class 目录完成 javac 编译；仅 hook 的 BaseHook/EzHookTool 等依赖采用测试夹具。没有进行完整 APK Gradle 构建、真机拍照/录音或真机部署验证。

复核产物在 `/workspace/camera-hook-recheck`：匹配 ROM detector 单类反编译、已安装 APK 单类反编译、ROM ABI JSON。未上传。

下一步若要确定失效环节，需要一次自然失败附近的有效 system_server hook 安装/dispatch 日志及 app service 日志，区分“没有双击 callback”“被 OEM gate 拒绝”“未启动 FGS”“相机服务/Camera2 拒绝”。本次授权禁止触发、安装、重启、改 prefs，因此没有制造新的拍照事件，也没有假定部署后修复成功。

## 补充：完整上游 routing 与可部署诊断

已在匹配的 `/workspace/edge-fw` JAR 上重新单类反编译完整上游，产物位于 `/workspace/camera-hook-recheck/routing`。`volumekey_launch_camera=-1` 没有在下列上游入口形成“关闭就不进入 detector”的分支：

1. `MiuiPhoneWindowManager.interceptKeyBeforeQueueing` 直接转 `BaseMiuiPhoneWindowManager.interceptKeyBeforeQueueingInternal`。后者检查 boot、水下模式、企业、特殊键盘/手写笔、VR/关机、DP-IN、组合键、距离传感器等；普通音量下再调用 `callSuperInterceptKeyBeforeQueueing`。这些可能阻断事件，但不读取 `volumekey_launch_camera`。`streetSnap` 是另一个长按广播逻辑，不根据本开关 return；设备只读 dump 的 `mLongPressVolumeDownBehavior=0`、`mSystemBooted=true`、`mIsVRMode=false`、`mTestModeEnabled=false`。
2. `MiuiKeyInterceptExtend.getKeyInterceptType` 的特殊路由先要求平板 full keyboard；未发现基于 OEM 相机设置的过滤。
3. `PhoneWindowManager.interceptKeyBeforeQueueing` 在其音量键 switch 之前调用 `handleKeyGesture`。此前的退出包括 boot、global key、显示用户限制、设备厂商路径和 stub 拦截；gesture skip 包括 policy 0x40000、event 0x400 及 `skipKeyGesutre`。该路径不读取 OEM 相机整数值。`handleKeyGesture` 的 camera-gesture 早退专属于 power key26，其他键送 `SingleKeyGestureDetector.interceptKey`。
4. `PhoneWindowManagerStubImpl.skipKeyGesutre` → `MiuiKeyShortcutRuleManager.skipKeyGesutre` → `MiuiShortcutTriggerHelper.skipKeyGesutre` 只检查 repeatCount !=0 或 event flag 0x01000000。stub 的 `interceptKeyBeforeQueueing` 对副屏 wake key 有特殊处理；委托的 `MiuiSoScManagerImpl.interceptKeyEvent` 只拦 key4，key25 返回 false。
5. key25 规则初始化、addRule 没有 OEM 相机设置 gate。detector 每次 down/up 动态查询 `getMaxMultiPressCount`；首个 UP 只有 max==1 才立即清 rule。现有 hook 的非交互 max==2 正是用来独立于 -1/0 识别双击。原始 -1 仅使 `VolumeDownKeyRule.isEnableLaunchCamera` 为 false、OEM maxCount=1，hook 覆盖该结果即可，未发现额外 setting gate。

因此，不应凭 -1 改 prefs/系统值或加一个通用按键替代 hook。上游其他传感器/模式的运行时状态仍可能阻断；本次没有绕过它们。

### loader / deopt 结论

安装 APK 的 `Deoptimizers.deoptimize(Method)` 委托到 EzXposed 后端。后端 `deoptimize(Executable)` 捕获任何 Throwable 并返回 false；`deoptimizeOrThrow` 在 base 初始化后调用真实 `XposedInterface.deoptimize`。这说明原来的相机“任一 deopt false 就不装 hook”确实可能无声失效，但不能据此声称设备已发生。

另外只读提取设备 `/data/adb/modules/zygisk_lsposed/framework.dex`，模块版本 `v2.2.1 (7940)`。其真实 context（DEX混淆名 `z0`）实现 `deoptimize(Executable)`：abstract/proxy 返回 false，其余调用 native `org.lsposed.lspd.core.nativebridge.HookBridge.deoptimizeMethod`。`XposedInterfaceWrapper` 经过 ensureAttached 后委托 mBase。同一 API 在当前 loader 有真实实现，不能认定“loader API 不支持”。native 针对 SDK37 的最终返回或重复 deopt 语义无法从 Java 声明得出。

camera 与 power 的 caller deopt 交集为 `MiuiSingleKeyRule.onKeyGesture`，两者使用同一后端。它们对该方法只是 deopt，不挂 callback；没有覆盖对方 callback 的 Java 逻辑。若重复 native deopt 返回 false，现有 camera 全量安装 gate 会终止：本次保留原安全 gate，新增精确 caller 的 direct warning，让部署后能够证明是否发生；没有无凭据取消 deopt 或绕过 gate。

### 本次生产修改与日志协议

生产改动仅 `ScreenOffCamera.java`；service 的临时新增日志已经全部撤回，`git diff` 确认 service 无变化。沿用既有 service 对 FGS/权限/preflight/显示/Camera2失败日志。

找到原日志可观测性问题：`XposedLog.i` 和 `XposedLog.w` 都被全局 verbose 设置过滤。新诊断使用 `android.util.Log.w("HCScreenOffCamera", ...)`，无需开启 verbose；仅 camera prefs 开时输出，不输出 user、Intent、路径、图片、异常 message 或堆栈。diagnostic 内所有异常都被隔离，不能改变捕获/分派行为。

- 安装：`hook install_begin`、`hook install_ok deopt=8 hooks=2`；失败有 `install_failed`，deopt false/throw 包含确定的类名#方法名。
- `hook recognition_screen_off_max2` 每个 hook 实例至多一次，证明非交互 count override 真正执行，无逐键日志。
- 一个 double 诊断 trace 每 2500ms 至多一次：`double_entry`、`post_ok`、`privacy_ok`、`fgs_dispatch_begin`、`fgs_dispatch_ok`；拒绝有 `reject_entry/queue_injected/audio/subscreen`、power/handler/busy/post/privacy/user/FGS 等阶段。异常只显示解包后的异常类名。
- `fgs_dispatch_ok` 只证明 Context 启动返回非 null，不能冒充 service 已 onStartCommand 或成功保存照片。之后查既有 service/Camera2 的 `HCScreenOffCamera` 日志。

新增测试验证 direct warning 的精确阶段、三种 OEM gate、privacy/FGS exception class、日志 cooldown、count 一次性、runtime disable 静默、Log.w 自身抛错仍正常 dispatch。当前结果：181 项 camera hook 断言、24 项生产 recorder/camera 共存断言、17 项 selector/orientation、9 项 service admission 全部通过。hook 与全部 camera sources 真实公开 API35 SDK classes javac 编译通过。

可部署诊断已具体落实到生产 hook；本次未装包/重启/触发。自然失败后按 tag 读取即可：`logcat -d -s HCScreenOffCamera:W`。若只有 install_ok 而始终没有 recognition，定位上游 routing/实际 max caller；有 recognition 没 double 则定位 detector 完成分派；有 reject 则按明确 gate；有 fgs_dispatch_ok 后出现 service 的 FGS/Camera2 错误则是 app准入或相机链路。没有 install_begin 时需查实际 loader/prefs，不能用缺失日志直接推断 camera API不支持。
