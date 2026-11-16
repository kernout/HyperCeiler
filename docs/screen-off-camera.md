# 熄屏双击音量下拍照

## 需求与实现范围

目标是在熄屏时快速双击实体音量下键，使用后置主摄拍摄一张照片，保持屏幕关闭，不启动系统相机或 HyperCeiler 的 Activity / 相机 UI。照片通过 MediaStore 写入 `DCIM/Camera`，文件名以 `HC_` 开头。亮屏时保留系统原行为；媒体播放或通话期间不触发拍摄。

涉及两个运行端：Android 系统框架（作用域通常显示为 Android 系统 / `system` / `android`，代码运行在 `system_server`）负责识别按键和派发；HyperCeiler（`com.sevtinge.hyperceiler`）内的前台服务负责 Camera2 拍摄与保存。此功能只需系统框架的 Hook 作用域，系统相机、系统设置、SystemUI 无需加入作用域。HyperCeiler 是服务宿主，需要安装、启用和取得权限，并不要求为此另加其自身的 Hook 作用域。

当前 Hook 要求 Android API 35+ 以及匹配的新 MIUI `VolumeDownKeyRule` / `MiuiSingleKeyRule` ABI；不是任意 Android / ROM 通用的按键方案。只 Hook 音量下规则的双击分支和最大连击次数；对已核对的上游调用者进行去优化，以覆盖系统关闭原生快捷拍照、以及私有相机调用被内联的情况。没有旧版通用按键 Hook 回退。

## 开启方式与权限

1. 安装与目标系统适配的 HyperCeiler 构建，在框架管理器中启用模块，并选择 Android 系统框架作用域。
2. 打开 **系统框架 → 音量 → 熄屏双击音量下拍照**。开关默认关闭；开启时请求 HyperCeiler 的 `CAMERA` 运行时权限。拒绝授权保持关闭，授权结果确认后才持久化开启；进入页面时发现权限已撤销会关闭开关。
3. 新 Hook 的装载需要重启 framework / 系统框架（或重启手机）。**由用户自己选择合适时机执行**；本次源码交付不自动重启、不等待真机操作。
4. 生效后，在无播放、无通话且屏幕非交互状态下快速双击音量下键。每次接受的请求最多保存一张；连续请求有 2.5 秒派发冷却，服务忙碌时丢弃重复请求。

Manifest 声明 `CAMERA`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CAMERA`、`WAKE_LOCK`，服务声明 `foregroundServiceType="camera"` 且 `exported="false"`，没有导出的拍摄入口。普通应用不能直接调用该服务。由清除 Binder 调用身份的 `system_server` 显式向当前用户启动前台服务，依赖系统允许的后台 FGS 和 while-in-use 相机例外；这不会自动授予 CAMERA，也不会绕过 AppOps 或相机策略。

前台服务会发布低重要性、无声音、无振动、无指示灯且锁屏私密的短暂通知。没有通知点击 Activity、全屏 Intent 或亮屏 WakeLock；仅持有最长 15 秒的 `PARTIAL_WAKE_LOCK` 来维持 CPU。系统相机使用指示器和系统规定的前台服务呈现仍由平台控制。

## 触发条件、隐私和失败处理

- 使用 ROM 的 `isAudioActive()` 门禁，在回调入口和异步队列实际派发前各检查一次。已审阅的 ROM 实现覆盖通话音频模式和活动音频流（排除系统提示音流），因此媒体播放、通话不触发；副屏相机活动和注入按键也被拒绝。这是 ROM 实现约束，不是全设备音频兼容性承诺。
- 遵循 camera privacy。派发前在系统身份下检查全局传感器隐私、相机软件开关和硬件开关；任一关闭相机访问、API 缺失、查询异常或查询期间切换用户，都拒绝拍摄。不会改写或绕过隐私开关，也不会为了查询隐私弹出界面。
- Hook 在非交互双击入口先抑制原生相机分支，再做门禁和派发；成功安装后的派发 / 权限 / 拍摄失败不回退到原生亮屏相机 UI。服务只接受带系统隐私预检标记且时间戳在 0–2500 毫秒以内的请求，过期、未来、缺失或畸形数据均拒绝。这只是入场检查，不把 2.5 秒限制套到后续最长 15 秒的拍摄过程。
- 相机打开前和运行期间仍受平台隐私 / 权限检查约束。预检之后改变隐私状态存在时间窗口，最终由 Camera2 和系统相机策略执行限制。屏幕变为交互状态时取消正在进行的任务；超时、相机占用和保存失败会结束并清理资源。
- 若 ROM ABI 不匹配或必要去优化 / Hook 安装失败，功能不会启用，系统原有行为仍可能存在。因此不能在不检查装载状态的情况下宣称双击一定不会启动原生相机。

## 主摄选择与质量标准

不把第一个后摄 ID 当作主摄。优先按 Xiaomi vendor role `com.xiaomi.cameraid.role.cameraIds` / `cameraId` 验证主摄角色 0；数组标签存在时优先于标量，避免默认标量 0 错选明确标为非主摄的设备。仅使用公开枚举且可直接打开的 ID；有匹配的物理后摄时优先于逻辑多摄，隐藏物理 ID 不会被直接打开。

**多后摄没有角色标签时明确失败**；角色冲突、多个同优先级主摄候选、或有标签却没有主摄也失败。只有唯一公开后摄且完全无角色标签时允许带日志回退。失败时不猜测超广角或长焦，更不切换到系统相机 UI。

质量标准是 **Camera2 标准 JPEG 单张**：选择不超过 1600 万像素的输出，优先 4:3，JPEG quality 92，关闭闪光灯，使用离屏预览辅助 3A，并按传感器 / 设备方向设置 JPEG 方向。并不调用小米系统相机私有算法，不承诺徕卡、HDR、夜景、多帧合成或与系统相机一致的画质和快门速度。

## 冷启动审阅与具体风险

相机服务在应用冷进程中启动也会经过 `Application.attachBaseContext()` / `onCreate()` 和 `AppInitializer`。当前源码中：

- attach 只注册 Xposed 服务监听和初始化 PrefsBridge；Application 级任务只做语言镜像初始化与作用域读取。`AppLanguageHelper.init()` 仅把系统语言索引同步到存储，不在此调用 `setApplicationLocales()` 或重建 Activity。
- `OobeUtils.syncHookAvailability()` 写入开关，FrameworkStatusManager 初始化状态，LogManager 以空历史同步回调初始化日志配置；Application 异常处理写入崩溃记录并委托系统处理，没有启动崩溃 Activity。
- Shell、公告 / 协议对话框、页面装饰和搜索初始化位于 `setupActivityTasks()`，只有 `HomePageActivity` 创建才调度。仓库的 `HookCrashHandler.launchCrashActivity()` 确实含 `startActivity()`，但属于独立 CrashService / CrashReceiver 路径，不在相机服务正常冷启动链上。

在以上可见源码路径中没有发现强制亮屏或无条件启动 Activity 的调用，未发现需修改现有 service / hook 的明确源码阻断。本次审阅没有改动它们。

具体未消除的运行风险包括：应用级同步初始化、远程偏好 / 框架绑定和系统调度可能使隐私预检超过 2.5 秒而拒绝冷启动请求；目标 ROM 的 FGS / Camera2 后台策略可能拒绝操作；已有通知频道或用户 / OEM 通知策略可能改变通知显示和屏幕表现；必要 Hook 未成功安装时仍保留系统原行为。`fan.app.Application` 等外部依赖和 framework 的实际运行行为不由本次宿主测试覆盖，不能把源码没有亮屏调用等同于真机绝不亮屏。

## 验证结果与构建限制

当前交付完成的是源码审阅和宿主测试，**不是已生成 APK、安装成功或真机拍摄成功的承诺**：

- 四个生产 camera Java 源文件以 `javac --release 17`、公共 Android API 35 classes 和 HiddenApiBypass 6.1 依赖完成编译；selector / orientation 17 项、service 隐私预检入场时效 9 项执行通过。
- 生产 Hook 在确定性 Android / OEM fixture 中执行 145 条断言通过，并通过公共 API 35 编译。该测试不执行真实 Binder、ART 去优化、相机 HAL 或前台通知策略。
- Manifest / 变动资源 XML 解析、非导出服务声明和偏好键链路完成静态检查；`git diff --check` 通过。运行命令见 `tests/screen-off-camera-selector/README.md` 和 `tests/screen-off-camera-hook/README.md`。

工程要求 `compileSdk = 37`、`targetSdk = 37` 和 Build Tools `37.0.0`；本机未配置所需 SDK 37 / Build Tools，也没有私有 GitHub Packages 凭据（`GIT_ACTOR` / `GIT_TOKEN` 或 `signing.properties` 中的 `gpr.user` / `gpr.key`）。因此未完成完整 Gradle / APK 构建，不能表述为“APK 编译成功”。API 35 局部编译不替代 SDK 37 的全工程依赖、资源合并和打包验证。

交付补丁仅含本任务的生产代码、测试与文档，不纳入系统相机 APK、第三方反编译源码、SDK 二进制、凭据或个人数据。真机最终验证应由用户择时进行，当前交付不会重启框架、开关设备相机隐私或等待拍摄操作。
