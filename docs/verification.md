# 验证记录

日期：2026-09-11。环境：Windows、Android Studio 内置 JDK 25、Gradle 9.5.0、AGP 9.3.2；720×1280 安卓模拟器，API 37。

## 构建

`gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain`：成功。

Android Lint：0错误、12警告。非阻断警告包含较新版本提示、只提供中文文本、Canvas绘制时分配对象、布局编辑器构造函数以及主题重复背景；本次没有用基线隐藏错误。Java编译器对部分原生兼容API有弃用提示。

## 规则与服务

- 独立 Java `ScheduleRulesTest`：14项检查通过，覆盖不连续周次、排序去重、非法范围、相邻节次、周末冲突。
- Python `python -m unittest discover -s server -v`：11个测试通过，包含真实XLS、XLSX单元格与合并范围、星期缺失、周次间隔、连续节合并、HTTP错误、AI响应格式、文件大小和工作簿总体范围限制。
- 真实样本：10个课程名、16项合并后安排、2项缺少具体时间的待补充事项；开学日期2026-09-07，共20周。

## 安卓模拟器

`adb shell am instrument -w com.kejian.app.test/com.kejian.app.SmokeTest`：38项检查通过。

- SQLite新建、编辑、删除、不同学期隔离。
- 同名课程不同教室/周次保存，不连续周次保留。
- 重复导入去重、待补充事项去重。
- 备份恢复作为新学期导入，非法备份事务回滚。
- 模拟器HTTP发送原始XLS至本机服务，校验返回，再将结果写入隔离测试数据库。
- 未配置AI时返回明确错误；测试不调用付费外部模型。
- 七天/五天切换、开关持久保存、隐藏周末不删除任何课程。
- 第13–14节导入项在12节配置下可完整编辑；1节配置的新课程默认值合法。
- 空标题待补充项可以打开编辑器，由保存阶段校验。
- 课表、今日、课程列表、详情、编辑、学期、作息、设置、导入页面可打开并截图。

自动化数据库测试使用专门命名的临时测试数据库，不清空用户课表。

另已手动验证系统文件选择器：Downloads选择原始XLS → 确认上传 → 显示16项结果/2项待补充 → 确认保存时显示跳过16项重复安排。已留存识别结果和确认界面截图。

## 尚未验证的边界

- 真实AI API、账户额度和具体服务商兼容性，需提供有效配置后联调。
- 其他真实手机、旧版安卓系统、超大字体和所有横屏尺寸；当前最低兼容API声明通过Lint，但不等于已在所有设备实测。
- PDF及任意学校格式的通用离线识别不在此版范围。规则模式针对当前教务导出格式；其他复杂表格可选择AI模式并人工确认。
- 当前按学期周次排课，不自动查询节假日或处理临时调休，临时安排可手动编辑。

## 复测

1. 先启动 `server/start-server.cmd`。
2. 执行上述Gradle构建命令。
3. 安装 `app/build/outputs/apk/debug/app-debug.apk` 和 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。
4. 执行上述instrument命令。

测试截图保存在模拟器 `/sdcard/Android/data/com.kejian.app/files/qa-captures`，可通过 `adb pull` 取回。
