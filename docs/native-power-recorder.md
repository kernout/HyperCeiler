# 原生双击电源键录音选项

本 ROM 的快捷手势页属于 `com.miui.securitycore`（`SecurityCoreAdd.apk`），因此 LSPosed 中除系统框架外，**必须启用 `com.miui.securitycore` 作用域并重启该宿主进程**。默认 scope.list 已加入此包。`com.android.settings` 作用域不能代替它。

打开系统快捷手势 → 自定义手势 → 双击电源键，在原生单选列表中选择“录音”（英文 Recording）。条目使用 `hyperceiler_recording_toggle`，继续交给现有系统框架 PowerDoubleClickRecorder 执行模块录音服务，不调用原生录音机。

与熄屏录音总开关 `system_framework_volume_screen_off_recorder` 共用 gate。关闭或重新开启开关不会改写既有双击绑定；需要应用/热重载模块配置后重新打开宿主页。切换为相机、手电筒、付款码或“无”仍使用原生单选互斥处理。

实现只在新宿主页的 `f2.t.j(1)` 支持表副本中加入 action，`q1.l.c` 仅映射自定义 key 的文案，在 `j2.b.p(root)` 前插入原生 SingleChoicePreference。原生 p/q/r 负责监听、互斥、保存与恢复。对方法参数、返回类型和字段做完整预检；不匹配时记录 `SecurityCore native power recorder` error 并放弃安装，不修改宿主设置。旧 Settings 下拉入口已移除。

测试及原 ROM ABI 验证见 `tests/native-power-recorder/README.md`。本页为实现说明；实际构建状态以配套 build-info 为准，按键执行效果仍需实机验收。

按键执行现使用 POWER_TOGGLE：亮屏开始的电源双击可以开／关共享录音，息屏音量上可互停，切换间隔 1 秒。息屏电源双击不录音。
