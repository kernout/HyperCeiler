# 音量组合双击视频服务

`com.sevtinge.hyperceiler.video.VolumeVideoService` 是无 Activity 的 Camera2 + MediaRecorder 前台服务。音量上下同时单击的识别由 system_server hook 完成；服务接收 toggle，亮屏/息屏状态不参与准入和停止。它不监听 SCREEN_ON/OFF，不启动预览界面，不唤醒屏幕，不改音频路由、焦点或隐私开关。部分 CPU wake lock 持续覆盖录制及收尾，有两小时加 30 秒上限；录制本身两小时自动安全结束。

## 集成契约

Manifest 由整合方添加：

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<service android:name=".video.VolumeVideoService"
    android:exported="false"
    android:foregroundServiceType="camera|microphone" />
```

CAMERA、RECORD_AUDIO、通知权限应在用户主动设置功能时通过正常权限流程取得。服务不弹权限 Activity，不绕过 while-in-use/后台 FGS 限制；平台拒绝时通知和日志明确报错。不要声明 `OBSERVE_SENSOR_PRIVACY`。

system_server 清除 Binder 调用身份后，向请求用户显式 `startForegroundServiceAsUser`。所有 toggle 均采用 FGS 启动，服务包括空闲重复请求也尝试履行前台协议。Intent：

| 字段 | 类型 | 要求 |
| --- | --- | --- |
| action | String | `com.sevtinge.hyperceiler.action.VOLUME_VIDEO_TOGGLE` |
| request_id | String | 标准 UUID，每次完成手势唯一，重传使用同一个 UUID |
| requested_at | long | 手势确认的 `SystemClock.elapsedRealtime()`，不能用 uptimeMillis |
| request_user | int | 当前 Android 用户 ID，与目标服务所属用户一致 |
| hyperceiler.sensor_privacy_checked | boolean | system_server 已确认全局及相机隐私均允许，必须 true |
| hyperceiler.sensor_privacy_checked_at | long | 相机隐私预检的 elapsedRealtime |
| hyperceiler.microphone_privacy_checked | boolean | system_server 已确认全局及 MIC 隐私均允许，必须 true |
| hyperceiler.microphone_privacy_checked_at | long | MIC 隐私预检的 elapsedRealtime |

三个时间戳均要求非负、不在未来、距离接收不超过 2500ms。原有相机和录音服务采用同名隐私回执；app 不直接调用受 `OBSERVE_SENSOR_PRIVACY` 保护的方法。预检回执只用于新开始，合法停止不因隐私预检缺失而被阻止。非导出服务及可信系统代理是信任边界，extras 本身不是鉴权机制。当前 hook 若仅传三个基础字段，必须补上这四个隐私字段，并检查 MIC（sensor=1）及 camera（sensor=2）和全局隐私，否则新录制会明确拒绝。

服务验证用户为当前前台用户且 CE 存储已解锁，验证 CAMERA、RECORD_AUDIO、AppOps、MIC 未静音和监控注册成功。128 项进程级 UUID 回执去重跨服务重建保留，服务 `START_NOT_STICKY` 不重放旧 toggle。进程被杀不保证无限期历史去重，重启后旧请求通常由 2500ms 新鲜度拒绝。`isRunning()` 为进程内 STARTING/RECORDING/STOPPING 快照，不可跨进程作鉴权或可靠状态查询。

可添加字符串资源（源码采用按名查找，资源未加入也能独立编译并使用英文回退）：`video_recording_channel`、`video_recording_title`、`video_recording_starting`、`video_recording_active`、`video_recording_stopped`、`video_recording_error`。前台通知类型是 camera|microphone；通知标题和说明明确摄像及 MIC，不隐瞒录制。失败详情写入通知展开文本和 `HCVolumeVideo` 日志。

## 主摄、方向和严格 1080p60

本工作树基线提交 `09a00ae35` 的 camera 目录仅有包内可见的 `ScreenOffCameraSelector`，其选择过程还绑定 JPEG/preview 输出要求。实际没有可导入的公共 `RearMainCameraSelector` 和 `CameraOrientation`。视频目录因此包含 `VideoCameraConfig`，不修改相机源码：沿用 Xiaomi `com.xiaomi.cameraid.role.cameraIds` 优先、`cameraId` 标量后备的 role=0 MAIN 策略，多个 MAIN 时优先可直接打开的 role=0 非逻辑主摄。若主摄仅以 physical ID 暴露，则查询公开 logical camera 的 physicalIds 及相应物理 characteristics，按 role=0 选主摄，由 logical ID 承载。角色缺失或歧义明确失败，不按焦距、唯一后摄或 ID 0 猜测。

若整合分支提供上述公共辅助类，可将 `VideoCameraConfig.select` 的角色解析换为 `RearMainCameraSelector` 的 MAIN 选择结果，保留本服务的直接/physical 路由、流能力检查及物理主摄保证；不能调用仍强制 JPEG 输出的照片选择器。`orientationHint` 复用基线后摄 JPEG 方向公式：`(sensorOrientation + round(deviceClockwiseDegrees / 90) * 90) % 360`，用于 `MediaRecorder.setOrientationHint`。若有公共 `CameraOrientation`，应调用其等价后摄公式。方向在 prepare 前冻结，传感器暂不可用时设备角度按 0 处理；录制中转屏不重启、不改变固定 1920×1080 编码尺寸。

常规会话必须同时具备：

- `StreamConfigurationMap.getOutputSizes(ImageFormat.PRIVATE)` 精确包含 1920×1080。
- `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 包含精确 `[60,60]`（`[30,60]` 不算）。
- 该 size 的 `getOutputMinFrameDuration` 大于 0 且不超过 16,666,667ns；0/未知时保守拒绝。
- 至少一个普通 H264 编码器支持 Surface 输入、1920×1080@60、20Mbps 及配置格式。MediaRecorder 自行选择编码器，随后 prepare/start 仍可能失败，直接报错，不重试 30fps。

会话只包含 MediaRecorder encoder Surface，`SESSION_REGULAR` + `TEMPLATE_RECORD`，请求固定 `[60,60]`。直接可访问主摄优先；physical 路径使用 `OutputConfiguration.setPhysicalCameraId` 将该 Surface 固定到 role=0 主摄，使用带 physical ID 集合的 createCaptureRequest，若 carrier 的 `getAvailablePhysicalCameraRequestKeys` 包含 FPS 则额外 `setPhysicalCameraKey`。普通 FPS 请求及 session parameters 同步固定 `[60,60]`。physical 主摄和 logical carrier 都必须通过 PRIVATE 1080p60 静态能力检查。`isSessionConfigurationSupported` 返回 false 则拒绝；HAL 不支持该查询时仍要求相同配置实际 onConfigured，绝不退为未固定物理输出。没有预览 UI，也没有用 JPEG 等额外 Surface 混入会话。平台不接受 encoder-only 组合时 `onConfigureFailed` 明确失败。此实现不启用 constrained-high-speed 会话：若 1080p60 只在 high-speed 表中可用，会明确拒绝；没有把高帧率表当普通会话能力，没有伪造高速 request list，也没有不合法的高速 Surface 组合。

运行时检查每帧回执 `[60,60]`、递增 SENSOR_TIMESTAMP、每两秒的实际传感器帧率。physical 路径要求 `getPhysicalCameraTotalResults` 包含固定主摄 ID，只使用该物理结果的 SENSOR_TIMESTAMP；不能拿 logical 或其他镜头回执替代。逻辑 FPS 必须精确匹配；物理 FPS 回执若存在也必须匹配，独立物理 FPS key 可控时必须收到物理 FPS 回执。输出前再次用 MediaExtractor 扫描完整视频样本 PTS，以实际样本数和时间跨度验证约 60fps（58.5–61.5 容差用于时钟及端点取整；30fps 必拒绝），同时检查 AVC 1920×1080 和非空 AAC 轨。固定 frame-rate tag 不能代替这项验证。平台仍可能编码掉帧，这时失败文件不发布。满两小时文件的完整索引扫描可能使收尾比短文件更慢。

## 最短时长与生命周期

仅在 `MediaRecorder.start()` 成功返回后立刻取 elapsedRealtime，进入 RECORDING。准备、相机打开、录制回调和 MIC 配置等待均不算录制起点。

用户停止用 **手势时间 requested_at** 与真实开始时间比较：差值 <10000ms 立即 IGNORE，无延迟消息、无排队停止；刚好 10000ms 可停。用手势时间可防止 9999ms 的手势因投递延迟而在接收时被升级为合法停止。STARTING/STOPPING 阶段的重复 toggle 忽略。安全/故障停止不受 10 秒约束，故障保留下来的有效文件可能短于 10 秒。

MIC 使用 `AudioSource.MIC` + AAC 48kHz mono/128kbps，默认总是带环境声音，不支持悄悄退为无声视频。每 500ms 检查 recorder 自己的 `getActiveRecordingConfiguration`：被 silenced 立即收尾，初始未知配置最多等 3 秒，验证成功后配置丢失最多等 1 秒；没有验证过自己的 MIC 就不发布。相机错误/断连、录制错误、帧率不达标、用户切换/后台、权限/AppOps 撤销、MIC 静音、onDestroy 都进入同一工作线程收尾。亮灭屏不触发停止。隐私由 system_server 预检、Camera2 平台执行和 MIC 自身 silenced 配置联合处理；不监听应用无权观察的隐私接口。

独立 HandlerThread 串行拥有 recorder、session、camera、FD 和 MediaStore。主线程 15 秒启动 watchdog，5 秒 worker 健康检查超时后发停止；收尾超过 10 秒释放服务和有界 wake lock，阻塞的 native 调用仍持有进程资源门，不会在另一线程强关 FD。门必须等 recorder/文件清理及 Camera2 `onClosed` **都完成** 才开放；销毁后迟到的 onOpened/onConfigured 会立即关闭，回调不会被批量删除。HAL 永不回调时保守保持门占用，需进程重启恢复，避免并发误用。

## 文件与失败

输出为 `MediaStore.Video` 的 external primary、`DCIM/Camera/HC_VIDEO_*.mp4`、`video/mp4`。insert `IS_PENDING=1` → 写 FD → recorder.stop 成功 → 轨道/尺寸/实际 60fps/MIC 校验 → `IS_PENDING=0`。没有成功 start、stop 抛错、无 MIC、错误帧率或无效轨道均删除 pending，不发布坏文件。普通断连、用户切换、销毁等可保留已完成且通过校验的部分文件；MediaRecorder 致命错误直接丢弃。

每次新录制以 owner_package_name、精确路径及专属前缀清理最多 64 条本应用遗留 pending。杀进程无法执行 finally，遗留行保持 pending，下一次启动回收；provider delete 本身失败则保留 unpublished 行并明确日志/通知，不声称已删除。已有 published 文件不进入回收。

## 验证

```sh
python3 tests/video-recording-service/run.py \
  --android-sdk /workspace/camera-service-check/android-35-classes
```

使用实际公开 Android SDK API 35 类编译全部视频生产 Java，不以自制 Android stub 替代 API。host JVM 单独执行 Android-free 生产策略和资源门：53 项请求/去重/10s/延迟投递/失败停止/60fps 检查，15 项清理先后顺序、迟到回调、非 owner 与重复回调检查，17 项主摄直接/物理路由和结果目标检查。

`main-camera-metadata.properties` 从已有只读 dumpsys 提取最小静态 fixture：8 个设备、2 个 normal/public（0、1）；logical 后摄 0 role64 的 physicalIds 为 3、2、4，物理 2 role0；0 和 2 均具有 `[60,60]`、PRIVATE 1920×1080、16,666,666ns 最短帧时长。回归确认选择 openId=0、physicalId=2；物理 2 如果直接公开则优先直接打开。fixture 不含客户端、events、会话日志、时间戳或设备标识。host 测试不声明已验证 HAL、音频权限、锁屏 FGS 豁免或文件实录。这些需取得用户授权后在目标机验证；本任务没有调用任何真实设备摄录。


## 同时单击修正版

基于实际板载输入证据修复：gpio-keys 仅上键，pmic_resin 仅下键，不能要求同deviceId。同一用户下两键按下间隔不超过120ms、完整松开、每键短按小于500ms，一次组合触发一次toggle。旧单键拍照和录音仍保留，十秒内停止直接忽略。

本机主摄结果不声明 CONTROL_AE_TARGET_FPS_RANGE，旧版强制读取会误失败。现按availableResultKeys决定echo是否必须存在，物理输出固定、时间戳持续60fps及最终成片帧率验证仍然保留。没有physical FPS独立控制键时使用普通请求builder，避免未更新的physical template覆盖logical配置。

应用私有 video-diagnostics/current.log 与 previous.log各32KiB，记录固定阶段和异常种类；不记录摄录内容、文件URI、设备或用户标识。未把host复现及构建成功当作实机录制通过。

## 预检 getKeys ROM 崩溃修复

安装包 R8 后的 `jy0.a` 有两条不同的只读预检失败证据，不能混为一谈：root UID 调用 `CameraCharacteristics.getKeys()` → `CameraMetadataNative.getExtendedSceneModeCapabilities()` 抛空指针；`setuid(10266)` 的 app_process 配合目标包 Context 调用旧 selector 则抛 `IllegalStateException: No role=0 rear MAIN accessible directly or through a public logical camera`。后者数值 UID 与目标 app 相同，但保留 root SELinux 上下文，**不是完整 app sandbox**。该探针中 `getKeys()` 不枚举 Xiaomi role vendor 字段，而直接 typed 读取能够取得这些值，说明是枚举过滤，不能解释为 metadata 中不存在角色。两者都在 CAMERA_SELECTED 前失败，不能据此归咎于会话/摄录。root 路径还说明该枚举会计算无关派生元数据。现在完全不调用该枚举，只使用已知 Xiaomi vendor tag 的 typed key：`cameraIds` 读取 `int[]`，数组存在（含空数组/不含0）时绝不被 scalar 覆盖；只有数组缺失才读取 `cameraId`。已有只读实机 metadata 确认 `cameraId (0x81a70000)` 为 `int32[1]`，专用 helper 兼容 Android `Integer` 与单元素 `int[]` 表示，不接受多元素 scalar。`cameraIds (0x81a70001)` 为 `int32[N]`。未注册 vendor tag 的 IllegalArgumentException 视作 absent；其他损坏/权限错误不当作合法 scalar 后备。

只对后摄构造角色描述；前摄的无关 vendor/stream 元数据不读。已验证存在直接 MAIN 时不查询隐藏 physical characteristics。否则仅查询公开后置 logical parent 指明的 physical IDs；逐相机 metadata/角色/physicalIds 故障单独记固定诊断，不能让无关镜头故障否决已验证的 MAIN。所有可用候选仍按既有唯一性/角色优先级选取，MAIN 或 carrier 歧义拒绝。若当前 app UID 无法读取物理 MAIN 的 metadata 且无可验证的直接 MAIN，仍以 MAIN_UNAVAILABLE 失败，不按 ID/焦距猜测，更不以 logical role64 代替 MAIN。选中的主摄和 carrier 的 orientation、结果键、流与编码器错误仍是失败，不吞掉、不降到30fps；物理输出固定及实际帧率验证不变。

私有诊断新增 CAMERA_IDS、CAMERA_ROLES、CAMERA_PHYSICAL_IDS、CAMERA_ROUTE、CAMERA_SELECTED_METADATA、CAMERA_STREAM、CAMERA_ENCODER、CAMERA_PREFLIGHT_READY 阶段；跳过故障记录 CAMERA_METADATA_SKIPPED / CAMERA_ROLES_SKIPPED / CAMERA_PHYSICAL_IDS_SKIPPED。固定失败枚举补充 MAIN_UNAVAILABLE、MAIN_AMBIGUOUS、CARRIER_AMBIGUOUS、STREAM_UNSUPPORTED、ENCODER_UNSUPPORTED，以及 NULL_METADATA/TYPE/LINKAGE 异常分类。每行依然只有单调时钟、枚举阶段、枚举原因，不记录实际 camera IDs、角色数值、异常消息或堆栈。

`run.py` 首先将所有视频生产源码对真实 API35 编译；之后隔离的 `select_fixture.py` 提供可执行 host metadata fixture，直接调用真实生产 `VideoCameraConfig.select`（不是仅测 policy）。fixture 的 getKeys 按实际调用链抛 extended-scene NPE，另覆盖不同 UID 可能的 SecurityException/IllegalArgumentException/LinkageError，并断言生产路径从未调用 getKeys。覆盖 typed role 优先级/表示、隐藏 metadata 拒绝、无关镜头故障、唯一性、严格普通 1080p60/encoder 与分阶段私有诊断。host fixture 不是 Android 生命周期/HAL 测试；未操作设备摄录、签名安装、hook、settings 或构建配置。

### 数值 app UID 的只读预检补证

`/workspace/video-runtime-evidence/role-app-result.txt` 的 RoleProbe 显示当前探针 `getCameraIdList()` 为 0..5：2 为直接后摄，typed `cameraIds=[0]`；0 和 5 都是包含物理 2 的 logical 后摄，角色数组分别为 `[64]` 和 `[60,62,61,63]`。因此本次 context 下应选直接 MAIN 2，不应因多个 logical carrier 拒绝。前文 `main-camera-metadata.properties` 的 publicIds=0,1 是此前 dumpsys 提取的**另一份历史只读视图**，仍用于 physical-only 路由回归，不代表本次 app UID 探针实时公开列表。

`old-app-preflight-result.txt` 保存旧安装包 selector 的 NO_MAIN；`typed-preflight-app.txt` 与 `typed-preflight-root.txt` 显示新生产 selector 经 D8 编译后，数值 UID 10266 和 root UID 的 metadata+codec 只读探针都返回 `PREFLIGHT_OK openId=2 physicalId=null orientation=90 fps=1080p60`。这些是静态预检通过：探针未 openCamera、未创建 MediaRecorder、未打开 MIC，数值 UID 版本并未进入完整 app SELinux sandbox，故不证明完整应用权限/FGS/物理会话/实际录像通过。上述 IDs/UID 只用于测试和本地证据说明，不加入生产 app diagnostics。

新增 filtered-enumeration 回归对同一 0..5、direct2、logical0/5 fixture 分别模拟 `getKeys()` 空列表和仅 standard keys：test-only 旧角色枚举算法两者均报 NO_MAIN，真实新生产 `VideoCameraConfig.select` 两者均通过并优先直接 MAIN 2，且不调用 getKeys、不校验无关 logical carrier 流。连同原有 ROM NPE 等场景，selector 回归现为75项。
