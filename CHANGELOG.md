# 更新日志

本项目的版本记录遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，并使用[语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

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

[未发布]: https://github.com/shitianyaa/NagramXVideoTimerLSP/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/shitianyaa/NagramXVideoTimerLSP/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/shitianyaa/NagramXVideoTimerLSP/releases/tag/v1.0.0
