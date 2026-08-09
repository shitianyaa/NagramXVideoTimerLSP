# NagramX Video Timer LSP

[![构建状态](https://img.shields.io/github/actions/workflow/status/shitianyaa/NagramXVideoTimerLSP/build-release.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=Build)](https://github.com/shitianyaa/NagramXVideoTimerLSP/actions/workflows/build-release.yml)
[![最新版本](https://img.shields.io/github/v/release/shitianyaa/NagramXVideoTimerLSP?style=for-the-badge&logo=github&logoColor=white&label=Release)](https://github.com/shitianyaa/NagramXVideoTimerLSP/releases/latest)
[![下载量](https://img.shields.io/github/downloads/shitianyaa/NagramXVideoTimerLSP/total?style=for-the-badge&logo=download&logoColor=white&label=Downloads)](https://github.com/shitianyaa/NagramXVideoTimerLSP/releases)
[![许可证](https://img.shields.io/github/license/shitianyaa/NagramXVideoTimerLSP?style=for-the-badge&logo=apache&logoColor=white&label=License)](LICENSE)

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#兼容范围)
[![NagramX](https://img.shields.io/badge/Target-nu.gpu.nagram-26A5E4?style=flat-square&logo=telegram&logoColor=white)](#兼容范围)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20101--102-F48FB1?style=flat-square)](#兼容范围)
[![Kotlin](https://img.shields.io/badge/Kotlin-JDK%2021-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#本地构建)
[![Changelog](https://img.shields.io/badge/Changelog-Keep%20a%20Changelog-E05735?style=flat-square)](CHANGELOG.md)

基于 [libxposed API](https://github.com/libxposed/api) 的 LSPosed 模块，为原版 NagramX 提供**后台视频播放**与**定时停止**入口。

> **签名迁移**：`1.1.1` 起使用固定正式签名。已安装 `1.0.0` 至 `1.1.0` 的用户需要先卸载旧模块再安装 `1.1.1`；此后版本可正常覆盖升级。

## 功能

### 定时与后台

- 在视频设置菜单中提供「后台定时播放」入口
- 支持 15 / 30 / 45 / 60 / 90 分钟，以及时/分滚轮自定义时长
- 支持「当前视频结束后停止」
- 支持取消定时器，同时保持后台播放
- 退出全屏后音频继续，定时器在后台照常倒计时

### 播放控制与界面

- 通知栏媒体会话：播放 / 暂停、上一个 / 下一个、进度显示
- 聊天页顶部迷你播放器：标题 + 剩余定时时长，点按回到全屏
- 播放列表面板：切换同一会话内的其他视频，并显示缩略图
- 定时菜单在激活状态下显示剩余时长并高亮

### 兼容与安全

- 优先复用宿主已有的后台播放与睡眠定时 API
- 旧版宿主优先走 PhotoViewer 原生「置顶播放」（PiP）链路
- 缓冲或边下边播时也可先开定时，首帧就绪后再切置顶播放
- 定时结束只暂停并保留置顶播放；取消定时不销毁播放器
- 仅在 NagramX 主进程加载；自带状态页可查看服务、API、作用域与宿主版本

## 兼容范围

| 项目 | 要求 |
| --- | --- |
| 目标应用 | 原版 NagramX |
| 目标包名 | `nu.gpu.nagram` |
| NagramX 版本 | 不限制 `versionCode` |
| Android | 8.0+（`minSdk 26`） |
| LSPosed | libxposed API 101–102 |

模块不会按 `versionCode` 拒绝加载，而是在运行时检查类、字段与方法签名。宿主结构变化时会写日志并安全停用相关 Hook，因此「不限制版本」不等于所有历史或未来版本都可用。

若宿主已完整包含相同的后台定时播放 UI/API，模块会跳过重复注入。

## 安装

1. 从 [Releases](https://github.com/shitianyaa/NagramXVideoTimerLSP/releases/latest) 下载最新 APK 并安装
2. 在 LSPosed 中启用 **NagramX Video Timer**
3. 确认作用域为原版 NagramX（`nu.gpu.nagram`）
4. 强制停止并重新启动 NagramX

从 `1.0.0`、`1.0.1` 或 `1.1.0` 升级到 `1.1.1` 时，必须先卸载旧模块。这些历史 APK 的签名与 `1.1.1` 正式签名不同，Android 不允许直接覆盖安装。

## 使用

1. 在 NagramX 中打开普通视频并开始播放
2. 打开视频设置菜单
3. 点击「后台定时播放」
4. 选择停止时间；视频转入后台继续播放

### 菜单项

| 选项 | 说明 |
| --- | --- |
| 播放 15 / 30 / 45 / 60 / 90 分钟 | 固定时长后停止 |
| 自定义时长 | 时 / 分双滚轮，可循环滚动 |
| 当前视频结束后停止 | 播完当前条目即停 |
| 取消当前定时器 | 仅在有活动定时器时显示；取消后仍保持后台播放 |

### 定时启动后

- 退出全屏：音频继续，通知栏出现播放控制
- 回到聊天页：顶部迷你播放器显示剩余定时，点按可回全屏
- 迷你播放器右侧列表图标：切换同一会话内其他视频

## 兼容策略

模块按优先级选择实现路径：

| 优先级 | 条件 | 行为 | 稳定性 |
| --- | --- | --- | --- |
| 1 | 宿主已有完整后台定时 UI/API | 不重复注入 | 最高 |
| 2 | 存在 `startVideoBackgroundPlayback(int, int)` | 注入菜单，调用宿主原生实现 | 高 |
| 3 | 存在 PhotoViewer PiP 链路 | 保留原播放器所有权，缓冲完成后置顶播放，模块维护计时器 | 中高 |
| 4 | 仅有旧版播放器转移入口 | 模块转移播放器并维护计时器 | 较低 |

路径 4 依赖 NagramX 内部实现。若菜单未出现，请先看日志确认目标版本是否缺少必要签名。

## 日志

```bash
adb logcat -s NagramXVideoTimer
```

会记录模块加载、宿主版本、签名探测、Hook 安装与播放器转移错误。

## 本地构建

**环境**

- JDK 21
- Android SDK 37

在 `local.properties` 中配置 SDK 路径：

```properties
sdk.dir=/path/to/Android/Sdk
```

完整检查：

```bash
# Windows
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest

# macOS / Linux
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

正式 Release 使用固定 JKS 签名。将 [`keystore.properties.example`](keystore.properties.example) 复制为本地忽略的 `keystore.properties` 后，填写 keystore 路径、别名和密码；缺少签名配置时仍可构建未签名 release，但不能用于发布。不要提交 keystore、`keystore.properties` 或任何密码。

## 自动构建与发布

[GitHub Actions 工作流](.github/workflows/build-release.yml)会：

- 推送 `main` 或提交 PR 时：构建 Debug/未签名 Release APK，并跑 Lint 与单元测试
- 推送与工程版本完全一致的标签（如 `10101-1.1.1`）时：校验正式签名、创建 GitHub Release、上传 APK，并写入对应 `CHANGELOG.md` 段落
- tag 必须等于 `MODULE_VERSION_CODE-MODULE_VERSION_NAME`；Release 标题为版本名，正文为对应的 Keep a Changelog 段落
- tag 发布前需要在仓库 Secrets 配置 `RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS` 和 `RELEASE_KEY_PASSWORD`

## 项目结构

包名：`com.shitianyaa.nagramx.videotimer`

| 文件 | 职责 |
| --- | --- |
| `ModuleMainKt.kt` | libxposed 模块入口与目标进程过滤 |
| `HostProfile.kt` | 宿主版本与类 / 字段 / 方法签名探测 |
| `NagramXHooks.kt` | Hook 安装与生命周期接入 |
| `HostBridge.kt` | 宿主 ClassLoader 下的反射边界与错误处理 |
| `VideoBackgroundSession.kt` | 后台播放会话、媒体通知与迷你播放器状态 |
| `VideoSleepTimerSheet.kt` | 后台定时菜单与自定义时长滚轮 |
| `PhotoViewerAgent.kt` | PhotoViewer / PiP 链路适配 |
| `MainActivity.kt` | 模块状态页 |

> 完整源码见 [`app/src/main/java/com/shitianyaa/nagramx/videotimer/`](app/src/main/java/com/shitianyaa/nagramx/videotimer/)。

## 更新日志

版本变更见 [CHANGELOG.md](CHANGELOG.md)，发布包见 [Releases](https://github.com/shitianyaa/NagramXVideoTimerLSP/releases)。

## 免责声明

本项目为非官方模块，与 NagramX、Telegram、LSPosed 均无隶属关系。模块会调用目标应用内部实现；升级 NagramX 前建议保留可回退安装包，并自行承担使用风险。

## 许可证

[Apache License 2.0](LICENSE)
