# NagramX Video Timer LSP

[![最新版本](https://img.shields.io/github/v/release/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer?style=for-the-badge&logo=github&logoColor=white&label=Release)](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/releases/latest)
[![下载量](https://img.shields.io/github/downloads/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/total?style=for-the-badge&logo=download&logoColor=white&label=Downloads)](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/releases)
[![许可证](https://img.shields.io/github/license/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer?style=for-the-badge&logo=apache&logoColor=white&label=License)](LICENSE)

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#兼容范围)
[![NagramX](https://img.shields.io/badge/Target-nu.gpu.nagram-26A5E4?style=flat-square&logo=telegram&logoColor=white)](#兼容范围)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20101--102-F48FB1?style=flat-square)](#兼容范围)
[![Changelog](https://img.shields.io/badge/Changelog-Keep%20a%20Changelog-E05735?style=flat-square)](CHANGELOG.md)
[![浏览量](https://visitor-badge.laobi.icu/badge?page_id=Xposed-Modules-Repo.com.shitianyaa.nagramx.videotimer&left_text=views)](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer)

基于 [libxposed API](https://github.com/libxposed/api) 的 LSPosed 模块，为原版 NagramX 提供**后台视频播放**和**定时停止**。

> **签名迁移**：`1.1.1` 起使用固定正式签名。已安装 `1.0.0` 至 `1.1.0` 的用户需要先卸载旧模块再安装 `1.1.1`；此后版本可正常覆盖升级。

## 功能

### 定时与后台

- 在视频设置菜单中提供「后台定时播放」入口。
- 支持 15 / 30 / 45 / 60 / 90 分钟，以及通过时/分滚轮设置自定义时长。
- 支持「当前视频结束后停止」。
- 可取消定时器，同时保持后台播放。
- 退出全屏后音频继续，定时器在后台照常倒计时。

### 播放控制与界面

- 通知栏媒体会话提供播放/暂停、上一个/下一个与进度显示。
- 聊天页顶部迷你播放器显示标题和剩余定时时长，点按可回到全屏。
- 播放列表可切换同一会话内的其他视频，并显示缩略图。
- 定时菜单在激活状态下显示剩余时长并高亮。

### 兼容与安全

- 优先复用宿主已有的后台播放与睡眠定时 API。
- 旧版宿主优先走 PhotoViewer 原生置顶播放（PiP）链路。
- 缓冲或边下边播时也可先开定时，首帧就绪后再切置顶播放。
- 定时结束只暂停并保留置顶播放；取消定时不销毁播放器。
- 仅在 NagramX 主进程加载；自带状态页可查看服务、API、作用域与宿主版本。

## 兼容范围

| 项目 | 要求 |
| --- | --- |
| 目标应用 | 原版 NagramX |
| 目标包名 | `nu.gpu.nagram` |
| NagramX 版本 | 不限制 `versionCode` |
| Android | 8.0+（`minSdk 26`） |
| LSPosed | libxposed API 101–102 |

模块会在运行时检测宿主接口。NagramX 内部结构发生变化时，部分功能可能不可用。

## 安装

1. 从 [官方 Releases](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/releases/latest) 下载最新 APK 并安装。
2. 在 LSPosed 中启用 **NagramX Video Timer**。
3. 将作用域设为原版 NagramX（`nu.gpu.nagram`）。
4. 强制停止并重新启动 NagramX。

## 使用

1. 在 NagramX 中打开普通视频并开始播放。
2. 打开视频设置菜单，选择「后台定时播放」。
3. 选择停止时间，或选择当前视频结束后停止。

## 更新与支持

- 版本变更见 [CHANGELOG.md](CHANGELOG.md)，发布包见 [官方 Releases](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/releases)。
- 问题反馈请使用 [个人仓库 Issues](https://github.com/shitianyaa/NagramXVideoTimerLSP/issues)。

## 免责声明

本项目为非官方模块，与 NagramX、Telegram、LSPosed 均无隶属关系。模块会调用目标应用内部实现；升级 NagramX 前建议保留可回退安装包，并自行承担使用风险。

## 许可证

[Apache License 2.0](LICENSE)
