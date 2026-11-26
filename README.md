# Nagram 定时播放（NagramTimer）

<p align="center"><img src="module/res/drawable-nodpi/ic_launcher_artwork.png" width="160" alt="猫耳角色抱着 NagramX 图标"></p>

[![最新版本](https://img.shields.io/github/v/release/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer?style=for-the-badge&logo=github&logoColor=white&label=Release)](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/releases/latest)
[![下载量](https://img.shields.io/github/downloads/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/total?style=for-the-badge&logo=download&logoColor=white&label=Downloads)](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/releases)
[![许可证](https://img.shields.io/github/license/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer?style=for-the-badge&logo=apache&logoColor=white&label=License)](LICENSE)

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#兼容范围)
[![NagramX](https://img.shields.io/badge/Target-nu.gpu.nagram-26A5E4?style=flat-square&logo=telegram&logoColor=white)](#兼容范围)
[![NiagramX](https://img.shields.io/badge/Target-top.nkbe.niagram-26A5E4?style=flat-square&logo=telegram&logoColor=white)](#兼容范围)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-F48FB1?style=flat-square)](#兼容范围)
[![Changelog](https://img.shields.io/badge/Changelog-Keep%20a%20Changelog-E05735?style=flat-square)](CHANGELOG.md)
[![浏览量](https://visitor-badge.laobi.icu/badge?page_id=Xposed-Modules-Repo.com.shitianyaa.nagramx.videotimer&left_text=views)](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer)

基于 [libxposed API](https://github.com/libxposed/api) 的 LSPosed 模块，为 NagramX / NiagramX 提供**听视频**、**后台播放**与**音视频定时关闭**。

> **签名迁移**：`1.1.1` 起使用固定正式签名。已安装 `1.0.0` 至 `1.1.0` 的用户需要先卸载旧模块再安装 `1.1.1`；此后版本可正常覆盖升级。

## 功能入口

启用模块并重启 NagramX / NiagramX 后，在宿主应用内使用：

| 功能 | 入口位置 |
| --- | --- |
| 听视频 | 打开普通视频 → 视频设置菜单 →「听视频」 |
| 视频定时关闭 | 进入听视频页 → 右上角定时按钮 |
| 音乐／音频定时关闭 | 播放音乐或音频文件 → 点击聊天页顶部播放栏展开播放器 → 右下角「⋮」→ 第一项「定时关闭」 |
| 修改／取消音频定时 | 再次打开右下角「⋮」→「定时关闭 · 剩余时间」→ 修改时长或点「取消定时关闭」 |
| 播完当前曲目停止 | 展开音乐播放器 → 左下角循环／随机播放按钮 → 宿主「播放一次」 |

聊天语音、一次性语音和圆形视频不提供音频定时入口。

## 功能

### 听视频

从普通视频的菜单选择「听视频」，即可从当前进度进入独立播放器。页面提供进度、播放／暂停、倍速、上一条／下一条和返回画面。右下菜单打开当前会话的共享视频，列表底部可以继续加载。定时关闭在听视频页右上角。关闭页面后，点击聊天页顶部的迷你播放器即可再次打开。

### 定时与后台

- 听视频页提供「定时关闭」。
- 支持 15 / 30 / 45 / 60 / 90 分钟、自定义时长，以及当前视频结束后停止。
- 手动返回后继续后台播放；从消息页或视频列表切换视频时，继承剩余定时。
- 暂停、缓冲及播放器转交期间冻结倒计时，恢复播放后继续。
- 定时到期暂停播放，保留顶栏和播放会话；取消定时仍可继续后台播放，手动关闭播放器才结束会话。
- 会话保存在当前应用进程内，强制停止或进程重启后不恢复。

### 音乐与音频定时

- 展开音乐播放器，点右下角「⋮」，第一项为「定时关闭」；启用后显示剩余时间，点击可修改或取消。
- 支持 15 / 30 / 45 / 60 / 90 分钟、自定义 1 分钟至 23 小时 59 分钟。小时为 0 时，分钟从 1 开始；其他小时可选 0 分钟。
- 按实际播放经过的时间累计，暂停、下载等待和缓冲时冻结；倍速和拖动进度不改变计时速度。同账号更换音乐或歌单继续按分钟计时。
- 当前曲目结束后停止使用宿主播放顺序菜单里的「播放一次」。
- 排除聊天语音、一次性语音、普通视频和圆形视频。成功切换播放账号或媒体类型、关闭播放会话、模块重载及进程重启后清除音频定时。
- 到期调用宿主暂停并保留进度，原循环及随机设置保留。音频和视频使用独立定时状态。

### 播放控制与界面

- 聊天页顶部播放器显示标题和剩余定时。点击播放器可重新打开听视频页。
- 听视频页的视频列表显示缩略图、标题、时长和当前项，可切换同一聊天中的视频。
- 系统媒体通知支持播放/暂停、上一条/下一条及宿主的随机/循环模式。
- 顺序播放时，手动点击「下一条」播放更新的消息，「上一条」播放更早的消息；随机播放沿用宿主逻辑。
- 通知点击可能回到应用退出前的页面，不保证滚动定位到对应消息。
- 仅在受支持宿主的主进程加载；模块状态页显示各宿主包名和安装版本。是否成功启用以 LSPosed 状态及宿主内菜单为准。

## 兼容范围

| 项目 | 要求或验证范围 |
| --- | --- |
| 目标应用 | NagramX，包名 `nu.gpu.nagram`；NiagramX，包名 `top.nkbe.niagram` |
| 已验证宿主 | NagramX `12.9.2-4335a2e`（`versionCode 1260`） |
| 已验证 Niagram | `13.2.2` 正式包（构建提交 `127b70d`，`versionCode 1322`）：类型识别、听视频、共享视频列表、切换与定时；音乐定时入口、时长联动、暂停恢复、锁屏到期及系统切歌继承 |
| Android | 模块最低要求 8.0（`minSdk 26`）；不代表所有系统均已实测 |
| LSPosed | libxposed API 102；设备验证使用框架版本码 7854 / 7901，音乐定时使用 7912 |

NagramX `12.9.2-4335a2e` 实测视频切换、跨视频定时、顶部播放栏、系统媒体控制和返回画面。音乐定时的实测版本为 Niagram 13.2.2。

NagramX 自 `146c125e7` 起已内置后台定时。检测到该能力时，听视频页沿用宿主的后台播放链路，定时交由宿主处理。

Niagram 的消息与播放器类型从宿主字段取得，其余 12 个相关类通过 DexKit 按字段、父类、方法及字符串特征解析。缓存按 APK 内容指纹和解析规则隔离，同版本号的不同构建会重新解析。旧包 `81fb24a` 与正式包 `127b70d` 的类特征均已核对。系统媒体卡片的上一条／下一条通过平台回调进入听视频队列，两套宿主媒体会话使用相同的切换方向与边界。解析缺失或存在多个候选时，日志记录具体类名，相应控件或能力沿用缺失接口的降级路径。

## 安装

1. 从 [官方 Releases](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/releases/latest) 下载最新 APK 并安装。
2. 在 LSPosed 中启用 **Nagram 定时播放**。
3. 将作用域设为 NagramX（`nu.gpu.nagram`）或 NiagramX（`top.nkbe.niagram`）。
4. 强制停止并重新启动目标应用。

## 使用

### 视频

1. 在 NagramX / NiagramX 中打开普通视频并开始播放。
2. 打开视频设置菜单，选择「听视频」。
3. 点听视频页右上角的定时按钮，选择停止时间，或选择当前视频结束后停止。
4. 返回聊天页后继续后台播放，点击顶部播放栏可重新打开听视频页。

### 音乐与音频文件

1. 播放一首音乐或音频文件，点击聊天页顶部播放栏展开播放器。
2. 点播放器右下角「⋮」，选择第一项「定时关闭」。
3. 点击预设时长即可启用；也可以滚动小时、分钟，再点「应用」。小时为 0 时，分钟从 1 开始。
4. 启用后，右下角「⋮」菜单的定时项显示剩余时间。再次点击可修改时长，或在面板底部点「取消定时关闭」。

倒计时按实际播放时间累计，暂停和缓冲时冻结；同账号切歌继续计时。需要播完当前曲目停止时，使用左下角循环／随机播放菜单中的「播放一次」。

## 构建与检查

正式构建使用 Gradle，`app` 构建入口读取 `module/` 中的 Java 源码、资源和 Xposed 元数据：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintDebug
.\module\test\run_test.ps1
```

Release 签名通过本地 `keystore.properties` 或 CI 的签名环境变量配置；签名材料不得提交到仓库。没有配置签名时，Gradle 仅生成未签名的 Release 包。标签构建要求正式签名，并运行 Java Hook 回归检查；发布标签格式为 `versionCode-versionName`。推送该格式的标签后，CI 会把正式签名 APK 与该标签对应的 CHANGELOG 段落发布到个人仓库 Releases；官方模块仓库独立发布。

`build.ps1` / `build.sh` 统一调用 Gradle `:app:assembleDebug`，将包含 DexKit 及其原生依赖的测试包输出到 `build/NagramTimer-v<版本>-debug.apk`。CI 的正式安装包命名为 `NagramTimer-v<版本>-release.apk`。Debug 使用本机 Gradle 开发签名，与正式 Release 签名分开。旧脚本的手工 javac/d8 打包和 `module/mod.keystore` 签名路径不再使用；SDK 和 JDK 沿用 Gradle 配置。Java 回归脚本保留 `AJ_W`、`JDK_W` 的编译运行工具链覆盖参数，并通过 Gradle 准备测试依赖。

桌面回归检查模拟宿主接口，不能替代 Android ART Hook、系统通知和完整真机交互测试。

## 更新与支持

- 版本变更见 [CHANGELOG.md](CHANGELOG.md)，发布包见 [官方 Releases](https://github.com/Xposed-Modules-Repo/com.shitianyaa.nagramx.videotimer/releases)；个人仓库的 [Releases](https://github.com/shitianyaa/NagramXVideoTimerLSP/releases) 由标签 CI 自动发布同一份正式签名 APK。
- 问题反馈请使用 [个人仓库 Issues](https://github.com/shitianyaa/NagramXVideoTimerLSP/issues)。

## 免责声明

本项目为非官方模块，与 NagramX、Telegram、LSPosed 均无隶属关系。模块会调用目标应用内部实现；升级 NagramX 前建议保留可回退安装包，并自行承担使用风险。

## 许可证

[Apache License 2.0](LICENSE)
