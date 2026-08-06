package com.shitianyaa.nagramx.videotimer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

internal class PlaybackCoordinator(
    private val module: XposedModule,
    private val profile: HostProfile,
) {
    private val bridge = profile.bridge
    private val mainHandler = Handler(Looper.getMainLooper())
    private val injectedMenus = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val injectionAttempts = Collections.synchronizedMap(WeakHashMap<Any, Int>())
    private val sessions = Collections.synchronizedMap(IdentityHashMap<Any, PlaybackSession>())

    fun scheduleMenuInjection(photoViewer: Any) {
        mainHandler.post { injectMenuIfNeeded(photoViewer) }
    }

    fun onMediaControllerCleanup(controller: Any, transferToViewer: Boolean) {
        if (!transferToViewer) {
            synchronized(sessions) {
                sessions.remove(controller)?.stopTimerOnly()
            }
        }
    }

    private fun injectMenuIfNeeded(photoViewer: Any) {
        synchronized(injectedMenus) {
            if (injectedMenus.containsKey(photoViewer)) return
        }

        val videoMenu = bridge.getField(photoViewer, profile.videoMenuField)
        val activity = bridge.invoke(photoViewer, profile.getParentActivity) as? Activity
        if (videoMenu == null || activity == null) {
            retryMenuInjection(photoViewer)
            return
        }

        val addSubItem = bridge.findMethod(videoMenu.javaClass, "addSubItem", 3) {
            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                CharSequence::class.java.isAssignableFrom(it.parameterTypes[2])
        }
        if (addSubItem == null) {
            log("找不到 ActionBarMenuItem.addSubItem(int, int, CharSequence)")
            return
        }

        val item = bridge.invoke(
            videoMenu,
            addSubItem,
            MENU_ID,
            resolveHostTimerIcon(activity),
            MENU_TITLE,
        ) as? View
        if (item == null) {
            log("创建定时菜单项失败")
            return
        }
        bridge.invokeNamed(item, "setColors", 0xfffafafa.toInt(), 0xfffafafa.toInt())
        bridge.invokeNamed(item, "setSelectorColor", 0x0fffffff)
        item.setOnClickListener {
            bridge.invokeNamed(videoMenu, "closeSubMenu")
            showTimerDialog(photoViewer, activity)
        }
        synchronized(injectedMenus) {
            injectedMenus[photoViewer] = true
        }
        synchronized(injectionAttempts) {
            injectionAttempts.remove(photoViewer)
        }
        log("已注入 PhotoViewer 定时播放菜单")
    }

    private fun retryMenuInjection(photoViewer: Any) {
        val attempt = synchronized(injectionAttempts) {
            val next = (injectionAttempts[photoViewer] ?: 0) + 1
            injectionAttempts[photoViewer] = next
            next
        }
        if (attempt > MAX_MENU_INJECTION_ATTEMPTS) {
            synchronized(injectionAttempts) {
                injectionAttempts.remove(photoViewer)
            }
            log("PhotoViewer 菜单在重试后仍未准备好，停止本次注入")
            return
        }
        mainHandler.postDelayed(
            { injectMenuIfNeeded(photoViewer) },
            MENU_INJECTION_RETRY_MS * attempt,
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun resolveHostTimerIcon(activity: Activity): Int {
        return listOf("baseline_timer_24", "msg_timer", "menu_video_loop")
            .firstNotNullOfOrNull { name ->
                activity.resources.getIdentifier(name, "drawable", activity.packageName)
                    .takeIf { it != 0 }
            } ?: 0
    }

    private fun showTimerDialog(photoViewer: Any, activity: Activity) {
        val choices = mutableListOf(
            TimerChoice("播放 15 分钟", profile.timerDurationMode, 15),
            TimerChoice("播放 30 分钟", profile.timerDurationMode, 30),
            TimerChoice("播放 1 小时", profile.timerDurationMode, 60),
            TimerChoice("当前视频结束后停止", profile.timerAfterCurrentMode, 0),
        )
        if (hasActiveTimer()) {
            choices += TimerChoice("取消当前定时器", HostProfile.MODE_OFF, 0)
        }

        AlertDialog.Builder(activity)
            .setTitle("选择停止时间")
            .setItems(choices.map { it.label }.toTypedArray()) { _, which ->
                val choice = choices.getOrNull(which) ?: return@setItems
                if (choice.mode == HostProfile.MODE_OFF) {
                    cancelTimer(activity)
                    return@setItems
                }
                if (!start(photoViewer, activity, choice.mode, choice.minutes)) {
                    Toast.makeText(
                        activity,
                        "当前播放器状态不支持后台定时播放",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun hasActiveTimer(): Boolean {
        val controller = profile.currentMediaController() ?: return false
        if (profile.isNativeTimerActive(controller)) return true
        return synchronized(sessions) {
            sessions[controller]?.isActive == true
        }
    }

    private fun cancelTimer(activity: Activity) {
        val controller = profile.currentMediaController()
        var cancelled = false
        if (controller != null && profile.isNativeTimerActive(controller)) {
            val clearMethod = profile.nativeTimerClear
            if (clearMethod != null) {
                bridge.invoke(controller, clearMethod)
                cancelled = true
            }
        }
        val legacySession = synchronized(sessions) {
            if (controller == null) null else sessions.remove(controller)
        }
        if (legacySession != null) {
            legacySession.stopTimerOnly()
            cancelled = true
        }
        Toast.makeText(
            activity,
            if (cancelled) "已取消定时器，后台播放继续" else "当前没有活动定时器",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun start(photoViewer: Any, activity: Activity, mode: Int, minutes: Int): Boolean {
        val nativeStart = profile.nativeBackgroundStart
        if (nativeStart != null) {
            val started = bridge.invoke(photoViewer, nativeStart, mode, minutes) as? Boolean == true
            if (started) {
                closePhoto(photoViewer)
                Toast.makeText(activity, "已开启后台播放定时器", Toast.LENGTH_SHORT).show()
            }
            return started
        }
        return startLegacy(photoViewer, activity, mode, minutes)
    }

    /**
     * Compatibility path for builds that expose the old PhotoViewer transfer entry but not the
     * dedicated start API. The timer itself remains in this module process generation.
     */
    private fun startLegacy(photoViewer: Any, activity: Activity, mode: Int, minutes: Int): Boolean {
        val transferMethod = profile.transferPlayerMethod ?: return false
        val controllerVideoPlayerField = profile.controllerVideoPlayerField ?: return false
        val player = bridge.getField(photoViewer, "videoPlayer") ?: return false
        val message = bridge.getField(photoViewer, "currentMessageObject") ?: return false
        val isVideo = bridge.invokeNamed(message, "isVideo") as? Boolean ?: false
        val isLivePhoto = bridge.invokeNamed(message, "isLivePhoto") as? Boolean ?: false
        val isPlaying = bridge.invokeNamed(player, "isPlaying") as? Boolean ?: false
        if (!isVideo || isLivePhoto || !isPlaying) return false

        bridge.invokeNamed(player, "setLooping", false)
        bridge.invoke(photoViewer, transferMethod)

        val controller = profile.currentMediaController() ?: return false
        val transferredPlayer = bridge.getField(controller, controllerVideoPlayerField)
        if (transferredPlayer !== player) {
            log("旧版播放器转移未生效，保留 PhotoViewer 当前状态")
            return false
        }
        detachSurface(transferredPlayer)

        val session = PlaybackSession(
            coordinator = this,
            controller = controller,
            message = message,
            player = transferredPlayer,
            mode = mode,
            remainingMs = if (mode == profile.timerDurationMode) minutes * 60_000L else 0L,
        )
        synchronized(sessions) {
            sessions.remove(controller)?.stopTimerOnly()
            sessions[controller] = session
        }
        startMusicPlayerService(activity)
        session.start()
        closePhoto(photoViewer)
        Toast.makeText(activity, "已开启后台播放定时器", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun detachSurface(player: Any) {
        val setSurfaceView = bridge.findMethod(player.javaClass, "setSurfaceView", 1) {
            it.parameterTypes[0] == SurfaceView::class.java
        }
        val setTextureView = bridge.findMethod(player.javaClass, "setTextureView", 1) {
            it.parameterTypes[0] == TextureView::class.java
        }
        bridge.invoke(player, setSurfaceView, null)
        bridge.invoke(player, setTextureView, null)
    }

    private fun startMusicPlayerService(activity: Activity) {
        val serviceClass = profile.musicPlayerService ?: return
        try {
            activity.startForegroundService(Intent(activity, serviceClass))
        } catch (t: Throwable) {
            log("启动宿主 MusicPlayerService 失败", t)
        }
    }

    private fun closePhoto(photoViewer: Any) {
        bridge.invoke(photoViewer, profile.closePhoto, false, true)
    }

    private fun stopSession(session: PlaybackSession) {
        session.stopTimerOnly()
        bridge.invokeNamed(session.player, "pause")
        bridge.invokeNamed(session.controller, "pauseMessage", session.message, false)
        synchronized(sessions) {
            if (sessions[session.controller] === session) {
                sessions.remove(session.controller)
            }
        }
    }

    private fun log(message: String, throwable: Throwable? = null) {
        module.log(android.util.Log.INFO, TAG, message, throwable)
    }

    private data class TimerChoice(
        val label: String,
        val mode: Int,
        val minutes: Int,
    )

    internal class PlaybackSession(
        private val coordinator: PlaybackCoordinator,
        val controller: Any,
        val message: Any,
        val player: Any,
        private val mode: Int,
        private var remainingMs: Long,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var lastTick = SystemClock.elapsedRealtime()
        var isActive = false
            private set

        private val tick = object : Runnable {
            override fun run() {
                if (!isActive) return
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - lastTick).coerceAtLeast(0L)
                lastTick = now
                val playing = coordinator.bridge.invokeNamed(player, "isPlaying") as? Boolean == true
                if (playing && mode == coordinator.profile.timerDurationMode) {
                    remainingMs = (remainingMs - elapsed).coerceAtLeast(0L)
                }

                val duration = (coordinator.bridge.invokeNamed(player, "getDuration") as? Number)
                    ?.toLong() ?: 0L
                val position = (coordinator.bridge.invokeNamed(player, "getCurrentPosition") as? Number)
                    ?.toLong() ?: 0L
                val playbackState = (coordinator.bridge.invokeNamed(player, "getPlaybackState") as? Number)
                    ?.toInt()
                val ended = mode == coordinator.profile.timerAfterCurrentMode &&
                    (playbackState == EXOPLAYER_STATE_ENDED ||
                        playing && duration > 0L && position >= duration - END_THRESHOLD_MS)
                if ((mode == coordinator.profile.timerDurationMode && remainingMs <= 0L) || ended) {
                    coordinator.stopSession(this@PlaybackSession)
                    return
                }
                handler.postDelayed(this, TICK_MS)
            }
        }

        fun start() {
            isActive = true
            lastTick = SystemClock.elapsedRealtime()
            handler.post(tick)
        }

        fun stopTimerOnly() {
            isActive = false
            handler.removeCallbacks(tick)
        }
    }

    companion object {
        private const val TAG = "NagramXVideoTimer"
        private const val MENU_ID = 0x4E585654
        private const val MENU_TITLE = "后台定时播放"
        private const val MAX_MENU_INJECTION_ATTEMPTS = 5
        private const val MENU_INJECTION_RETRY_MS = 200L
        private const val TICK_MS = 500L
        private const val END_THRESHOLD_MS = 250L
        private const val EXOPLAYER_STATE_ENDED = 4
    }
}
