# 1.2.1 回归测试

纯 Java 测试：运行 `tests\run-pure-java.cmd`，包含 CSV 超限、精确边界、尾部换行和引号处理。

安卓测试必须使用**隔离包名**；测试会写虚构课表、虚构密钥，只请求模拟器自己的 loopback 临时 HTTP 服务。不要修改测试包名为正式应用。测试不验证真实服务商额度或识别质量。

在项目根目录执行（先设置 JAVA_HOME，并将 Android SDK platform-tools 加入 PATH）：

```powershell
.\gradlew.bat -I tests/reviewfix.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.kejian.reviewfix20260913.test/com.kejian.app.ReviewRegressionTest
```

以输出的 `PASS` / `FAIL` 统计判定结果；`adb` 的退出码不能代表测试全部成功。目标非 QA 包名时测试拒绝运行。

**测试后必须重新构建正式应用**，否则输出目录中的 APK 仍是隔离测试包：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

正式应用包名应为 `com.kejian.app`。当前输出为 debug 签名自用安装包，并非正式 release 签名包；不要为升级更换签名。发布前建议备份课表。
