# NagramX Video Timer LSP

<p align="center"><img src="module/res/drawable-nodpi/ic_launcher_artwork.png" width="160" alt="猫耳角色抱着 NagramX 图标"></p>

[![最新版本](https://img.shields.io/github/v/release/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer?style=for-the-badge&logo=github&logoColor=white&label=Release)](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/releases/latest)
[![下载量](https://img.shields.io/github/downloads/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/total?style=for-the-badge&logo=download&logoColor=white&label=Downloads)](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/releases)
[![许可证](https://img.shields.io/github/license/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer?style=for-the-badge&logo=apache&logoColor=white&label=License)](LICENSE)

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#兼容范围)
[![NagramX](https://img.shields.io/badge/Target-nu.gpu.nagram-26A5E4?style=flat-square&logo=telegram&logoColor=white)](#兼容范围)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-F48FB1?style=flat-square)](#兼容范围)
[![Changelog](https://img.shields.io/badge/Changelog-Keep%20a%20Changelog-E05735?style=flat-square)](CHANGELOG.md)
[![浏览量](https://visitor-badge.laobi.icu/badge?page_id=Xposed-Modules-Repo.com.shitianyaa.nagramx.videotimer&left_text=views)](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer)

基于 [libxposed API](https://github.com/libxposed/api) 的 LSPosed 模块，为原版 NagramX 提供**后台视频播放**和**定时停止**。

> **签名迁移**：`1.1.1` 起使用固定正式签名。已安装 `1.0.0` 至 `1.1.0` 的用户需要先卸载旧模块再安装 `1.1.1`；此后版本可正常覆盖升级。

## 功能

### 定时与后台

- 视频设置菜单提供「后台定时播放」，确认后留在视频页。
- 支持 15 / 30 / 45 / 60 / 90 分钟、自定义时长，以及当前视频结束后停止。
- 手动返回后继续后台播放；从消息页或视频列表切换视频时，继承剩余定时。
- 暂停、缓冲及播放器转交期间冻结倒计时，恢复播放后继续。
- 定时到期暂停播放，保留顶栏和播放会话；取消定时仍可继续后台播放，手动关闭播放器才结束会话。
- 会话保存在当前应用进程内，强制停止或进程重启后不恢复。

### 播放控制与界面

- 聊天页顶部播放器显示标题和剩余定时，点击直接返回视频详情页。
- 视频菜单中的独立「视频列表」显示缩略图、标题、时长和当前项，可切换同一聊天中的视频。
- 系统媒体通知支持播放/暂停、上一条/下一条及宿主的随机/循环模式。
- 顺序播放时，手动点击「下一条」播放更新的消息，「上一条」播放更早的消息；随机播放沿用宿主逻辑。
- 通知点击可能回到应用退出前的页面，不保证滚动定位到对应消息。
- 仅在原版 NagramX 主进程加载；模块状态页显示目标包名和宿主安装版本。是否成功启用以 LSPosed 状态及宿主内菜单为准。

## 兼容范围

| 项目 | 要求或验证范围 |
| --- | --- |
| 目标应用 | 原版 NagramX，包名 `nu.gpu.nagram` |
| 已验证宿主 | `12.9.2-4335a2e`（`versionCode 1260`） |
| Android | 模块最低要求 8.0（`minSdk 26`）；不代表所有系统均已实测 |
| LSPosed | libxposed API 102；本轮设备使用框架版本码 7854 / 7901 |

本轮已在连接设备上验证视频切换、跨视频定时、顶栏返回、通知控制及全屏画面恢复。其他宿主版本和系统组合仍需测试。模块依赖 NagramX 内部接口；上游归档不代表所有版本都兼容。

## 安装

1. 从 [官方 Releases](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/releases/latest) 下载最新 APK 并安装。
2. 在 LSPosed 中启用 **NagramX Video Timer**。
3. 将作用域设为原版 NagramX（`nu.gpu.nagram`）。
4. 强制停止并重新启动 NagramX。

## 使用

1. 在 NagramX 中打开普通视频并开始播放。
2. 打开视频设置菜单，选择「后台定时播放」。
3. 选择停止时间，或选择当前视频结束后停止。

## 构建与检查

正式构建使用 Gradle，`app` 构建入口读取 `module/` 中的 Java 源码、资源和 Xposed 元数据：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintDebug
.\module\test\run_test.ps1
```

Release 签名通过本地 `keystore.properties` 或 CI 的签名环境变量配置；签名材料不得提交到仓库。没有配置签名时，Gradle 仅生成未签名的 Release 包。标签构建要求正式签名，并运行 Java Hook 回归检查；发布标签格式为 `versionCode-versionName`。推送该格式的标签后，CI 会把正式签名 APK 与该标签对应的 CHANGELOG 段落发布到个人仓库 Releases；官方模块仓库的源码同步与 Release 仍需手动执行。

`build.ps1` / `build.sh` 用于本地快速制作测试包，使用独立开发签名，不能作为正式更新包覆盖安装。PowerShell 脚本可通过 `BT_W`、`AJ_W`、`JDK_W` 指定 Android Build Tools、android.jar 和 JDK bin 路径；默认路径沿用本机开发环境。

桌面回归检查模拟宿主接口，不能替代 Android ART Hook、系统通知和完整真机交互测试。

## 更新与支持

- 版本变更见 [CHANGELOG.md](CHANGELOG.md)，发布包见 [官方 Releases](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/releases)；个人仓库的 [Releases](https://github.com/shitianyaa/NagramXVideoTimerLSP/releases) 由标签 CI 自动发布同一份正式签名 APK。
- 问题反馈请使用 [个人仓库 Issues](https://github.com/shitianyaa/NagramXVideoTimerLSP/issues)。

## 免责声明

本项目为非官方模块，与 NagramX、Telegram、LSPosed 均无隶属关系。模块会调用目标应用内部实现；升级 NagramX 前建议保留可回退安装包，并自行承担使用风险。

## 许可证

[Apache License 2.0](LICENSE)
