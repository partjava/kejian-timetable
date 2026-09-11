# 验证记录

## 1.2.0 课表交互改进

本次改动：卡片左滑删除、删除学期、同名课程同色、30色色板与取色器、今日白色磨砂、待补充红点、周次点格子。数据库结构和备份格式未变，旧备份仍可恢复。

已通过：

- `gradlew.bat :app:assembleDebug :app:lintDebug` 成功（移除插桩测试前后都验证过；移除前还带 `:app:assembleDebugAndroidTest`）。
- 独立 Java `CourseColorsTest`：2084项检查通过。覆盖色板30格无重复且全部形如 `#RRGGBB`、原有6个种子色都在色板内、`valid()` 拒绝缺 `#`／位数不对／非十六进制字符、同名取色稳定、200个课程名能铺满全部种子色，以及2000个课程名连续取色不抛异常（含哈希最高位为1的名字，这是原先 `int(hex[:8],16)` 移植到 Java 后会下标越界的那一半）。
- `ScheduleRulesTest` 14项、`XlsReaderTest` 30项仍通过，说明周次的保存路径没有改变。
- Android Lint：0错误、19条非阻断提示，与改动前同一条目集合（本次新增的 `ClickableViewAccessibility` 与 `RtlHardcoded` 已在 `SwipeRow` 上按理由显式抑制：左滑对读屏不可达属已知降级，RTL 手势镜像不在范围内）。

**已移除真机插桩测试。** 应要求删掉了 `app/src/androidTest`（`SmokeTest`、`CleanStartTest` 及清单），并从 `app/build.gradle` 去掉 `testInstrumentationRunner`。理由是它需要额外安装一个测试 APK（`com.kejian.app.test`）。为本次改动新写的插桩检查（颜色一致、删学期不留孤儿行、色板与周次格子计数）尚未执行就随之删除，因此**本轮没有任何检查是在真机或模拟器上跑过的**。本文件下方的 `SmokeTest` / `CleanStartTest` 记录是当时的历史结果，现无对应源码，无法复跑。

**未验证**：左滑手感、白色磨砂观感、红点位置这些只能人工确认的项目都没做。1.2.0 的真机验收（含删除学期前先备份）待执行。可自动化的部分现在只剩纯 Java 测试与构建/Lint。

日期：2026-09-11。

## 1.1.0 手机直连 AI

1.1.0 起导入不再依赖电脑：App 自己读取 XLS/CSV，并用「AI 配置」里填写的地址、模型和密钥直接调用 AI。密钥用 Android Keystore 加密只存本机。

已通过：14项规则检查、30项 XLS 读取检查、5项隐私与密钥检查、47项模拟器检查，以及独立测试安装的空白启动检查（默认学期存在、课程0条、待补充0条、无课表资源）。

XLS 读取器另用 `server/vendor/xlrd` 做了独立对照：对本机 72 个真实课表文件（三个学期、不同班级）逐格与逐合并范围比对，`identical: 72 mismatched: 0`。对照脚本和被比对的课表文件都不随工程交付。

**未验证**：真实密钥 + 真实文件的完整导入链路。自动化测试绝不调用付费模型，这一项必须手工执行。

日期：2026-09-11。模拟器上保留着真实课表数据（16门课程），空白启动检查因此改用独立测试包名执行，未清除该数据；每次验证后都确认过 `seeded=true` 与学期仍在。

## 1.0.1 空白版本说明

1.0.1 已通过：3项隐私资源检查、11项服务测试、37项模拟器检查，以及独立测试安装的空白启动检查（默认学期存在、课程0条、待补充0条、无课表资源）。模拟器检查比旧版少1项，是空课表没有现成课程可打开详情；不将跳过的详情检查算为通过。导入链路改用虚构 CSV，未调用外部 AI。正式包已重新 clean 构建，应用ID com.kejian.app，versionCode 2，versionName 1.0.1，Lint 0错误；APK 不含 assets 条目。测试使用独立应用ID，没有清除旧 App 数据。

下方为 1.0.0 历史测试记录，不代表新版每项均已复测。旧记录中提到的原始文件、截图不再随源码交付。覆盖安装不删除旧课程，初次空白验收需全新安装或由用户明确清除存储后进行。

日期：2026-09-11。环境：Windows、Android Studio 内置 JDK 25、Gradle 9.5.0、AGP 9.3.2；720×1280 安卓模拟器，API 37。

## 构建

`gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain`：成功。

Android Lint：0错误、12警告。非阻断警告包含较新版本提示、只提供中文文本、Canvas绘制时分配对象、布局编辑器构造函数以及主题重复背景；本次没有用基线隐藏错误。Java编译器对部分原生兼容API有弃用提示。

## 规则与服务

- 独立 Java `ScheduleRulesTest`：14项检查通过，覆盖不连续周次、排序去重、非法范围、相邻节次、周末冲突。
- 独立 Java `XlsReaderTest`：30项检查通过，覆盖单字节与 UTF-16 字符串、富文本与注音跳过、CONTINUE 分片续接、合并范围、空占位、数值记录、公式字符串、非法输入拒绝。夹具在测试内现场生成，不使用任何真实课表文件。
- Python `python -m unittest discover -s tests -v`：5项通过，断言私有样本不会回来、旧截图已清空，并且源码树中不含 API 密钥字面量、App 代码不再指向已停用的电脑端服务。
- Python `python -m unittest discover -s server -v`：11个测试通过。该服务已不由 App 调用，测试针对保留的独立实现。
- 真实样本：10个课程名、16项合并后安排、2项缺少具体时间的待补充事项；开学日期2026-09-07，共20周。

## 安卓模拟器

`adb shell am instrument -w com.kejian.app.test/com.kejian.app.SmokeTest`：47项检查通过。

- SQLite新建、编辑、删除、不同学期隔离。
- 同名课程不同教室/周次保存，不连续周次保留。
- 重复导入去重、待补充事项去重。
- 备份恢复作为新学期导入，非法备份事务回滚。
- 手机上读取虚构CSV、生成提示词内容、校验一份形如模型回复的结果，再写入隔离测试数据库并验证二次导入幂等。全程不联网。
- 校验器拒绝非法颜色和字符串周次；未配置密钥时导入页给出明确提示。测试不调用付费外部模型。
- 七天/五天切换、开关持久保存、隐藏周末不删除任何课程。
- 第13–14节导入项在12节配置下可完整编辑；1节配置的新课程默认值合法。
- 空标题待补充项可以打开编辑器，由保存阶段校验。
- 课表、今日、课程列表、详情、编辑、学期、作息、设置、导入、AI配置页面可打开并截图。

自动化数据库测试使用专门命名的临时测试数据库，不清空用户课表。

### 空白启动检查（源码已于 1.2.0 移除，以下为历史步骤）

`adb shell am instrument -w <测试包>/com.kejian.app.CleanStartTest`：通过（默认学期存在、课程0条、待补充0条、无课表资源）。

这项检查的前提是**全新安装**，所以不能在已有数据的安装上执行——它会断言课程数为0，覆盖安装则会误报「Unexpected preloaded courses」。步骤：

1. 临时把 `app/build.gradle` 的 `applicationId` 和 `app/src/androidTest/AndroidManifest.xml` 里两处 `android:targetPackage` 一并改成同一个测试用包名（例如 `com.kejian.cleanqa<日期>`），构建。改文件时注意不要写入 UTF-8 BOM，否则 Gradle 会在 `build.gradle` 第 1 行报 `Unexpected character`。
2. `adb uninstall <测试包名>` 后重新安装两个 APK，再执行上面的 instrument 命令。
3. 改回 `com.kejian.app` 并重新构建，恢复正式包。
4. 若要复核正式安装未被影响，重跑 `SmokeTest` 并确认课程数据仍在。

`targetPackage` 必须写具体包名，不能用 `${applicationId}`：在 androidTest 清单里它展开成**测试**包（`com.kejian.app.test`），会让 `getTargetContext()` 指向错误的 App。

旧版（1.0.2 及更早）曾手动验证系统文件选择器：Downloads选择原始XLS → 确认上传 → 显示16项结果/2项待补充 → 确认保存时显示跳过16项重复安排。该链路依赖已停用的电脑端服务，1.1.0 需按下节重新手工验收。

## 尚未验证的边界

- **真实AI API、账户额度和具体服务商兼容性**：需填入有效密钥后联调。自动化测试不调用付费模型。
  - 已在真机验证（HONOR 200 Pro / ELP-AN00，Android 16，真实 DeepSeek 密钥，2026-09-11）：「测试连接」返回「连接成功：地址、密钥和模型都可以使用」；「获取模型列表」从 `https://api.deepseek.com/chat/completions` 推出 `/models` 并列出 `deepseek-flash`、`deepseek-v4-pro`，点选后正确回填模型名，再测试连接仍成功。旧名 `deepseek-chat` 目前作为别名仍然可用，故默认值未改。
  - **仍未验证**：完整导入链路（选文件 → 读取 → AI 识别 → 核对 → 入库）尚未用真实密钥跑通，见上一节。
- 各服务商的 JSON 输出模式兼容性。请求里带了 `response_format:{"type":"json_object"}`，不支持的接口会在「测试连接」时明确报错。
- 其他真实手机、旧版安卓系统、超大字体和所有横屏尺寸；当前最低兼容API声明通过Lint，但不等于已在所有设备实测。
- PDF、课表截图及任意学校格式的通用离线识别不在此版范围。XLS 读取器已对 72 个真实文件验证，但换一种导出工具仍可能引入未覆盖的记录类型；此时会明确报错，不会静默返回空课表。
- 换机恢复或修改锁屏凭据后 Keystore 解密失败的路径，已按「清除并要求重填」实现，但未在真机上实际触发验证。
- 当前按学期周次排课，不自动查询节假日或处理临时调休，临时安排可手动编辑。

## 复测

1. 执行上述Gradle构建命令（1.2.0 起不再有 `:app:assembleDebugAndroidTest`）。
2. 安装 `app/build/outputs/apk/debug/app-debug.apk`。1.2.0 起只需这一个 APK。
3. 执行上述instrument命令。
4. 空白启动检查按上一节换测试包名单独执行，不要在有数据的安装上跑。
5. 手工验收导入：在「AI 配置」填入真实地址、模型和密钥 → 点「获取模型列表」核对模型名 → 「测试连接」→ 保存 → 「智能导入」选真实 .xls → 核对结果 → 保存。把结果记到本节上方。

**不要在 `am instrument` 里验证网络。** 在 Android 16（API 36/37）上，插桩进程拿不到默认网络：`dumpsys connectivity` 里 `Package add: uid=<uid>, nPerm=(NONE/NONE), tPerm=INTERNET`，于是 `InetAddress.getByName()` 一律抛 `UnknownHostException: No address associated with hostname`。同样的代码在正常启动的 App 进程里工作正常——真机上点「测试连接」成功即为证据。因此网络相关的验收只能走真实 App 的 UI，用 `uiautomator dump` + `input tap` 驱动。同理，`run-as <包名> <命令>` 也不能用来测 App 的网络：它只切换 UID，套接字的 netd fwmark 仍继承 shell，量到的是 shell 的网络。

测试截图保存在模拟器 `/sdcard/Android/data/com.kejian.app/files/qa-captures`，可通过 `adb pull` 取回。
