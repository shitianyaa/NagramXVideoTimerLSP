package com.shitianyaa.nagramx.videotimer

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import java.util.Collections
import java.util.WeakHashMap

/**
 * PhotoViewer 侧：往视频菜单注入「定时关闭」入口，并在用户确认后把播放器交给 MediaController。
 *
 * 对应 fork 在 `PhotoViewer` 里加的菜单项、`showVideoSleepTimerSheet()` 和
 * `startVideoBackgroundPlayback(int,int)`。
 */
internal class PhotoViewerAgent(
    private val host: HostProfile,
    private val media: MediaControllerAgent,
    private val session: VideoBackgroundSession,
    private val theme: HostTheme,
    private val strings: HostStrings,
    private val sheet: VideoSleepTimerSheet,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val bridge = host.bridge
    private val photo = host.photo
    private val mainHandler = Handler(Looper.getMainLooper())
    private val injected = Collections.synchronizedMap(WeakHashMap<Any, MenuEntry>())
    private val attempts = Collections.synchronizedMap(WeakHashMap<Any, Int>())

    /** 已经转交出去的播放器，用于抑制 PhotoViewer 的残留回调。 */
    @Volatile
    var transferredPlayer: Any? = null
        private set

    private class MenuEntry(val item: View)

    fun scheduleMenuInjection(photoViewer: Any) {
        mainHandler.post { injectMenu(photoViewer) }
    }

    private fun injectMenu(photoViewer: Any) {
        synchronized(injected) { if (injected.containsKey(photoViewer)) return }

        val videoMenu = bridge.getField(photoViewer, photo.videoItemField)
        val activity = bridge.invoke(photoViewer, photo.getParentActivity) as? Activity
        if (videoMenu == null || activity == null) {
            retryInjection(photoViewer)
            return
        }

        val addSubItem = bridge.findMethod(videoMenu.javaClass, "addSubItem", 3) {
            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                CharSequence::class.java.isAssignableFrom(it.parameterTypes[2])
        }
        if (addSubItem == null) {
            logger("ActionBarMenuItem.addSubItem 签名不匹配，放弃注入定时菜单", null)
            return
        }

        val item = bridge.invoke(
            videoMenu,
            addSubItem,
            MENU_ID,
            timerIcon(activity),
            strings.get(activity, "VideoSleepTimer"),
        ) as? View
        if (item == null) {
            logger("创建定时菜单项失败", null)
            return
        }

        styleMenuItem(item)
        item.setOnClickListener {
            bridge.invokeNamed(videoMenu, "closeSubMenu")
            openTimerUi(photoViewer, activity)
        }

        synchronized(injected) { injected[photoViewer] = MenuEntry(item) }
        synchronized(attempts) { attempts.remove(photoViewer) }
        refreshMenuState(photoViewer)
        logger("已注入 PhotoViewer 定时菜单", null)
    }

    private fun retryInjection(photoViewer: Any) {
        val attempt = synchronized(attempts) {
            val next = (attempts[photoViewer] ?: 0) + 1
            attempts[photoViewer] = next
            next
        }
        if (attempt > MAX_INJECTION_ATTEMPTS) {
            synchronized(attempts) { attempts.remove(photoViewer) }
            logger("PhotoViewer 菜单多次重试仍未就绪，放弃本次注入", null)
            return
        }
        mainHandler.postDelayed({ injectMenu(photoViewer) }, INJECTION_RETRY_MS * attempt)
    }

    /** 菜单项文案随定时状态变化，等价 fork 的 `updateVideoSleepTimerMenu`。 */
    fun refreshMenuState(photoViewer: Any?) {
        val entry = photoViewer?.let { synchronized(injected) { injected[it] } } ?: return
        val context = entry.item.context ?: return
        val state = session.snapshot()
        val subtext = when {
            !state.active -> ""
            state.mode == VideoBackgroundSession.MODE_AFTER_CURRENT ->
                strings.get(context, "VideoSleepTimerAfterCurrent")

            else -> strings.duration(context, state.remainingMinutes.coerceAtLeast(1))
        }
        bridge.invokeNamed(entry.item, "setSubtext", subtext.ifEmpty { null })
        // 与 fork 的 updateVideoSleepTimerMenu 一致：激活时整项染成强调色。
        bridge.invokeNamed(
            entry.item,
            "setEnabledByColor",
            state.active,
            MENU_TEXT_COLOR,
            ACCENT_COLOR,
        )
        bridge.invokeNamed(
            entry.item,
            "setSelectorColor",
            if (state.active) ACCENT_SELECTOR_COLOR else MENU_SELECTOR_COLOR,
        )
    }

    fun refreshAllMenus() {
        val viewers = synchronized(injected) { injected.keys.toList() }
        viewers.forEach { refreshMenuState(it) }
    }

    private fun openTimerUi(photoViewer: Any, activity: Activity) {
        if (!host.isVideoMessage(host.messageOf(photoViewer))) {
            toast(activity, strings.get(activity, "VideoSleepTimerChoose"))
            return
        }
        val state = session.snapshot()
        val shown = sheet.show(
            activity = activity,
            state = state,
            onPick = { mode, minutes -> onTimerPicked(photoViewer, activity, mode, minutes) },
            onCancel = { cancelTimer(activity) },
        )
        if (!shown) {
            logger("宿主 BottomSheet 不可用，改用系统对话框", null)
            showFallbackDialog(photoViewer, activity, state)
        }
    }

    private fun onTimerPicked(photoViewer: Any, activity: Activity, mode: Int, minutes: Int) {
        val message = host.messageOf(photoViewer)
        val messageId = (bridge.invoke(message, host.message.getId) as? Number)?.toInt() ?: 0

        if (!session.backgroundActive) {
            if (!startBackgroundPlayback(photoViewer, activity)) return
        }
        session.arm(mode, minutes, messageId)
        refreshAllMenus()

        val text = if (mode == VideoBackgroundSession.MODE_AFTER_CURRENT) {
            strings.get(activity, "VideoSleepTimerAfterCurrentSet")
        } else {
            strings.format(
                activity,
                "VideoSleepTimerSetFor",
                strings.duration(activity, minutes),
            )
        }
        toast(activity, text)
    }

    private fun cancelTimer(activity: Activity) {
        session.disarm()
        refreshAllMenus()
        toast(activity, strings.get(activity, "VideoSleepTimerDisabled"))
    }

    /**
     * 把当前 PhotoViewer 的播放器转成后台播放，并关闭全屏查看器。
     *
     * 与 fork 的 `startVideoBackgroundPlayback` 等价，区别是转交那一步优先走宿主原生的
     * `injectVideoPlayerToMediaController()`——原版就有这个方法，是宿主自己在用的路径，
     * 比模块直接改字段安全。
     */
    private fun startBackgroundPlayback(photoViewer: Any, activity: Activity): Boolean {
        val player = host.playerOf(photoViewer)
        val message = host.messageOf(photoViewer)
        if (player == null || message == null || !host.isVideoMessage(message)) {
            toast(activity, strings.get(activity, "VideoSleepTimerChoose"))
            return false
        }

        val position = (bridge.invoke(player, host.player.getCurrentPosition) as? Number)
            ?.toLong()?.takeIf { it > 0L && it != MediaControllerAgent.TIME_UNSET } ?: 0L

        // 转交前先取消循环，否则「播完当前视频」永远等不到结尾。
        bridge.invoke(player, host.player.setLooping, false)
        // 缓冲中也允许开启定时：先请求播放，首帧就绪后由 MediaController 接管。
        // playVideoOrWeb 返回 void，不能用返回值判断成功，只能看方法本身是否存在。
        bridge.setField(photoViewer, photo.manuallyPausedField, false)
        if (bridge.invoke(player, host.player.isPlaying) as? Boolean != true) {
            val resume = photo.playVideoOrWeb
            if (resume != null) {
                bridge.invoke(photoViewer, resume)
            } else {
                bridge.invoke(player, host.player.play)
            }
        }

        val started = media.startBackgroundPlayback(
            videoPlayer = player,
            message = message,
            playlist = videoPlaylistOf(photoViewer, message),
            positionMs = position,
        ) { transferPlayer, transferMessage ->
            transferToMediaController(photoViewer, transferPlayer, transferMessage)
        }
        if (!started) {
            transferredPlayer = null
            toast(activity, strings.get(activity, "VideoSleepTimerChoose"))
            return false
        }

        session.markBackgroundActive(true)
        media.postPlayStateChanged()
        bridge.invoke(photoViewer, photo.closePhoto, false, true)
        return true
    }

    /** 优先复用宿主入口；宿主拒绝（例如尚未 isPlaying）时退回直接注入。 */
    private fun transferToMediaController(photoViewer: Any, player: Any, message: Any): Boolean {
        transferredPlayer = player
        val controller = media.controller() ?: return false

        photo.injectToMediaController?.let { entry ->
            bridge.invoke(photoViewer, entry)
            if (bridge.getField(controller, host.media.videoPlayerField) === player) {
                return true
            }
        }

        val inject = host.media.injectVideoPlayer ?: return false
        bridge.invoke(controller, inject, player, message)
        if (bridge.getField(controller, host.media.videoPlayerField) !== player) return false

        bridge.setField(photoViewer, photo.videoPlayerField, null)
        bridge.setField(photoViewer, photo.playerInjectedField, false)
        bridge.setField(photoViewer, photo.isPlayingField, false)
        return true
    }

    /** 当前相册里的全部普通视频，用作后台播放列表。 */
    private fun videoPlaylistOf(photoViewer: Any, current: Any): List<Any> {
        val images = bridge.getField(photoViewer, photo.imagesArrField) as? List<*>
            ?: return listOf(current)
        return images.filterNotNull().filter { host.isVideoMessage(it) }
    }

    fun onBackgroundSessionEnded() {
        transferredPlayer = null
        session.markBackgroundActive(false)
        refreshAllMenus()
    }

    private fun showFallbackDialog(photoViewer: Any, activity: Activity, state: TimerState) {
        val labels = FALLBACK_MINUTES.map { strings.duration(activity, it) } +
            strings.get(activity, "VideoSleepTimerAfterCurrent") +
            if (state.active) listOf(strings.get(activity, "VideoSleepTimerCancel")) else emptyList()
        android.app.AlertDialog.Builder(activity)
            .setTitle(strings.get(activity, "VideoSleepTimer"))
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < FALLBACK_MINUTES.size -> onTimerPicked(
                        photoViewer,
                        activity,
                        VideoBackgroundSession.MODE_DURATION,
                        FALLBACK_MINUTES[which],
                    )

                    which == FALLBACK_MINUTES.size -> onTimerPicked(
                        photoViewer,
                        activity,
                        VideoBackgroundSession.MODE_AFTER_CURRENT,
                        0,
                    )

                    else -> cancelTimer(activity)
                }
            }
            .setNegativeButton(strings.get(activity, "Cancel"), null)
            .show()
    }

    private fun timerIcon(context: Context): Int {
        return listOf("baseline_timer_24", "msg_timer", "msg_autodelete", "menu_video_loop")
            .firstNotNullOfOrNull { name ->
                context.hostResource("drawable", name).takeIf { it != 0 }
            } ?: 0
    }

    private fun styleMenuItem(item: View) {
        bridge.invokeNamed(item, "setColors", MENU_TEXT_COLOR, MENU_TEXT_COLOR)
        bridge.invokeNamed(item, "setSelectorColor", MENU_SELECTOR_COLOR)
    }

    fun toast(context: Context, text: String) {
        mainHandler.post {
            try {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                logger("显示提示失败", t)
            }
        }
    }

    private companion object {
        const val MENU_ID = 0x4E585654
        const val MAX_INJECTION_ATTEMPTS = 5
        const val INJECTION_RETRY_MS = 200L
        const val MENU_TEXT_COLOR = 0xFFFAFAFA.toInt()
        const val MENU_SELECTOR_COLOR = 0x0FFFFFFF
        const val ACCENT_COLOR = 0xFF73B4EC.toInt()
        const val ACCENT_SELECTOR_COLOR = 0x0F73B4EC
        val FALLBACK_MINUTES = listOf(15, 30, 45, 60, 90)
    }
}
