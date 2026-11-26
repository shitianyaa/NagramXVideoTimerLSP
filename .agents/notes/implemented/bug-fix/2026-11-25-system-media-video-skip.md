# Agent Note: 系统媒体切换复用听视频队列

Status: implemented

## Problem

Niagram 13.2.2 的应用内听视频切换正常，系统媒体卡片的上一条和下一条无响应。MusicPlayerService 与 TelegramMediaSession 同时发布媒体会话，两者的音乐类型检查会拦住视频。R8 还将回调搬到默认包，原有按匿名类编号与源码类名查找的 Hook 没有安装。

## Decision

在宿主 DEX 中按 android.media.session.MediaSession.Callback 父类及两个无参 void 切换方法定位平台回调实现，沿用[混淆类的结构解析](2026-11-25-niagram-r8-class-discovery.md)的唯一候选和 APK 指纹缓存。规则版本为 3，新增该回调的类型与方法特征，使旧缓存重新解析。

Niagram 正处于模块拥有的听视频会话时，两个平台切换回调直接使用 ListenVideoAgent.skip。两套媒体会话共用应用内的排序、边界、失败恢复和定时语义。普通音频及听视频会话外的事件继续调用宿主回调。

模块拥有的视频事件在处理后直接返回，包括没有相邻视频的边界，避免再次进入宿主音乐分支。该入口只在 Niagram 对应分支安装，NagramX 保持原有媒体会话路径。

同文件的保护计数器在 finally 中逐层恢复嵌套深度，最外层退出后 remove；空状态读取也立即 remove，遵循阿里规约 01-(七)。通知缩略图解码异常按 02-(三)记录诊断，保留原有的无封面降级。

## Alternatives considered

补齐两套业务回调的混淆名可以复用现有音乐身份伪装，但需要同时维护回调类及其重命名的方法，并继续跨过各自的音乐分支。

只 Hook MediaController 的切换方法范围更小，但系统回调会在 isMusic 检查处返回，尚未调用这些方法，无法修复当前入口。

## Verification

- 完整 APK 核对：旧包 81fb24a 唯一匹配 zf6，正式包 127b70d 唯一匹配 xf6；两者均声明 public final onSkipToNext()V 与 onSkipToPrevious()V，R8 短名不进入生产查找条件。
- 170 项 Java 回归通过，覆盖两套回调实例、单次切换、方向、边界、定时保持、普通音乐与会话外事件透传、错误签名拒绝，以及两组保护计数器的嵌套恢复和最外层清理。反转会话所有权判断后回归失败，恢复后通过。
- 设备 Niagram 13.2.2 / versionCode 1322 / 127b70d 正式包，LSPosed 7912 / API 102。分别向 TelegramMediaSession 发送 previous、向 telegramAudioPlayer 发送 next，两套系统会话的 metadata 均切换到另一视频。
- 在系统顶部媒体卡片实际点击下一条和上一条，卡片标题、封面和进度刷新，日志分别记录 onSkipToNext/Previous 与 changed=true。原有应用内队列切换由完整 JVM 回归覆盖。
- Debug、正式签名 Release 和 Lint 通过，修复 Debug 包已覆盖安装。脚本、回调结构检查、设备日志与截图位于工作区 Temp/niagram-system-skip/。

## Consequences

新入口复用听视频队列，不再依赖业务回调匿名类的混淆名和音乐身份检查。系统卡片选择两套媒体会话中的任意一套，均进入同一切换语义。

该入口依赖框架回调覆写约定和模块会话所有权判断。宿主新增多个同形状平台回调时，会记录歧义并停用这一入口，需要重新核对回调范围。普通音频透传与队列边界已做 JVM 回归，本轮真机覆盖视频的系统媒体控制。
