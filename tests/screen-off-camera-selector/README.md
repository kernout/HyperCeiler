# 熄屏相机 selector / service 入场检查

从工程根目录执行，需要 JDK 17+ 和 Python 3：

```sh
python3 tests/screen-off-camera-selector/run.py \
  --android-classes /path/to/extracted/android-35-classes \
  --hidden-api-jar /path/to/hiddenapibypass-6.1.jar
```

`--android-classes` 是从有效的公共 Android API 35+ `android.jar` 解出的 classes 目录，需包含 `android/app/Service.class`；`--hidden-api-jar` 是 HiddenApiBypass 6.1 AAR 中的 `classes.jar`。这些外部依赖不纳入工程，脚本不下载依赖、不连接或修改手机，编译产物位于执行结束自动清理的临时目录。

本地复核环境可使用：

```sh
python3 tests/screen-off-camera-selector/run.py \
  --android-classes /path/to/extracted/android-35-classes \
  --hidden-api-jar /path/to/hiddenapibypass-6.1.jar
```

脚本通过 `javac --release 17 -encoding UTF-8` 同时编译全部四个生产 camera Java 源文件与以下测试，然后用 `java` 执行：

- `ScreenOffCameraSelectorTest`：17 项生产纯函数检查，覆盖标量 / 数组角色优先级、冲突角色及四个设备方向与取整边界。
- `ScreenOffServiceAdmissionTest`：9 项生产 `ScreenOffCaptureService.isFreshPrivacyCheck()` 检查，覆盖未预检、负时间戳、未来时间戳、零时刻、2500 毫秒包含边界、2501 毫秒过期，以及 `Long.MAX_VALUE` 边界。该文件从既有 service 审阅测试原样复制；执行的是实际生产方法。

预期输出：

```text
PASS production camera sources compile (public Android API classes)
PASS: 17 production selector/orientation checks
PASS: 9 production service admission freshness checks
```

测试只执行纯 Java 方法，不运行 Android SDK stub 方法，也没有实例化或运行 Service。它不模拟 Camera2 / MediaStore / Binder / 权限 / FGS / 通知亮屏策略，不证明完整相机选择流程、真实 HAL、APK 构建或真机拍摄成功。完整使用范围与限制见 [中文说明](../../docs/screen-off-camera.md)。

Hook 回归另行运行：

```sh
python3 tests/screen-off-camera-hook/run.py \
  --android-classes /path/to/extracted/android-35-classes
```

当前预期是公共 Android API 编译通过和 `PASS ScreenOffCameraHookTest: 145 assertions`；详见 [Hook README](../screen-off-camera-hook/README.md)。
