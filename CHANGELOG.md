# 更新日志

本项目的版本记录遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，并使用[语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

## [1.1.2] - 2026-08-09

### 更改

- 将启动器图标更新为黑白猫耳表盘：黑色背景、白色表盘和 `10:10` 指针，并提供 Android 13 单色版本。

## [1.1.1] - 2026-08-09

### 更改

- Release 改用稳定的正式签名，后续版本可正常覆盖升级。
- GitHub Release tag 改为 `versionCode-versionName`，首个正式 tag 为 `10101-1.1.1`。
- 补齐应用图标、模块目录元数据和定时状态机单元测试。

### 迁移

- 已安装 `1.0.0`、`1.0.1` 或 `1.1.0` 的用户需要先卸载旧模块，再安装 `1.1.1`。历史 Release 使用的签名不一致，Android 无法直接覆盖安装。

## [1.1.0] - 2026-08-08

### 新增

- 视频后台播放：退出全屏后音频继续，定时器在后台照常倒计时。
- 通知栏播放控制与媒体会话，支持播放/暂停、上一个/下一个和进度显示。
- 聊天页顶部迷你播放器，显示标题、剩余定时时长，点按可回到全屏。
- 播放列表面板，可切换同一会话内的其他视频，条目补上视频缩略图。
- 定时菜单在激活状态下显示剩余时长并高亮。

### 修复

- 滚动聊天列表或离开聊天页时不再中断后台视频播放。
- 迷你播放器改为两行布局，剩余时长不再与标题重叠，并按秒刷新。
- 自定义时长的小时与分钟滚轮恢复循环滚动。
- 迷你播放器与播放列表里的视频名称、作者不再显示为文件名和「未知艺术家」：
  名称按 文件名 → 说明文字首行 → 「视频」取值，作者按 转发来源 → 发送者 → 会话 解析。
- 取消定时后迷你播放器不再停留在最后一次倒计时上。

## [1.0.1] - 2026-08-07

### 修复

- 修复旧版兼容路径转交播放器后导致原版 PiP/置顶播放消失的问题。
- 允许视频处于缓冲或边下边播状态时先开启定时播放，首帧就绪后再切换 PiP。
- 定时结束改为暂停并保留 PiP，取消定时不再销毁播放器。
- 将兜底定时菜单调整为宿主原版 ActionBar swipe-back 二级页风格，并优先复用宿主已有布局。

## [1.0.0] - 2026-08-07

### 新增

- 基于 libxposed API 102 的 LSPosed 模块入口。
- 原版 NagramX（`nu.gpu.nagram`）静态作用域和主进程过滤。
- 后台视频定时播放菜单，支持固定时长、当前视频结束后停止和取消定时器。
- 宿主原生 API 优先及旧播放器转移兼容路径。
- 模块状态页、运行时签名探测、日志和安全停用机制。
- GitHub Actions 自动构建、检查和标签发布流程。

[未发布]: https://github.com/shitianyaa/NagramXVideoTimerLSP/compare/10102-1.1.2...HEAD
[1.1.2]: https://github.com/shitianyaa/NagramXVideoTimerLSP/compare/10101-1.1.1...10102-1.1.2
[1.1.1]: https://github.com/shitianyaa/NagramXVideoTimerLSP/compare/v1.1.0...10101-1.1.1
[1.1.0]: https://github.com/shitianyaa/NagramXVideoTimerLSP/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/shitianyaa/NagramXVideoTimerLSP/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/shitianyaa/NagramXVideoTimerLSP/releases/tag/v1.0.0
