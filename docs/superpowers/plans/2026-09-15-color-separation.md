# 配色优化 Implementation Plan

**Goal:** 新导入课程优先使用视觉差异大的颜色；旧课表仅经预览确认后改色。

**Architecture:** CourseColors 共享渐变端点与差异评分；Course JSON 保存 colorManual，缺失时保护。ScheduleDb 生成只读预览并事务应用；MainActivity 调用独立预览控制器。颜色改变不影响课程安排。

**Tech Stack:** Java / Android Canvas / XML / SQLite JSON。

- [x] 更新 CourseColorsTest 的同色系优先断言为最大最小显示色距离断言，运行 tests/run-pure-java.cmd，确认旧实现失败。
- [x] CourseColors.gradient(int) 返回左右端点和强调色，pick 选择与已用色最小距离最大的候选；TimetableView 使用共享算法。
- [x] Course.colorManual 默认 false，from(JSON) 缺失默认 true；copy/json 保留。手选整组标记；导入新标题强制自动、已有标题继承。
- [x] 独立 ColorOptimization 控制器以课程名勾选生成新旧渐变预览；默认不选保护项；取消零写入，确认事务保存，并校验预览未过期。
- [x] 今日高亮遵循设置文案，不改变渐变透明度。
- [x] Java 分配测试、隔离 Android 数据库/像素测试、Python 检查和生产 assembleDebug/lintDebug。

验收：确定性、大小写无关、完整色板耗尽降级、手选保护、旧数据兼容、同名继承、取消不写入、事务保存、重复重绘一致。
