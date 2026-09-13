# 1.2.1 检查问题修复计划

范围：用户已确认修复四项检查发现，保留现有 UI、数据、手机直连 AI 和许可。不提交、不推送，不清除正式应用数据。

- [x] 删除学期：同时废弃内存、磁盘草稿及在途任务；保存前再次检查目标学期；数据库拒绝写入不存在的学期。
- [x] 取消识别：每次请求独立取消信号，排队、读文件后、创建连接时均检查；取消 Future 并断开已创建的连接。已发送到服务商的内容不能撤回。
- [x] AI 配置测试：编辑三个字段、切页或开始新检查即让旧结果失效；结果只能对应当前页面与输入；保存状态区分已测试与未测试。
- [x] CSV：按逻辑记录计算行数，超过2000行或100列报错；不将末尾换行当作额外行；保留引号内换行和转义引号。
- [x] 先运行失败回归，再实现；复跑纯 Java、隔离安卓本地 HTTP、隐私检查、构建与 Lint；独立审查；生成1.2.1包。

文件：ImportController.java、AiClient.java、新 RequestCancellation.java、MainActivity.java、ScheduleDb.java、Sheets.java；tests/CsvLimitsTest.java、app/src/androidTest/java/com/kejian/app/ReviewRegressionTest.java。

验收：旧结果不能进入已删学期；取消排队请求后本机接口收到0次请求；编辑配置后旧成功结果不能覆盖“需重新测试”；CSV超限明确抛出IOException。测试安装使用独立applicationId，不包含真实密钥或课表。

## 验证结果（2026-09-13）

- 修复前：安卓5项失败，CSV在2001行用例失败，证明能够复现。
- 修复后：安卓扩展到10项，全部通过；2171项纯Java检查通过；5项隐私检查通过。
- 正式包构建成功；Lint 0错误、20条已有警告，没有声称零警告。
- APK核验：com.kejian.app / versionCode 6 / versionName 1.2.1 / 个人课表。独立代码复核无明确新增缺陷。
- 未进行真实付费API测试或用户手机验收；文件读取中的取消、配置切页等组合未逐一做设备自动化覆盖。已发出的服务商请求不能撤回。
- 未修改用户数据库，未提交或推送代码。测试运行说明见 tests/REVIEW-TESTS.md。
