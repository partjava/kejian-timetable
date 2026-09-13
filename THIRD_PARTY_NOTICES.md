# 第三方组件

本工程自身按 [LICENSE](LICENSE) 授权（私人使用，禁止商用）。下面列出其中用到的第三方内容及其
各自的许可证，这些许可证只覆盖对应组件，不改变本工程整体的使用许可。

- **xlrd 2.0.2**：用于读取旧版 XLS。源码随 `server/vendor/xlrd` 分发，许可证保留于 `server/vendor/xlrd-2.0.2.dist-info/LICENSE`。来源：PyPI 的 xlrd 2.0.2 发布包。
- **Gradle Wrapper 9.5.0**：由本机 Gradle 的 `wrapper` 任务生成。启动脚本保留上游版权和许可证说明。构建时还会使用 Android Gradle Plugin 9.3.2 和 Android SDK；这些不随工程源码分发。
- **安卓应用运行时**：只使用 Android 平台原生组件，没有附带第三方 UI 库。APK 里出现的 `kotlin/` 条目来自 Android Gradle Plugin 的工具链，不是本工程引入的依赖，业务代码中也没有 Kotlin 源码。
