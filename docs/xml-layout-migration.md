# XML 布局与 Java 代码对照

当前版本为 1.2.5（versionCode 10），最新代码已合并到 `main`。多个页面和复用卡片采用 XML + Java，课表网格仍由 `TimetableView` 自定义绘制。并非所有界面都已迁移到 XML。

## 在 Android Studio 中查看

在左侧 Android 视图展开 `app → res → layout`，打开文件后使用 Code / Split / Design 查看。XML 中能看到输入框、按钮、间距和固定结构，不再只有空容器。

| XML | 对应 Java | 用途 |
|---|---|---|
| `page_settings.xml` | `MainActivity.showSettings()` | 设置页的分组与设置项 |
| `item_setting.xml` | MainActivity 设置绑定方法 | 多个设置项共用的卡片结构 |
| `item_setting_toggle.xml` | MainActivity 开关绑定方法 | 周末显示、今天高亮开关 |
| `page_ai_settings.xml` | `ImportController.settings()` | API 地址、模型、密钥与操作按钮 |
| `dialog_course_editor.xml` | `CourseEditor.build()` | 添加和编辑课程弹窗 |
| `dialog_color_picker.xml` | `ColorPicker.open()` | 自定义取色弹窗 |
| `dialog_term_editor.xml` | `MainActivity.editTerm()` | 新建和编辑学期 |
| `item_course_card.xml` | MainActivity 课程卡片绑定 | 今日与课程列表的复用卡片 |
| `item_term_card.xml` | `MainActivity.showTerms()` | 学期列表卡片 |
| `item_color_preview.xml` | `ColorOptimization` | 配色优化前后的渐变色块 |
| `activity_main.xml` | `MainActivity` | 页面容器和底部导航容器 |

文件均位于 `app/src/main/res/layout/`。样式资源在 `res/values/layout_styles.xml`，文字、颜色、尺寸分别在同目录的 `layout_strings.xml`、`layout_colors.xml`、`layout_dimens.xml`；背景在 `res/drawable/page_*.xml`。

## 一次点击如何工作

以 AI 测试按钮为例：XML 定义 `@+id/ai_test` 的文字与样式，Java 使用 `findViewById(R.id.ai_test)` 找到它并设置点击监听。点击后仍运行原来的 `test(...)`，网络结果由原来的请求状态检查后显示到 `ai_status`。

资源文件不一定必须叫 strings.xml 或 colors.xml；Android 会合并 `res/values` 中所有资源 XML。这里采用 layout_ 前缀，使这一阶段的资源容易识别，也避免影响未迁移页面。

`<include layout="@layout/item_setting" ... />` 是复用布局。各设置项的外层 id 不同，Java 应先找到对应外层，再找内部标题和说明；不能直接从整页查找重复的内部 id。

## 为什么仍有 Java 界面代码

- 课表网格仍由 `TimetableView` 自定义绘制，本阶段不拆。
- 周次格子数量取决于学期周数、色块取决于色板；XML 定义它们的容器，Java 动态填充与更新选择状态。
- 共享页面标题和其他未迁移页面仍沿用原有写法。不要认为本阶段已经将整个应用全部改成 XML。
- XML 预览不执行数据库读取和 Java 数据绑定，所以动态文本、周次与颜色不一定显示完整，实际运行结果以应用为准。

设置与 AI 页面保留运行时 `layout_height="0dp"`、`layout_weight="1"`，通过 `tools:layout_height="match_parent"` 提供独立预览高度。设置卡片的 `tools:text` 仅用于预览，不会写入课表。主题字体使用 `sans-serif`，修正了此前 `sans` 导致的预览器字体加载问题；不同 Android Studio 版本的实际预览仍需本机确认。

## 1.2.5 颜色显示

`CourseColors.gradient()` 统一提供渐变端点与强调色，分配算法比较最终显示色的差异。`TimetableView` 使用独立画笔和缓存绘制：今日课程使用保存的原始纯色，其他日期渐变，关闭高亮则全部渐变；卡片不再有左侧竖条。

“设置 → 优化课程配色”先选课程，再预览，最终确认才写入。旧版或手选颜色默认保留；新 JSON 中的 `colorManual` 保存颜色来源保护标记。

## 回归测试

`tests/test_xml_layouts.py` 检查真实控件、唯一 id 和密钥输入属性。`XmlLayoutRegressionTest` 在隔离包中测试设置开关与导航、AI 配置保存、课程回填/保存/关闭/非法输入与数据库写入。

测试命令（配置好 JAVA_HOME 和 adb）：

```powershell
python -m unittest discover -s tests
.\tests\run-pure-java.cmd
.\gradlew.bat -I tests/xml-reviewfix.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e phase after com.kejian.reviewfix20260913.test/com.kejian.app.XmlLayoutRegressionTest
```

还应按 `tests/REVIEW-TESTS.md` 复跑原有 AI/导入回归。以 instrumentation 输出的 FAIL 数量判定结果，而非仅看 adb 退出码。

完成隔离测试后，不带 init 脚本重新构建：`.\gradlew.bat :app:assembleDebug :app:lintDebug`。检查包名是 `com.kejian.app`，再另存安装包，避免误发 QA 包。不要将测试截图、用户课表或密钥提交到仓库。

## 回退与数据

旧版本基准为 `ba9195f`（v1.2.1）；XML 拆分与后续更新已经合并并推送到 `main`，原 `refactor/xml-layouts` 分支已删除。旧代码仍能通过提交历史或标签查阅，删除分支不删除历史。回退前保存未提交改动和课表备份，不要强制重置丢弃修改。

APK 是否可覆盖安装取决于包名、签名及版本兼容性；不要为测试卸载原应用。安装前先备份课表。上传源码与发布 APK 是两个独立操作，发布时从 `main` 选择对应提交或标签。

## 首轮迁移历史记录（1.2.1，不是当前版本）

- 3 个页面、5 个布局文件已迁移；颜色、文字、尺寸、背景提取为独立 XML 资源。
- 8 项隔离界面测试通过，包含视图状态恢复时两个开关互不影响；该用例修复前失败、修复后通过。复用开关禁用视图状态保存，以 SharedPreferences 为唯一来源。
- 原有 10 项 AI/导入回归通过；2171 项纯 Java 检查与 10 项 Python 检查通过。
- 对比设置页、AI 页、编辑弹窗上下部截图；修正返回导入按钮的居中偏移。
- 构建成功。Lint 0 错误、41 警告（包含复用 include id、输入类型/自动填充/标签、旧 API 等，不等于零警告）。
- 安装包仍为 com.kejian.app、versionCode 6、versionName 1.2.1，原 debug 签名；这是 XML 拆分测试包，未声明为新正式版本。
- 没有调用付费 AI、修改用户数据库或覆盖旧 APK。未逐一验证全部真机型号、软键盘/横屏组合；模拟器通过不代表所有场景零缺陷。
