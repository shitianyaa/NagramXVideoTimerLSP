package com.shitianyaa.nagramx.videotimer

/**
 * 让后台播放的视频在通知栏与媒体会话里表现得像音乐。
 *
 * fork 的做法是把 `MusicPlayerService` 里十余处 `messageObject.isMusic()` 扩写成
 * `isMusic() || shouldShowVideoSkipActions()`，Hook 拦不到方法内部的分支，所以这里改成在
 * `createNotification` / `updatePlaybackState` 执行期间用 [MessageIdentityMask] 把当前视频
 * 伪装成音乐，让宿主自己走进音乐分支。
 *
 * 副作用是同时拿到了音乐分支的全部能力：紧凑视图 5 个按钮、上一首/下一首、
 * `ACTION_SKIP_TO_*`、`METADATA_KEY_DURATION`、锁屏进度条。
 *
 * 与 fork 的差异见 [dressedNotificationGaps]。
 */
internal class NotificationDresser(
    private val host: HostProfile,
    private val session: VideoBackgroundSession,
    private val strings: HostStrings,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val bridge = host.bridge

    /** 「视频」，用作没有 caption 时的标题与取不到发送者时的作者。 */
    private fun attachVideo(): String? =
        host.appContext?.let { strings.get(it, "AttachVideo") }

    /**
     * 只在「模块自己发起的后台视频播放」期间伪装，避免影响宿主正常的音乐/语音/圆视频通知。
     */
    private fun maskTarget(): Any? {
        if (!session.backgroundActive) return null
        val controller = host.mediaController() ?: return null
        val playing = bridge.invoke(controller, host.media.getPlayingMessageObject)
        return playing?.takeIf { host.isVideoMessage(it) }
    }

    /**
     * 包裹一次宿主调用，使其中的身份查询把当前后台视频看成音乐。
     *
     * 用 [MessageIdentityMask.Spec.asMusic] 而非 `asMusicHidingVideo`：通知与媒体会话分支里
     * `isVideo()` 只出现在 fork 新增的代码中，原版根本不查，多伪造一项只会扩大影响面。
     */
    fun <T> around(body: () -> T): T = MessageIdentityMask.around(
        MessageIdentityMask.Spec.asMusic(maskTarget()),
        body,
    )

    /**
     * `getMusicTitle(unknown)` 的视频回退，顺序与 fork 一致：
     * 文件名 → caption 首行 → 「视频」。
     *
     * 原版对视频走完文档属性循环后要么返回 `FileLoader.getDocumentFileName(document)`，
     * 要么落到 `AudioUnknownTitle`（「未知曲目」）。文件名有值就沿用宿主结果 —— fork 也是
     * 把视频分支加在文件名判断**之后**的；只有宿主交白卷时才轮到 caption。
     */
    fun videoTitleFallback(messageObject: Any, hostResult: String?): String? {
        if (!hostResult.isNullOrBlank() && !isUnknownTitle(hostResult)) return hostResult
        captionFirstLine(messageObject)?.let { return it }
        return attachVideo()
    }

    /** 宿主是否已经放弃取名（返回了「未知曲目」占位）。 */
    private fun isUnknownTitle(value: String): Boolean {
        val context = host.appContext ?: return false
        return value == strings.get(context, "AudioUnknownTitle")
    }

    /**
     * `getMusicAuthor(unknown)` 的视频回退，逐条对齐 fork 的 `getVideoAuthor`。
     *
     * 原版对视频走完 `TL_documentAttributeVideo` 分支后落到 `AudioUnknownArtist`（「未知艺术家」），
     * fork 改成显示来源。顺序：自己 → 转发来源名 → 转发来源 peer → 发送者 peer →
     * 会话 peer → senderId → 「视频」。peer 一律交给宿主的
     * `DialogObject.getPeerDialogId` + `MessagesController.getPeerName` 解析，
     * 不重造 user/chat/channel 的分支。
     */
    fun videoAuthorFallback(messageObject: Any, unknown: Boolean): String? {
        val account =
            (bridge.getField(messageObject, host.message.currentAccountField) as? Number)?.toInt()
                ?: host.selectedAccount
        val owner = bridge.getField(messageObject, host.message.messageOwnerField)
        val fwdFrom = bridge.getField(owner, host.tl.fwdFrom)

        if (isFromSelf(messageObject, fwdFrom, account)) {
            host.appContext?.let { return strings.get(it, "FromYou") }
        }

        (bridge.getField(fwdFrom, host.tl.fwdFromName) as? String)?.takeIf { it.isNotBlank() }
            ?.let { return it }

        host.peerNameOf(account, bridge.getField(fwdFrom, host.tl.fwdFromId))?.let { return it }

        val fromId = bridge.getField(owner, host.tl.fromId)
        host.peerNameOf(account, fromId)?.let { return it }
        // 频道消息没有 from_id，作者就是频道本身。
        if (fromId == null) {
            host.peerNameOf(account, bridge.getField(owner, host.tl.peerId))?.let { return it }
        }

        val senderId =
            (bridge.invoke(messageObject, host.message.getSenderId) as? Number)?.toLong() ?: 0L
        host.peerName(account, senderId)?.let { return it }

        return if (unknown) attachVideo() else null
    }

    /** 自己发的，或转发自自己的消息。 */
    private fun isFromSelf(messageObject: Any, fwdFrom: Any?, account: Int): Boolean {
        if (bridge.invoke(messageObject, host.message.isOutOwner) as? Boolean == true) return true
        if (fwdFrom == null) return false
        val self = host.clientUserId(account)
        if (self == 0L) return false
        val fwdPeer = bridge.getField(fwdFrom, host.tl.fwdFromId) ?: return false
        // 只认用户身份的转发来源：群/频道的 dialogId 是负数，不会与自己的 id 相等。
        return (bridge.getField(fwdPeer, "user_id") as? Number)?.toLong() == self
    }

    /** caption 首行，用作视频标题；无 caption 返回 null。 */
    private fun captionFirstLine(messageObject: Any): String? {
        val owner = bridge.getField(messageObject, host.message.messageOwnerField) ?: return null
        val text = bridge.getField(owner, host.tl.messageText) as? String
        if (text.isNullOrBlank()) return null
        return text.trim().substringBefore('\n').trim().takeIf { it.isNotBlank() }
    }

    /**
     * 强制刷新通知与播放状态。
     *
     * 播放位置写进 `PlaybackStateCompat` 后系统会自行推进，只有在 seek 或播放/暂停切换后才需要重发。
     * 宿主 `MusicPlayerService` 监听 `messagePlayingPlayStateChanged` 已覆盖大部分情形，
     * 这里只处理定时器到期这类模块内部触发的变化。
     */
    fun refresh(reason: String) {
        val service = MusicServiceHandle.current ?: return
        val playing = maskTarget() ?: return
        try {
            around {
                bridge.invoke(service, host.musicService.createNotification, playing, false)
                val positionMs =
                    (bridge.invoke(host.mediaController(), host.media.getProgressMs, playing)
                        as? Number)?.toLong() ?: -1L
                bridge.invoke(service, host.musicService.updatePlaybackState, positionMs)
            }
        } catch (t: Throwable) {
            logger("刷新通知失败（$reason）", t)
        }
    }

    /** 与 fork 通知栏仍存在的差异，写进启动日志便于对照。 */
    val dressedNotificationGaps: String
        get() = buildList {
            add("无封面（AudioInfo 为抽象类，无法注入 cover）")
            if (host.media.getProgressMs == null) add("无精确进度（缺 getProgressMs）")
        }.joinToString("；")

    /** 当前 `MusicPlayerService` 实例，供模块主动刷新通知。 */
    internal object MusicServiceHandle {
        @Volatile
        var current: Any? = null
    }
}
