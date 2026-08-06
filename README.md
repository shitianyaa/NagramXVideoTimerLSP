# NagramX Video Timer LSP

[![构建状态](https://github.com/shitianyaa/NagramXVideoTimerLSP/actions/workflows/build-release.yml/badge.svg)](https://github.com/shitianyaa/NagramXVideoTimerLSP/actions/workflows/build-release.yml)
[![最新版本](https://img.shields.io/github/v/release/shitianyaa/NagramXVideoTimerLSP?display_name=tag)](https://github.com/shitianyaa/NagramXVideoTimerLSP/releases/latest)
[![许可证](https://img.shields.io/github/license/shitianyaa/NagramXVideoTimerLSP)](LICENSE)

基于 [libxposed API](https://github.com/libxposed/api) 开发的现代 LSPosed 模块，为原版 NagramX 提供后台视频播放和定时停止入口。

当前版本：`1.0.0`

## 功能

- 在 NagramX 的视频设置菜单中提供“后台定时播放”入口。
- 支持播放 15 分钟、30 分钟或 1 小时后停止。
- 支持当前视频播放结束后停止。
- 支持取消当前定时器，同时保持后台播放。
- 优先调用宿主已有的后台播放与睡眠定时 API。
- 对缺少新版 API、但仍保留旧播放器转移入口的版本提供兼容路径。
- 只在 NagramX 主进程加载，避免影响其他应用和子进程。
- 自带状态页，可查看 LSPosed 服务、API、作用域和目标应用版本。

## 兼容范围

| 项目 | 要求 |
| --- | --- |
| 目标应用 | 原版 NagramX |
| 目标包名 | `nu.gpu.nagram` |
| NagramX 版本 | 不限制 `versionCode` |
| Android | Android 8.0 及以上 |
| LSPosed API | libxposed API 101-102 |

模块不会根据 `versionCode` 拒绝加载，而是在运行时检查 NagramX 的类、字段和方法签名。若目标版本结构发生变化，模块会记录日志并安全停止安装相关 Hook，因此“不限制版本”不代表所有历史或未来版本都保证可用。

如果宿主版本已经完整包含相同的后台定时播放 UI 和 API，模块会跳过重复注入。

## 安装

1. 从 [Releases](https://github.com/shitianyaa/NagramXVideoTimerLSP/releases) 下载最新 APK 并安装。
2. 在 LSPosed 中启用 `NagramX Video Timer`。
3. 确认静态作用域为原版 NagramX（`nu.gpu.nagram`）。
4. 强制停止并重新启动 NagramX。

## 使用

1. 在 NagramX 中打开一个普通视频并开始播放。
2. 打开视频设置菜单。
3. 点击“后台定时播放”。
4. 选择停止时间，视频将转入后台继续播放。

可选项包括：

- 播放 15 分钟
- 播放 30 分钟
- 播放 1 小时
- 当前视频结束后停止
- 取消当前定时器（仅在存在活动定时器时显示）

## 兼容策略

模块按以下顺序选择实现路径：

1. 宿主已有完整后台定时播放 UI/API：不重复注入。
2. 宿主保留 `startVideoBackgroundPlayback(int, int)`：注入菜单并调用宿主原生实现。
3. 宿主仅保留旧版播放器转移入口：由模块转移播放器并维护计时器。

第三种兼容路径依赖 NagramX 内部实现，稳定性低于原生 API 路径。若菜单没有出现，请先查看日志确认目标版本是否缺少必要签名。

## 日志

```powershell
adb logcat -s NagramXVideoTimer
```

日志会记录模块加载、宿主版本、签名探测、Hook 安装和播放器转移错误。

## 本地构建

环境要求：

- JDK 21
- Android SDK 37

在 `local.properties` 中配置 Android SDK：

```properties
sdk.dir=D\:\\Android SDK
```

执行完整检查：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest
```

构建产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

当前 Release 构建使用调试签名，仅用于模块分发和测试。更换签名会影响覆盖安装，请在自行构建时保持签名一致。

## 自动构建与发布

[GitHub Actions 工作流](.github/workflows/build-release.yml)会执行以下任务：

- 推送到 `main` 或提交 Pull Request 时，自动构建 Debug/Release APK，并执行 Lint 和单元测试。
- 推送与工程版本一致的标签（例如 `v1.0.0`）时，自动创建 GitHub Release 并上传 Release APK。
- 标签版本必须与 `gradle.properties` 中的 `MODULE_VERSION_NAME` 一致，否则发布任务会失败。

## 项目结构

- `ModuleMainKt.kt`：libxposed 模块入口和目标进程过滤。
- `HostProfile.kt`：宿主版本信息、类、字段和方法签名探测。
- `NagramXHooks.kt`：Hook 安装和生命周期接入。
- `PlaybackCoordinator.kt`：菜单注入、播放器转移和计时器生命周期。
- `HostBridge.kt`：宿主 ClassLoader 下的反射边界与错误处理。

## 免责声明

本项目是非官方模块，与 NagramX、Telegram、LSPosed 项目没有隶属关系。模块会调用目标应用的内部实现，升级 NagramX 前建议保留可回退的安装包，并自行承担使用风险。

## 许可证

[Apache License 2.0](LICENSE)
