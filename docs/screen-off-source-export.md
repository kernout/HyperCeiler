# 息屏拍照、录音与录像源码索引

本分支保留完整 HyperCeiler 工程，以 `bc1db464fc93ad05fea5e8778ba3826339e4830a`（主摄录像预检修正版）为基础，汇总此前交付的拍照、录音、录像功能；不覆盖 main。

## 功能入口

- 拍照按键 Hook：`library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemframework/volume/ScreenOffCamera.java`
- 录音按键 Hook：同目录 `ScreenOffRecorder.java`、`ScreenOffRecorderState.java`；电源键入口见 `PowerDoubleClickRecorder.java`。
- 录像组合键 Hook：同目录 `VolumeVideoChord.java`、`VolumeVideoChordState.java`。
- 拍照执行端：`app/src/main/java/com/sevtinge/hyperceiler/camera/`。
- 录音执行端：`app/src/main/java/com/sevtinge/hyperceiler/recording/`。
- 录像执行端：`app/src/main/java/com/sevtinge/hyperceiler/video/`。
- 设置入口：`library/core/src/main/java/com/sevtinge/hyperceiler/hooker/framework/VolumeSettings.java` 及 `library/core/src/main/res/xml/framework_volume.xml`。

## 说明

拍照：息屏双击音量下键，支持请求排队与亮屏续拍。
录音：息屏音量上键手势，并保留工程中的电源键相关入口。
录像：音量上下键同时短按、完整松开一次切换，采用后置主摄 1080p60 + MIC 的预检修正版。

参见 `docs/screen-off-camera.md`、`docs/camera-queue.md`、`docs/screen-off-recorder.md`、`docs/native-power-recorder.md` 与 `docs/video-chord.md`。

本次为源码归档与索引，不是新的功能修改，亦不代表本次已编译或完成真机摄录验证。源码及第三方内容遵循仓库原有许可。
