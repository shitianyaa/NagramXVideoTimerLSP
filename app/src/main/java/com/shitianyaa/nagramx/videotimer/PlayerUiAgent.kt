package com.shitianyaa.nagramx.videotimer

import android.os.Handler
import android.os.Looper

/**
 * 顶部迷你播放器与播放列表弹层。
 *
 * fork 的做法是把 `FragmentContextView.checkPlayer` 的 `isVideo()` 早退守卫改掉，
 * 并把 `AudioPlayerAlert` 里十余处 `isMusic() || isVoice()` 扩成包含视频。
 * 模块拦不到方法内的分支，改为在这些方法执行期间用 [MessageIdentityMask] 把当前后台视频
 * 伪装成音乐，让宿主自己走进音乐分支。
 *
 * 与通知栏那层的区别：迷你播放器的守卫查的是 `isVideo()`，所以这里必须同时否认「我是视频」，
 * 用 [MessageIdentityMask.Spec.asMusicHidingVideo]。
 */
internal class PlayerUiAgent(
    private val host: HostProfile,
    private val session: VideoBackgroundSession,
    private val strings: HostStrings,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val bridge = host.bridge
    private val ui = host.playerUi
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 倒计时每秒都要格式化一次，类查找提到构造期做。 */
    private val androidUtilitiesClass: Class<*>? =
        bridge.loadClass("org.telegram.messenger.AndroidUtilities")

    /** 存活的迷你播放器，映射到「当前是否已改成两行布局」。 */
    private val contextViews = java.util.WeakHashMap<Any, Boolean>()

    /** 上次写入副标题的秒数，避免每次心跳都重设文本引发闪烁。 */
    private var lastCountdownSecond = -1

    /**
     * 上一次 [backgroundVideo] 判定过的消息与结论。
     *
     * `updateMessagesVisiblePart` 每帧滚动都会进来一次，每次重跑
     * `isVideoMessage` 的三个反射调用不值得。同一个 MessageObject 的这三项不会变，
     * 按引用缓存即可。
     */
    private var lastProbedMessage: Any? = null
    private var lastProbedIsVideo = false

    /** 当前正在后台播放的视频，取不到时返回 null（伪装随即失效）。 */
    private fun backgroundVideo(): Any? {
        if (!session.backgroundActive) return null
        val controller = host.mediaController() ?: return null
        val playing = bridge.invoke(controller, host.media.getPlayingMessageObject) ?: return null
        if (playing !== lastProbedMessage) {
            lastProbedMessage = playing
            lastProbedIsVideo = host.isVideoMessage(playing)
        }
        return playing.takeIf { lastProbedIsVideo }
    }

    /**
     * 迷你播放器用的伪装：既装成音乐，又否认自己是视频。
     *
     * `checkPlayer` 开头是 `if (messageObject == null || getId() == 0 || messageObject.isVideo())`
     * 直接隐藏播放条，只把 `isMusic` 设为 true 过不了这一关。
     */
    private fun <T> asMusicHidingVideo(body: () -> T): T = MessageIdentityMask.around(
        MessageIdentityMask.Spec.asMusicHidingVideo(backgroundVideo()),
        body,
    )

    /**
     * 播放列表弹层用的伪装。
     *
     * 列表里可能同时存在多个视频（后台播放列表就是整个相册的视频），
     * 所以把整份播放列表都纳入伪装，否则只有当前项会被渲染成条目。
     */
    private fun <T> asMusicForPlaylist(body: () -> T): T {
        val current = backgroundVideo() ?: return body()
        val playlist = bridge.invoke(host.mediaController(), host.media.getPlaylist) as? List<*>
        val targets = buildList {
            add(current)
            playlist?.filterNotNull()?.forEach { item ->
                if (item !== current && host.isVideoMessage(item)) add(item)
            }
        }
        return MessageIdentityMask.around(
            MessageIdentityMask.Spec.asMusicHidingVideo(*targets.toTypedArray()),
            body,
        )
    }

    fun wrapContextView(body: () -> Any?): Any? = asMusicHidingVideo(body)

    fun wrapPlaylist(body: () -> Any?): Any? = asMusicForPlaylist(body)

    /**
     * `ChatActivity` 滚动/离场路径用的伪装：只摘掉视频身份，不声称是音乐。
     *
     * 见 [MessageIdentityMask.Spec.hidingVideo]。命中后宿主的三处判断会变成：
     * 容器定位分支跳过、`checkTextureViewPosition && isVideo()` 不成立（改走 else 把容器移出屏幕）、
     * `setCurrentVideoVisible(false)` 也跳过 —— 与 fork 逐条对齐。
     */
    fun <T> wrapChatScroll(body: () -> T): T = MessageIdentityMask.around(
        MessageIdentityMask.Spec.hidingVideo(backgroundVideo()),
        body,
    )

    fun rememberContextView(view: Any?) {
        if (view == null) return
        synchronized(contextViews) { contextViews.putIfAbsent(view, false) }
    }

    /**
     * 把倒计时写进副标题，对应 fork 的 `updateVideoPlayerSubtitle`。
     *
     * 宿主的音乐样式把副标题设成 `GONE`，标题独占 36dp 高度；直接把副标题显示出来会和标题重叠。
     * fork 是在 `checkPlayer` 的视频分支里重排两行（标题 20dp 顶到 0，副标题 18dp 顶到 18），
     * 这里照抄同一组尺寸，并在会话结束时恢复宿主原本的单行布局。
     *
     * @param relayout 宿主刚跑完 `checkPlayer`（可能连带 `updateStyle` 重置过布局），
     *   需要强制重排一次；普通心跳传 false，只在秒数变化时改文本。
     */
    fun refreshCountdown(relayout: Boolean = false) {
        val context = host.appContext ?: return
        val background = session.backgroundActive && backgroundVideo() != null
        val text = if (!background) {
            null
        } else {
            when (session.mode) {
                VideoBackgroundSession.MODE_AFTER_CURRENT -> {
                    // 离开时长模式就把节流位清掉，否则再切回来时若秒数恰好相同会被误判成「没变化」。
                    lastCountdownSecond = -1
                    strings.get(context, "VideoSleepTimerAfterCurrent")
                }

                VideoBackgroundSession.MODE_DURATION -> {
                    val seconds = session.remainingSeconds
                    if (seconds == lastCountdownSecond && !relayout) return
                    lastCountdownSecond = seconds
                    strings.get(context, "VideoSleepTimer") + " · " + shortDuration(seconds)
                }

                // 后台播放但没设定时：和 fork 一样退回「视频」，副标题始终占位。
                else -> {
                    lastCountdownSecond = -1
                    strings.get(context, "AttachVideo")
                }
            }
        }
        if (text == null) lastCountdownSecond = -1

        val views = synchronized(contextViews) { contextViews.keys.toList() }
        views.forEach { view -> applySubtitle(view, text, relayout) }
    }

    /** 复用宿主的 `AndroidUtilities.formatShortDuration(int)`，缺失时自己拼 `m:ss`。 */
    private fun shortDuration(seconds: Int): String {
        val formatted = bridge.invokeStatic(
            androidUtilitiesClass,
            "formatShortDuration",
            seconds,
        ) as? CharSequence
        if (formatted != null) return formatted.toString()
        val minutes = seconds / 60
        return if (minutes >= 60) {
            "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60)
        } else {
            "%d:%02d".format(minutes, seconds % 60)
        }
    }

    /**
     * 写副标题并重排两行；[text] 为 null 表示恢复宿主原样。
     *
     * 两道闸门确保不干扰宿主自己的播放条：
     * 一是只在当前样式确实是音乐播放条时动手（通话、直播位置、导入进度共用这两个视图）；
     * 二是没给这个视图改过布局就不去「恢复」，普通音乐播放全程不碰。
     */
    private fun applySubtitle(view: Any, text: String?, relayout: Boolean) {
        val twoLine = text != null
        val wasTwoLine = synchronized(contextViews) { contextViews[view] } ?: false
        if (!twoLine && !wasTwoLine) return

        val subtitle = bridge.getField(view, ui.subtitleTextViewField) as? android.view.View ?: return
        val title = bridge.getField(view, ui.titleTextViewField) as? android.view.View
        mainHandler.post {
            try {
                if ((bridge.getField(view, ui.currentStyleField) as? Number)?.toInt()
                    != ui.styleAudioPlayer
                ) {
                    return@post
                }
                val context = (view as? android.view.View)?.context ?: return@post
                val sideMenued = bridge.getField(view, ui.isSideMenuedField) as? Boolean ?: false
                val rightMargin = 36 + if (sideMenued) 64 else 0

                if (twoLine) {
                    if (relayout || !wasTwoLine) {
                        title?.let { frame(context, it, height = 20, left = 37, top = 0, right = rightMargin) }
                        frame(context, subtitle, height = 18, left = 37, top = 18, right = rightMargin)
                    }
                    bridge.invokeNamed(subtitle, "setText", text, false)
                    subtitle.visibility = android.view.View.VISIBLE
                } else {
                    // 宿主 updateStyle 里 STYLE_AUDIO_PLAYER 的原始布局。
                    title?.let { frame(context, it, height = 36, left = 37, top = 0, right = rightMargin) }
                    subtitle.visibility = android.view.View.GONE
                }
                synchronized(contextViews) {
                    if (contextViews.containsKey(view)) contextViews[view] = twoLine
                }
            } catch (t: Throwable) {
                logger("刷新迷你播放器副标题失败", t)
            }
        }
    }

    /** 等价于宿主的 `LayoutHelper.createFrame(MATCH_PARENT, h, LEFT|TOP, l, t, r, 0)`。 */
    @android.annotation.SuppressLint("RtlHardcoded")
    private fun frame(
        context: android.content.Context,
        view: android.view.View,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = Math.ceil(density * value.toDouble()).toInt()
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            dp(height),
            // 用 LEFT 而不是 START：宿主 FragmentContextView 自己就是 LEFT|TOP，
            // 这里换成 START 会在 RTL 语言下和标题/按钮的定位对不上。
            android.view.Gravity.LEFT or android.view.Gravity.TOP,
        )
        params.setMargins(dp(left), dp(top), dp(right), 0)
        view.layoutParams = params
    }

    fun onSessionEnded() {
        lastCountdownSecond = -1
        refreshCountdown(relayout = true)
    }

    /**
     * 播放列表里点视频条目：回到全屏，而不是原地当音频重播。
     *
     * 对应 fork 的 `AudioPlayerAlert.openVideoMessage`。宿主的
     * `cleanupPlayer(notify, stopService, byVoiceEnd, transferPlayerToPhotoViewer)`
     * 第四个参数为 true 时会把播放器交回 PhotoViewer，正好用于「继续播当前这条」。
     *
     * @return true 表示已接管，调用方应跳过宿主原实现。
     */
    fun openInFullscreen(cell: Any): Boolean {
        val target = bridge.invoke(cell, ui.cellGetMessageObject) ?: return false
        if (!session.backgroundActive || !host.isVideoMessage(target)) return false

        val controller = host.mediaController() ?: return false
        val playing = bridge.invoke(controller, host.media.getPlayingMessageObject)
        val sameItem = playing === target

        val activity = bridge.getStaticField(ui.launchActivityInstanceField) as? android.app.Activity ?: run {
            logger("没有可用的 LaunchActivity，无法回到全屏", null)
            return false
        }
        val photoViewer = bridge.invoke(null, host.photo.getInstance) ?: return false

        // 关闭弹层，等它退场动画结束再开全屏，避免两个窗口叠在一起。
        bridge.getStaticField(ui.alertInstanceField)?.let { alert ->
            bridge.invokeNamed(alert, "dismiss")
        }

        mainHandler.postDelayed({
            try {
                // 同一条视频：把播放器交回 PhotoViewer 续播；换一条：先彻底停掉再开新的。
                bridge.invoke(controller, host.media.cleanupPlayer4, true, true, false, sameItem)
                bridge.invoke(photoViewer, host.photo.setParentActivity, activity, null, null)
                openPhoto(photoViewer, target)
                bridge.invoke(controller, host.media.resetGoingToShowMessageObject)
            } catch (t: Throwable) {
                logger("回到全屏失败", t)
            }
        }, REOPEN_DELAY_MS)
        return true
    }

    /** `PhotoViewer.openPhoto(MessageObject, long, long, boolean, PhotoViewerProvider, boolean)` 家族。 */
    private fun openPhoto(photoViewer: Any, message: Any): Boolean {
        val dialogId = (bridge.invoke(message, host.message.getDialogId) as? Number)?.toLong() ?: 0L
        val provider = bridge.newInstance(
            "org.telegram.ui.PhotoViewer\$EmptyPhotoViewerProvider",
        )
        val opener = bridge.findMethod(host.photoViewerClass, "openPhoto", 6) {
            it.parameterTypes[0].name.endsWith(".MessageObject") &&
                it.parameterTypes[1] == Long::class.javaPrimitiveType &&
                it.parameterTypes[2] == Long::class.javaPrimitiveType
        }
        if (opener == null || provider == null) {
            logger("找不到 PhotoViewer.openPhoto 兼容重载", null)
            return false
        }
        val args = arrayOfNulls<Any?>(6)
        args[0] = message
        args[1] = dialogId
        args[2] = 0L
        opener.parameterTypes.forEachIndexed { index, type ->
            if (index < 3) return@forEachIndexed
            args[index] = when {
                type == Boolean::class.javaPrimitiveType -> index == opener.parameterTypes.lastIndex
                type.isAssignableFrom(provider.javaClass) -> provider
                else -> null
            }
        }
        bridge.invoke(photoViewer, opener, *args)
        return true
    }

    private companion object {
        /** 与 fork 的 `AndroidUtilities.runOnUIThread(..., 200)` 保持一致。 */
        const val REOPEN_DELAY_MS = 200L
    }
}
