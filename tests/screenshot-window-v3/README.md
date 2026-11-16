# 截图窗口 v3 与截图隐私 host 回归

在任意位置运行，需 Python 3 和支持项目 Java 语法的 JDK（本次验证使用 JDK 25）：

```sh
python3 -B tests/screenshot-window-v3/run_all.py
```

脚本通过自身路径定位当前 checkout，使用 `JAVA_HOME` 或 PATH 中的 `javac`/`java`；编译产物自动清理，报告默认放在系统临时目录，也可以设置 `HC_TEST_OUTPUT_DIR`。不需要 Android SDK、网络、ROM、私人工作区或设备访问。

`run_island.py` 编译当前生产窗口 hook、policy，以及从当前 SystemFrameworkB 提取的原始注册条件。覆盖四开关组合、默认关闭、嵌套截图作用域、已有 excludeLayers 合并、SurfaceControl 生命周期、精确岛窗口身份与异常回退，并执行 9 个 mutation 检查。`v2-exclusion.java.txt` 是旧版生产 Java 的只读测试 fixture，用原样 pin 回归断言对照 v2 与当前 v3；不是反编译 ROM。

`run_tests.py` 编译当前回调拦截及隐私相关生产 Java，使用有界 Android/BaseHook 模型执行回调测试，保留上游断言。默认不执行需外部 ROM 的签名检查；可选 `--rom /path/to/decoded-rom`，ROM 不随工程提供。`run_all.py` 还执行相邻 `screenshot-recorder-privacy` 的真实生产 Java 编译与录屏/命名回归。

host fixture 不等于 Android/Xposed/SurfaceFlinger 或完整 APK 构建，不验证真实像素、Binder、录屏文件、后台相机或真机生命周期。
