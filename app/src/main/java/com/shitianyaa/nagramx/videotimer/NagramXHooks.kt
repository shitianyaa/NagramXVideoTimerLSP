package com.shitianyaa.nagramx.videotimer

import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 安装并编排全部 Hook。
 *
 * 分工：能整体替换的方法直接短路，方法内部的分支用「前后夹逼」抵消，宿主自己有 try/catch 的地方
 * 靠抛异常走它的失败分支。每个 Hook 都先判 [VideoBackgroundSession.backgroundActive]，
 * 会话不存在时行为与未安装模块完全一致。
 */
internal class NagramXHooks(
    private val module: XposedModule,
    private val host: HostProfile,
) {
    private val logger: (String, Throwable?) -> Unit = { message, throwable ->
        module.log(if (throwable == null) Log.INFO else Log.WARN, TAG, message, throwable)
    }

    private val session = VideoBackgroundSession(logger)
    private val theme = HostTheme(host.bridge, logger)
    private val strings = HostStrings(host.bridge, logger)
    private val media = MediaControllerAgent(host, logger)
    private val sheet = VideoSleepTimerSheet(host, theme, strings, logger)
    private val photo = PhotoViewerAgent(host, media, session, theme, strings, sheet, logger)
    private val notification = NotificationDresser(host, session, strings, logger)
    private val playerUi = PlayerUiAgent(host, session, strings, logger)

    /**
     * `ChatActivity` 清理聊天内嵌播放的调用深度，见 [hookChatActivityGuards]。
     *
     * 按线程计数而不是单个布尔量：这些方法都在主线程跑，但嵌套调用（`onRemoveFromParent`
     * 里再进 `updateTextureViewPosition`）时布尔量会被内层提前清掉。
     */
    private val suppressCleanup = ThreadLocal<Int>()

    private var suppressCleanupDepth: Int
        get() = suppressCleanup.get() ?: 0
        set(value) = suppressCleanup.set(value)

    fun install() {
        if (host.hasCompleteNativeTimerUi) {
            logger(
                "宿主已自带后台定时播放，模块不重复注入：" +
                    "version=${host.versionName}/${host.versionCode}",
                null,
            )
            return
        }
        if (!host.canInjectTimerMenu) {
            logger("宿主缺少可注入的 PhotoViewer 视频菜单，模块保持停用", null)
            return
        }
        if (!host.canStartBackgroundPlayback) {
            logger("宿主缺少后台播放所需入口，模块保持停用：缺失=${host.describeGaps()}", null)
            return
        }

        session.bind(
            probe = ::probePlayback,
            onExpire = ::onTimerExpired,
            onTick = playerUi::refreshCountdown,
        )

        hookMenuInjection()
        hookServiceEligibility()
        hookRoundVideoOverlay()
        hookPlaylistPreservation()
        hookTrackEnd()
        hookDownloadCancel()
        hookSessionTeardown()
        hookStrayViewerCallbacks()

        hookIdentityQueries()
        hookNotificationDressing()
        hookMusicMetadata()

        hookMiniPlayer()
        hookPlaylistAlert()

        hookChatActivityGuards()
        hookPlaylistThumbnails()

        logger(
            "Hook 安装完成：version=${host.versionName}/${host.versionCode}, " +
                "playlist=${host.canManagePlaylist}, 通知=${host.canDressNotification}, " +
                "缺失=${host.describeGaps()}",
            null,
        )
        if (host.canDressNotification) {
            logger("通知栏已知差异：${notification.dressedNotificationGaps}", null)
        }
    }

    // ---- 会话探测与到期 ----

    private fun probePlayback(): VideoBackgroundSession.ProbeResult {
        val playing = media.playingMessage()
        val alive = media.activePlayer() != null && playing != null
        val id = (host.bridge.invoke(playing, host.message.getId) as? Number)?.toInt() ?: 0
        return VideoBackgroundSession.ProbeResult(
            sessionAlive = alive,
            messageId = id,
            reachedEnd = media.hasReachedEnd(),
        )
    }

    private fun onTimerExpired() {
        logger("定时到期，暂停后台视频", null)
        media.pausePlayback()
        photo.refreshAllMenus()
        notification.refresh("定时到期")
        playerUi.onSessionEnded()
    }

    // ---- Hook 安装 ----

    private fun hookMenuInjection() {
        val method = host.photo.setParentActivity ?: return
        intercept(method) { chain ->
            val result = chain.proceed()
            chain.thisObject?.let(photo::scheduleMenuInjection)
            result
        }
    }

    /**
     * 原版只让音乐/语音/圆形视频启动 MusicPlayerService，普通视频会被拒绝。
     * 后台会话期间直接返回 true，等价 fork 在该方法里加的 `videoBackgroundPlayback` 分支。
     */
    private fun hookServiceEligibility() {
        val method = host.media.canStartMusicPlayerService ?: return
        intercept(method) { chain ->
            if (session.backgroundActive && host.isVideoMessage(media.playingMessage())) {
                true
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * 后台播放是纯音频，不能再弹圆形视频浮窗。
     *
     * `setCurrentVideoVisible` / `setTextureView` 可以整体短路；而 `playMessage` 与
     * `onSurfaceTextureDestroyed` 里的浮窗创建藏在方法内部，改为让 `show()` 抛异常，
     * 由宿主自己的 `catch (Exception e) { pipRoundVideoView = null; }` 收尾。
     */
    private fun hookRoundVideoOverlay() {
        host.media.setCurrentVideoVisible?.let { method ->
            intercept(method) { chain ->
                if (session.backgroundActive) {
                    media.closeRoundVideoOverlay(chain.thisObject)
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        host.media.setTextureView.forEach { method ->
            intercept(method) { chain ->
                if (session.backgroundActive) null else chain.proceed()
            }
        }

        val show = host.bridge.findMethod(host.pipRoundVideoViewClass, "show", 2) ?: return
        module.hook(show)
            .setExceptionMode(ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                if (session.backgroundActive) {
                    // 宿主两处调用点都包在 try/catch 里，抛异常正好让它把浮窗置空。
                    throw IllegalStateException("background video playback active")
                }
                chain.proceed()
            }
    }

    /**
     * `playMessage` 内部无条件调用 `clearPlaylist()`，会把后台播放列表清空，
     * 导致通知栏的上一个/下一个失效。分支拦不到，改为进入前快照、退出后还原。
     */
    private fun hookPlaylistPreservation() {
        val method = host.media.playMessage ?: return
        val playlistField = host.media.playlistField ?: return
        intercept(method) { chain ->
            if (!session.backgroundActive) return@intercept chain.proceed()

            val target = chain.args.getOrNull(0)
            if (!host.isVideoMessage(target)) {
                // 用户改播音乐或语音，后台视频会话到此结束。
                endSession()
                return@intercept chain.proceed()
            }

            @Suppress("UNCHECKED_CAST")
            val list = host.bridge.getField(chain.thisObject, playlistField) as? MutableList<Any>
            val snapshot = list?.toList()
            val index = (
                host.bridge.getField(chain.thisObject, host.media.currentPlaylistNumField)
                    as? Number
                )?.toInt() ?: 0

            val result = chain.proceed()

            if (list != null && snapshot != null && list.isEmpty() && snapshot.isNotEmpty()) {
                list.addAll(snapshot)
                val restored = snapshot.indexOfFirst { it === target }
                host.bridge.setField(
                    chain.thisObject,
                    host.media.currentPlaylistNumField,
                    if (restored >= 0) restored else index,
                )
            }
            result
        }
    }

    /**
     * 一条视频播完时的走向：设了「播完当前」就停，否则接着播列表下一条，
     * 而不是走原版的 `cleanupPlayer` 直接结束整个会话。
     */
    private fun hookTrackEnd() {
        val method = host.media.updateVideoState ?: return
        intercept(method) { chain ->
            val state = (chain.args.getOrNull(4) as? Number)?.toInt()
            if (!session.backgroundActive || state != MediaControllerAgent.EXO_STATE_ENDED) {
                return@intercept chain.proceed()
            }

            if (session.mode == VideoBackgroundSession.MODE_AFTER_CURRENT) {
                session.disarm()
                onTimerExpired()
                return@intercept null
            }

            val playlistSize = (host.bridge.getField(
                chain.thisObject,
                host.media.playlistField,
            ) as? List<*>)?.size ?: 0
            val playNext = host.media.playNextMessageWithoutOrder
            if (playlistSize > 1 && playNext != null) {
                host.bridge.invoke(chain.thisObject, playNext, true)
                return@intercept null
            }
            chain.proceed()
        }
    }

    /**
     * `PhotoViewer.onPhotoClosed` 会无条件取消当前视频的下载：
     * `FileLoader.getInstance(...).cancelLoadFile(currentMessageObject.getDocument())`。
     * 视频刚转入后台播放且尚未下载完时，这一下会直接把流式播放掐断。
     *
     * fork 是在 `onPhotoClosed` 里加 `isBackgroundPlayback` 判断跳过；模块拦不到方法内的分支，
     * 改为拦 `cancelLoadFile` 本身，只放过正在后台播放的那个 document。
     */
    private fun hookDownloadCancel() {
        val method = host.cancelLoadFileByDocument ?: return
        intercept(method) { chain ->
            if (!session.backgroundActive) return@intercept chain.proceed()
            val target = chain.args.getOrNull(0) ?: return@intercept chain.proceed()
            val playingDocument = host.bridge.invoke(
                media.playingMessage(),
                host.message.getDocument,
            )
            if (playingDocument != null && playingDocument === target) {
                logger("跳过取消后台视频的下载", null)
                null
            } else {
                chain.proceed()
            }
        }
    }

    /** 宿主释放播放器即代表后台会话结束，模块跟着收尾。 */
    /**
     * 宿主释放播放器即代表后台会话结束，模块跟着收尾。
     *
     * 例外是 [hookChatActivityGuards] 标记的抑制区间：那是 `ChatActivity` 在按「聊天内嵌播放」
     * 的逻辑清理，对后台播放不适用，直接跳过原实现。
     */
    private fun hookSessionTeardown() {
        val method = host.media.cleanupPlayer4 ?: return
        intercept(method) { chain ->
            if (session.backgroundActive && suppressCleanupDepth > 0) {
                logger("跳过 ChatActivity 对后台视频的清理", null)
                return@intercept null
            }
            val stopService = chain.args.getOrNull(1) == true
            if (session.backgroundActive && stopService) endSession()
            chain.proceed()
        }
    }

    /**
     * 播放器已经交给 MediaController，但它仍是 PhotoViewer 创建的匿名子类，
     * play/pause/seekTo 里会回调 PhotoViewer 的贴纸动画。全屏已关闭时这些调用没有意义，
     * 且可能碰到已置空的视图，转交期间一律跳过。
     */
    private fun hookStrayViewerCallbacks() {
        listOfNotNull(
            host.photo.playOrStopAnimatedStickers,
            host.photo.seekAnimatedStickersTo,
        ).forEach { method ->
            intercept(method) { chain ->
                if (photo.transferredPlayer != null && session.backgroundActive) {
                    null
                } else {
                    chain.proceed()
                }
            }
        }
    }

    // ---- 阶段 2：通知栏与媒体会话 ----

    /**
     * 身份查询的转接点。
     *
     * 这两个方法遍布全 App 热路径，所以 [MessageIdentityMask.resolve] 在未激活时只做一次
     * `AtomicInteger.get()`；返回 null 表示不干预，交还宿主原实现。
     *
     * 不按 [HostProfile.canDressNotification] 设门槛：通知栏只是伪装的用途之一，迷你播放器、
     * 播放列表和 `ChatActivity` 滚动保护同样依赖它，缺一个宿主签名不该把其余三处一起关掉。
     */
    private fun hookIdentityQueries() {
        val queries = listOf(
            host.message.isMusic to MessageIdentityMask.Kind.MUSIC,
            host.message.isVideo to MessageIdentityMask.Kind.VIDEO,
        )
        queries.forEach { (method, kind) ->
            method ?: return@forEach
            intercept(method) { chain ->
                MessageIdentityMask.resolve(chain.thisObject, kind) ?: chain.proceed()
            }
        }
        if (host.message.isVideo == null) {
            logger("找不到 MessageObject.isVideo，迷你播放器与滚动保护不可用", null)
        }
    }

    /**
     * 在通知与媒体会话构建期间套上身份伪装，让宿主自己走音乐分支。
     *
     * 这样拿到的是宿主原生的音乐通知：紧凑视图 5 键、上一首/下一首、
     * `ACTION_SKIP_TO_PREVIOUS/NEXT`、`METADATA_KEY_DURATION` 与锁屏进度条。
     */
    private fun hookNotificationDressing() {
        if (!host.canDressNotification) return

        host.musicService.createNotification?.let { method ->
            intercept(method) { chain ->
                NotificationDresser.MusicServiceHandle.current = chain.thisObject
                notification.around { chain.proceed() }
            }
        }
        host.musicService.updatePlaybackState?.let { method ->
            intercept(method) { chain -> notification.around { chain.proceed() } }
        }
        host.musicService.onStartCommand?.let { method ->
            intercept(method) { chain ->
                NotificationDresser.MusicServiceHandle.current = chain.thisObject
                notification.around { chain.proceed() }
            }
        }
        host.musicService.onDestroy?.let { method ->
            intercept(method) { chain ->
                if (NotificationDresser.MusicServiceHandle.current === chain.thisObject) {
                    NotificationDresser.MusicServiceHandle.current = null
                }
                chain.proceed()
            }
        }

        // 锁屏 / 蓝牙 / 车机的上一首、下一首走 MediaSession 回调，里面各有一处 isMusic()
        // 判断，且不在 createNotification 的调用栈上，需要单独套伪装。
        // 通知栏那两个按钮走 MusicPlayerReceiver 广播，原版没有 isMusic 判断，无需处理。
        listOfNotNull(
            host.musicService.onSkipToNext,
            host.musicService.onSkipToPrevious,
        ).forEach { method ->
            intercept(method) { chain -> notification.around { chain.proceed() } }
        }
        if (host.musicService.sessionCallbackClass == null) {
            logger("未找到 MediaSession 回调匿名类，锁屏上一首/下一首对视频可能无效", null)
        }
    }

    /**
     * 标题与作者的视频回退。
     *
     * 伪装成音乐后宿主会调 `getMusicTitle`/`getMusicAuthor`，但这两个方法内部按文档属性分支，
     * 视频走不进音频分支，原版会返回文件名和「未知艺术家」。这里在返回值上接管：
     * 标题按 文件名 → caption 首行 → 「视频」，作者按发送/转发来源解析。
     *
     * 不按 [HostProfile.canDressNotification] 设门槛：这两处回退服务的是通知栏、迷你播放器和
     * 播放列表三个界面，缺一个 `MusicPlayerService` 签名不该把另外两处的文案一起退回原版。
     */
    private fun hookMusicMetadata() {
        // 只 hook 一参重载：`getMusicTitle()` 内部就是 `getMusicTitle(true)`，
        // 同时 hook 两个会让回退逻辑跑两遍。
        host.message.getMusicTitle?.let { method ->
            intercept(method) { chain ->
                val result = chain.proceed()
                val target = chain.thisObject
                if (!dressingVideo(target)) return@intercept result
                notification.videoTitleFallback(target!!, result as? String) ?: result
            }
        }
        host.message.getMusicAuthor?.let { method ->
            intercept(method) { chain ->
                val result = chain.proceed()
                val target = chain.thisObject
                if (!dressingVideo(target)) return@intercept result
                val unknown = chain.args.getOrNull(0) as? Boolean ?: true
                notification.videoAuthorFallback(target!!, unknown) ?: result
            }
        }
        if (host.message.getMusicTitle == null || host.message.getMusicAuthor == null) {
            logger("找不到 MessageObject.getMusicTitle/getMusicAuthor，视频的名称与作者退回原版", null)
        }
    }

    /**
     * 当前是否该给这条消息套「后台视频」的标题/作者。
     *
     * 判据只有两条：后台会话进行中，且它确实是视频。**不能**再要求「此刻正被伪装成音乐」——
     * 播放列表的条目是 RecyclerView 在弹层构造之后异步绑定的，`AudioPlayerCell.setMessageObject`
     * 跑在任何伪装之外，加这一条会让列表里的标题作者退回原版的文件名与「未知艺术家」。
     *
     * 放宽后的覆盖面与 fork 基本一致：fork 直接改了 `MessageObject`，对任何视频都生效。
     * 原版里 `getMusicTitle`/`getMusicAuthor` 的调用点几乎都被 `isMusic()` 守着，
     * 普通视频只有经过本模块接管的界面才走得到，不会波及聊天列表一类的地方。
     */
    private fun dressingVideo(target: Any?): Boolean {
        if (target == null || !session.backgroundActive) return false
        return host.isVideoMessage(target)
    }

    /**
     * 顶部迷你播放器。
     *
     * `checkPlayer` 开头就有 `messageObject.isVideo()` 早退，视频永远不显示播放条；
     * 用伪装包住整个调用，让宿主按音乐分支布局，再在返回后补写倒计时副标题。
     */
    private fun hookMiniPlayer() {
        val ui = host.playerUi
        if (ui.contextViewClass == null) {
            logger("找不到 FragmentContextView，跳过迷你播放器", null)
            return
        }

        ui.checkPlayer?.let { method ->
            intercept(method) { chain ->
                val result = playerUi.wrapContextView { chain.proceed() }
                playerUi.rememberContextView(chain.thisObject)
                // 宿主可能刚在 updateStyle 里把标题恢复成单行，强制重排一次。
                playerUi.refreshCountdown(relayout = true)
                result
            }
        }
        ui.contextViewUpdatePlaybackButton?.let { method ->
            intercept(method) { chain -> playerUi.wrapContextView { chain.proceed() } }
        }

        // 点击迷你播放器要打开播放列表而不是跳回聊天记录。这些分支在 lambda 体内，
        // 拦不到分支本身，只能把整个点击回调包进伪装里。
        ui.contextViewClickLambdas.forEach { method ->
            intercept(method) { chain -> playerUi.wrapContextView { chain.proceed() } }
        }
        logger("迷你播放器已接管：点击回调=${ui.contextViewClickLambdas.size} 个", null)
    }

    /**
     * 播放列表弹层。
     *
     * `AudioPlayerAlert` 有十余处 `isMusic() || isVoice()` 判定，`updateTitle` 甚至会直接
     * `dismiss()`。构造、通知回调、标题与进度刷新都要包伪装，且伪装范围覆盖整份播放列表，
     * 否则列表里其他视频不会成条目。
     */
    private fun hookPlaylistAlert() {
        val ui = host.playerUi
        if (ui.alertClass == null) {
            logger("找不到 AudioPlayerAlert，跳过播放列表", null)
            return
        }

        ui.alertConstructor?.let { constructor ->
            try {
                module.hook(constructor)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain -> playerUi.wrapPlaylist { chain.proceed() } }
            } catch (t: Throwable) {
                logger("Hook AudioPlayerAlert 构造失败", t)
            }
        }
        listOfNotNull(ui.alertDidReceivedNotification, ui.alertUpdateTitle)
            .plus(ui.alertUpdateProgress)
            .forEach { method ->
                intercept(method) { chain -> playerUi.wrapPlaylist { chain.proceed() } }
            }

        // 列表里点视频：回全屏续播，而不是原地当音频重播。
        ui.cellDidPressedButton?.let { method ->
            intercept(method) { chain ->
                val cell = chain.thisObject
                if (cell != null && playerUi.openInFullscreen(cell)) null else chain.proceed()
            }
        }
    }

    /**
     * 离开会话或滚动列表时，别把后台播放当成聊天内嵌播放清掉。
     *
     * 宿主有三处会误伤，分两种手法处理：
     *
     * 1. `onRemoveFromParent` / `updateTextureViewPosition` —— 整个方法都是围绕聊天内嵌播放的
     *    清理逻辑，后台播放期间挂抑制标记，由 [hookSessionTeardown] 跳过 `cleanupPlayer`。
     *    不用身份伪装：那会让宿主走进 `setTextureView` 分支去绑定聊天内的 Surface，
     *    而后台播放根本没有 Surface。
     *
     * 2. `updateMessagesVisiblePart` —— 滚动聊天列表的主路径，方法体里还有可见区计算、已读上报、
     *    投票排队等一大堆正事，不能整体跳过，也不能笼统抑制 `cleanupPlayer`
     *    （那样会连带跳过同一个 if 的 else 分支，把视频容器留在屏幕上）。
     *    改为在方法执行期间把正在后台播放的那条消息的 `isVideo()` 说成 false，宿主三处判断
     *    `(isVideo() || isRoundVideo())`、`checkTextureViewPosition && isVideo()`、
     *    `setCurrentVideoVisible(false)` 会各自走到与 fork 守卫相同的分支上。
     */
    private fun hookChatActivityGuards() {
        val ui = host.playerUi
        if (ui.chatActivityClass == null) {
            logger("找不到 ChatActivity，跳过后台播放保护", null)
            return
        }
        listOfNotNull(ui.onRemoveFromParent, ui.updateTextureViewPosition).forEach { method ->
            intercept(method) { chain ->
                suppressCleanupDepth += 1
                try {
                    chain.proceed()
                } finally {
                    suppressCleanupDepth -= 1
                }
            }
        }
        ui.updateMessagesVisiblePart?.let { method ->
            intercept(method) { chain ->
                if (session.backgroundActive) {
                    playerUi.wrapChatScroll { chain.proceed() }
                } else {
                    chain.proceed()
                }
            }
        } ?: logger("找不到 ChatActivity.updateMessagesVisiblePart，滚动时可能中断后台播放", null)
    }

    /**
     * 播放列表条目的视频缩略图。
     *
     * 原版只认 `TL_photoSize` / `TL_photoSizeProgressive`，视频的封面往往是
     * `TL_photoStrippedSize`（模糊占位）或 `TL_photoCachedSize`，于是列表里是一片空白。
     * 在 `setMessageObject` 之后补一张 bitmap，对应 fork 对 `AudioPlayerCell` 的改动。
     */
    private fun hookPlaylistThumbnails() {
        val ui = host.playerUi
        val method = ui.cellSetMessageObject ?: return
        val radialField = ui.cellRadialProgressField ?: return
        intercept(method) { chain ->
            val result = chain.proceed()
            val target = chain.args.getOrNull(0)
            if (session.backgroundActive && host.isVideoMessage(target)) {
                applyVideoThumbnail(chain.thisObject, target!!, radialField)
            }
            result
        }
    }

    private fun applyVideoThumbnail(cell: Any?, message: Any, radialField: java.lang.reflect.Field) {
        val bridge = host.bridge
        val radial = bridge.getField(cell, radialField) ?: return
        val document = bridge.invoke(message, host.message.getDocument) ?: return
        val thumbs = bridge.getField(document, "thumbs") as? ArrayList<*> ?: return

        val fileLoader = host.fileLoaderClass
        val thumb = bridge.invokeStatic(fileLoader, "getClosestPhotoSizeWithSize", thumbs, 90)
            ?: return
        val bytes = bridge.getField(thumb, "bytes") as? ByteArray ?: return
        if (bytes.isEmpty()) return

        val bitmap = when {
            thumb.javaClass.simpleName == "TL_photoStrippedSize" -> bridge.invokeStatic(
                bridge.loadClass("org.telegram.messenger.ImageLoader"),
                "getStrippedPhotoBitmap",
                bytes,
                "b",
            ) as? android.graphics.Bitmap

            else -> runCatching {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        } ?: return

        bridge.invokeNamed(radial, "setImageOverlay", bitmap)
    }

    private fun endSession() {
        session.markBackgroundActive(false)
        photo.onBackgroundSessionEnded()
        playerUi.onSessionEnded()
        NotificationDresser.MusicServiceHandle.current = null
    }

    private fun intercept(
        method: Method,
        hooker: (io.github.libxposed.api.XposedInterface.Chain) -> Any?,
    ) {
        try {
            module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(hooker)
        } catch (t: Throwable) {
            logger("安装 Hook 失败：${method.declaringClass.simpleName}.${method.name}", t)
        }
    }

    private companion object {
        const val TAG = "NagramXVideoTimer"
    }
}
