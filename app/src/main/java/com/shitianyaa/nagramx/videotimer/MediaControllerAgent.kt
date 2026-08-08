package com.shitianyaa.nagramx.videotimer

import android.content.Intent
import android.media.AudioManager

/**
 * MediaController 侧的操作与 Hook。
 *
 * 对应 fork 加在 `MediaController` 上的 `startVideoBackgroundPlayback` /
 * `transferVideoPlayerFromPhotoViewer` / `setVideoPlaylist` 以及散布在原有方法里的
 * `videoBackgroundPlayback` 守卫。模块不能改宿主方法体，因此：
 *
 * - 能整体替换的（`canStartMusicPlayerService`、`setCurrentVideoVisible`）直接改返回值或短路；
 * - 分支在方法内部的（`playMessage` 里的 `clearPlaylist()`）用「进入前快照、退出后还原」抵消；
 * - 圆形浮窗（`PipRoundVideoView`）靠让 `show()` 抛异常触发宿主自己的 `catch` 分支置空。
 */
internal class MediaControllerAgent(
    private val host: HostProfile,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val bridge = host.bridge
    private val media = host.media
    private val player = host.player

    fun controller(): Any? = host.mediaController()

    fun playingMessage(): Any? = bridge.invoke(controller(), media.getPlayingMessageObject)

    fun activePlayer(): Any? = bridge.getField(controller(), media.videoPlayerField)

    fun isPaused(): Boolean = bridge.getField(controller(), media.isPausedField) as? Boolean ?: true

    /** 真正在出声：未暂停、播放器 isPlaying、且已进入 READY。 */
    fun isActuallyPlaying(): Boolean {
        if (isPaused()) return false
        val current = activePlayer() ?: return false
        val playing = bridge.invoke(current, player.isPlaying) as? Boolean ?: false
        if (!playing) return false
        val state = (bridge.invoke(current, player.getPlaybackState) as? Number)?.toInt()
        return state == null || state == EXO_STATE_READY
    }

    fun hasReachedEnd(): Boolean {
        val current = activePlayer() ?: return false
        val state = (bridge.invoke(current, player.getPlaybackState) as? Number)?.toInt()
        return state == EXO_STATE_ENDED
    }

    /** 播放器返回 TIME_UNSET 时退回消息自带的秒级时长，和 fork 的处理一致。 */
    fun durationMs(): Long {
        val current = activePlayer()
        val fromPlayer = (bridge.invoke(current, player.getDuration) as? Number)?.toLong() ?: 0L
        if (fromPlayer > 0L && fromPlayer != TIME_UNSET) return fromPlayer
        val message = playingMessage() ?: return 0L
        val seconds = (bridge.invoke(message, host.message.getDuration) as? Number)?.toDouble()
            ?: return 0L
        return (seconds * 1000.0).toLong()
    }

    fun positionMs(): Long {
        val current = activePlayer() ?: return 0L
        val position = (bridge.invoke(current, player.getCurrentPosition) as? Number)?.toLong()
            ?: return 0L
        return if (position < 0L || position == TIME_UNSET) 0L else position
    }

    fun pausePlayback() {
        val controller = controller() ?: return
        val message = playingMessage() ?: return
        bridge.invoke(controller, media.pauseMessage, message)
    }

    /**
     * 把 PhotoViewer 的播放器整体接管为后台播放，等价 fork 的
     * `MediaController.startVideoBackgroundPlayback`。步骤顺序不可调整：先摘除渲染目标，
     * 再清理旧播放器，最后注入并建列表。
     */
    fun startBackgroundPlayback(
        videoPlayer: Any,
        message: Any,
        playlist: List<Any>,
        positionMs: Long,
        transfer: (Any, Any) -> Boolean,
    ): Boolean {
        val controller = controller() ?: return false

        // 1. 摘掉画面输出，播放器变成纯音频。
        bridge.invoke(videoPlayer, player.setDelegate, null)
        bridge.invoke(videoPlayer, player.setSurfaceView, null)
        bridge.invoke(videoPlayer, player.setTextureView, null)

        // 2. 清掉 MediaController 里可能存在的另一个播放器。
        val existingVideo = bridge.getField(controller, media.videoPlayerField)
        val existingAudio = bridge.getField(controller, media.audioPlayerField)
        if (existingAudio != null || existingVideo != null && existingVideo !== videoPlayer) {
            bridge.invoke(controller, media.cleanupPlayer2, false, false)
        }

        // 3. 断开聊天里的渲染容器，并确保圆形浮窗不会被重建。
        bridge.setField(controller, media.currentTextureViewField, null)
        bridge.setField(controller, media.currentAspectRatioFrameLayoutField, null)
        bridge.setField(controller, media.currentTextureViewContainerField, null)
        bridge.setField(controller, media.currentAspectRatioReadyField, false)
        closeRoundVideoOverlay(controller)

        // 4. 回种进度，否则通知栏和迷你播放器都会从 0 开始。
        seedProgress(message, positionMs)

        // 5. 走音乐音频流，锁屏音量键才会控到媒体音量。
        bridge.invoke(videoPlayer, player.setStreamType, AudioManager.STREAM_MUSIC)

        // 6. 注入。注意 injectVideoPlayer 内部会调用 clearPlaylist()。
        if (!transfer(videoPlayer, message)) {
            logger("播放器转交失败，后台播放启动失败", null)
            return false
        }
        if (bridge.getField(controller, media.videoPlayerField) !== videoPlayer) {
            logger("MediaController 未接受注入的播放器，后台播放启动失败", null)
            return false
        }
        bridge.setField(controller, media.isPausedField, false)

        // 7. 列表必须在注入之后写入。
        applyPlaylist(controller, playlist, message)

        startMusicPlayerService()
        return true
    }

    /** 结束后台会话并让宿主释放播放器。 */
    fun stopBackgroundPlayback() {
        val controller = controller() ?: return
        bridge.invoke(controller, media.cleanupPlayer2, true, true)
    }

    private fun seedProgress(message: Any, positionMs: Long) {
        val durationSeconds = (bridge.invoke(message, host.message.getDuration) as? Number)
            ?.toDouble() ?: 0.0
        val durationMs = (durationSeconds * 1000.0).toLong()
        val bounded = when {
            positionMs <= 0L -> 0L
            durationMs > 0L -> positionMs.coerceAtMost((durationMs - 1L).coerceAtLeast(0L))
            else -> positionMs
        }
        bridge.setField(
            message,
            host.message.audioProgressMsField,
            bounded.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
        bridge.setField(
            message,
            host.message.audioProgressField,
            if (durationMs > 0L) (bounded.toFloat() / durationMs) else 0f,
        )
        bridge.setField(message, host.message.audioProgressSecField, (bounded / 1000L).toInt())
    }

    /** 等价 fork 的 `setVideoPlaylist`：去重、写入映射、排序、定位当前项。 */
    private fun applyPlaylist(controller: Any, playlist: List<Any>, current: Any) {
        if (!host.canManagePlaylist) return

        @Suppress("UNCHECKED_CAST")
        val list = bridge.getField(controller, media.playlistField) as? MutableList<Any> ?: return

        @Suppress("UNCHECKED_CAST")
        val map = bridge.getField(controller, media.playlistMapField)
            as? MutableMap<Int, Any> ?: return

        list.clear()
        map.clear()
        (bridge.getField(controller, media.shuffledPlaylistField) as? MutableList<*>)?.clear()

        val ordered = buildList {
            playlist.forEach { candidate ->
                if (host.isVideoMessage(candidate) && none { it === candidate }) add(candidate)
            }
            if (none { it === current }) add(current)
        }
        ordered.forEach { item ->
            list.add(item)
            (bridge.invoke(item, host.message.getId) as? Number)?.let { map[it.toInt()] = item }
        }

        bridge.setField(controller, media.forceLoopCurrentPlaylistField, false)
        bridge.invoke(controller, media.sortPlaylist)
        bridge.setField(controller, media.currentPlaylistNumField, indexOf(list, current))
        if (isShuffleEnabled()) bridge.invoke(controller, media.buildShuffledPlayList)
    }

    private fun indexOf(list: List<Any>, target: Any): Int {
        list.forEachIndexed { index, item -> if (item === target) return index }
        return 0
    }

    private fun isShuffleEnabled(): Boolean {
        val sharedConfig = bridge.loadClass("org.telegram.messenger.SharedConfig")
        return bridge.getStaticField(sharedConfig, "shuffleMusic") as? Boolean ?: false
    }

    /** 关闭并清空圆形视频浮窗，等价 fork 的 `closePipRoundVideoView`。 */
    fun closeRoundVideoOverlay(controller: Any?) {
        if (controller == null) return
        bridge.getField(controller, media.pipRoundVideoViewField)?.let { overlay ->
            bridge.invokeNamed(overlay, "close", false)
        }
        bridge.setField(controller, media.pipRoundVideoViewField, null)
        bridge.setField(controller, media.pipSwitchingStateField, 0)
    }

    /**
     * 用 `startService` 而不是 `startForegroundService`，与宿主 `playMessage` 的行为一致：
     * 此刻 PhotoViewer 仍在前台，不受后台启动限制，服务自己会转前台。
     */
    private fun startMusicPlayerService() {
        val serviceClass = host.musicPlayerServiceClass ?: return
        val applicationLoader = bridge.loadClass("org.telegram.messenger.ApplicationLoader")
        val context = bridge.getStaticField(applicationLoader, "applicationContext")
            as? android.content.Context ?: return
        try {
            context.startService(Intent(context, serviceClass))
        } catch (t: Throwable) {
            logger("启动 MusicPlayerService 失败", t)
        }
    }

    /** 通知宿主刷新播放状态，让通知栏与迷你播放器同步。 */
    fun postPlayStateChanged() {
        val message = playingMessage() ?: return
        val account = (bridge.getField(message, host.message.currentAccountField) as? Number)
            ?.toInt() ?: return
        val id = (bridge.invoke(message, host.message.getId) as? Number)?.toInt() ?: return
        val notificationCenter = bridge.loadClass("org.telegram.messenger.NotificationCenter")
        val instance = bridge.invokeStatic(notificationCenter, "getInstance", account) ?: return
        val event = (
            bridge.getStaticField(notificationCenter, "messagePlayingPlayStateChanged") as? Number
            )?.toInt() ?: return
        bridge.invokeNamed(instance, "postNotificationName", event, arrayOf<Any>(id))
    }

    internal companion object {
        const val EXO_STATE_READY = 3
        const val EXO_STATE_ENDED = 4
        const val TIME_UNSET = Long.MIN_VALUE + 1
    }
}
