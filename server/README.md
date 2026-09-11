# 课间导入服务（已不由 App 调用）

> **1.1.0 起 App 不再依赖此服务。** 手机端已内置 XLS/CSV 读取和直连 AI 的能力，导入全程不需要电脑。
> 此目录保留作为独立的命令行解析工具与参考实现：它的 `validate_result` 是 App 内 `ImportValidator` 的移植来源，
> 它的提示词是 `AiClient.PROMPT` 的来源，附带的 `xlrd` 还用于对照验证 App 的 XLS 读取器。
> 下面的说明描述的是这个服务本身，仍是准确的，但不再代表 App 的工作方式。

需要 Python 3.10 或更新版本。已附带 xlrd 2.0.2，可直接双击 `start-server.cmd`；启动器会优先使用本机已有的虚拟环境或已安装的 Codex 配套 Python，也支持 `KEJIAN_PYTHON` 指定解释器路径。使用独立 Python 时，命令行切换到此目录后执行：

```powershell
python -m pip install -r requirements.txt
python server.py
```

也可双击 `start-server.cmd`。看到 `listening` 才表示启动成功。默认只监听本机 `http://127.0.0.1:8765`。Android 模拟器连接电脑使用 `http://10.0.2.2:8765`。USB 真机连接电脑并开启 USB 调试后，执行 `adb reverse tcp:8765 tcp:8765`，应用填写 `http://127.0.0.1:8765`。应用不接受局域网明文 HTTP 地址；远程使用须通过 HTTPS 反向代理，并另行配置鉴权、限流等部署措施。此服务默认没有用户鉴权，交付版本用于本机开发演示，不要直接暴露公网。

规则解析支持 XLS、XLSX、UTF-8/GB18030 CSV。规则识别星期标题和 `课程/(1-2节)1-3周,5周/教室/教师/备注` 格式；无法确定的已识别课程内容进入待确认，包括未识别星期列中的课程。它不是通用 Excel 布局识别器。XLSX 使用标准库读取单元格与合并范围；不计算公式、不执行宏。结果明确标为本地规则解析。XLS/XLSX 最多 16 张工作表，每张最多 2000 行、100 列，所有工作表的矩形单元格范围总计最多 200,000 格；稀疏表格也按完整矩形范围计数，超过限制会拒绝解析。

AI 为真实远程请求，需自行配置兼容 Chat Completions 的服务，并确认模型支持图片（如需识图）。在启动服务的 PowerShell 中设置：

```powershell
$env:AI_API_URL="https://your-provider.example/v1/chat/completions"
$env:AI_API_KEY="替换为你的密钥"
$env:AI_MODEL="替换为模型名称"
python server.py
```

地址必须为完整 HTTPS `/chat/completions` 地址。密钥只保留在服务端环境变量，不写入 APK。AI 模式将上传的表格文字及坐标，或原图片，发送到所配置的服务。上传仅在内存处理、不保存文件；AI 厂商自身的数据保留策略请查看其说明。失败会返回错误，不自动改成规则模式。请求超时 90 秒。AI 返回结果经过字段类型、日期格式及数值范围校验，但仍需人工核对。

GET `/health` 返回 `{ "status": "ok", "configured": true/false }`。POST `/parse`，Content-Type 为 application/json，正文为 `{ "filename":"课表.xls", "contentBase64":"...", "mode":"rules" }`，mode 可选 `rules` 或 `ai`。单文件最多 8 MB。错误返回相应 HTTP 状态码和 `{ "error":"中文说明" }`。

成功结果包含 `courses`、`pending`、`semester`、`warnings`、`mode`。courses 字段为 title、teacher、room、day（1—7）、start/end（1—16）、weeks（1—40 的整数数组）、color（#RRGGBB）、notes。pending 只有 title 和 notes。semester 包含 name、startDate、totalWeeks。**无法识别日期时 startDate 为空字符串，应用必须要求用户确认；不虚构日期。** 未知总周数默认 20 并在规则模式提示检查学期。

测试：`python -m unittest discover -v`。测试只使用虚构内容，附带的 `fixtures/synthetic.csv` 可用于手动导入，不包含真实个人课表。
