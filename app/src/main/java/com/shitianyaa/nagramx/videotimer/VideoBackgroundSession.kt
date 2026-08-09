package com.shitianyaa.nagramx.videotimer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** 主线程延迟任务的最小边界，便于状态机在 JVM 单元测试中使用假调度器。 */
internal interface DelayedTaskScheduler {
    fun postDelayed(task: Runnable, delayMillis: Long)

    fun removeCallbacks(task: Runnable)
}

private class MainThreadDelayedTaskScheduler : DelayedTaskScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun postDelayed(task: Runnable, delayMillis: Long) {
        handler.postDelayed(task, delayMillis)
    }

    override fun removeCallbacks(task: Runnable) {
        handler.removeCallbacks(task)
    }
}

/**
 * 定时关闭的状态机。
 *
 * 对应 fork 里 `MediaController` 上的 `videoSleepTimerMode` / `videoSleepTimerDeadline` /
 * `videoSleepTimerMessageId` 三个字段和围绕它们的定时逻辑。倒计时按墙上时钟走
 * （`elapsedRealtime`，不受系统时间调整影响），暂停期间同样计时，语义是「N 分钟后停」。
 *
 * 所有状态读写都在主线程，因此不需要额外同步。
 */
internal class VideoBackgroundSession(
    private val logger: (String, Throwable?) -> Unit,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val scheduler: DelayedTaskScheduler = MainThreadDelayedTaskScheduler(),
) {
    var mode: Int = MODE_OFF
        private set

    /** 时长模式下的到期时刻（`elapsedRealtime` 基准）。 */
    private var deadline: Long = 0L

    /** 「当前视频结束后」模式下锚定的消息 id。 */
    private var anchorMessageId: Int = 0

    /** 上次用户选择的时长，用于面板回显。 */
    var lastPickedMinutes: Int = DEFAULT_MINUTES
        private set

    /** 后台播放是否正在进行（无论是否设了定时）。 */
    var backgroundActive: Boolean = false
        private set

    private var onExpire: (() -> Unit)? = null
    private var probe: (() -> ProbeResult)? = null

    /** 每次心跳都会调用，用于刷新倒计时一类的 UI。 */
    private var onTick: (() -> Unit)? = null

    val armed: Boolean get() = mode != MODE_OFF

    /** 剩余分钟数，向上取整；非时长模式返回 0。 */
    val remainingMinutes: Int
        get() {
            if (mode != MODE_DURATION) return 0
            val remaining = deadline - elapsedRealtime()
            if (remaining <= 0L) return 0
            return ((remaining + 59_999L) / 60_000L).toInt()
        }

    /** 剩余秒数，向上取整；非时长模式返回 0。用于迷你播放器的秒级倒计时。 */
    val remainingSeconds: Int
        get() {
            if (mode != MODE_DURATION) return 0
            val remaining = deadline - elapsedRealtime()
            if (remaining <= 0L) return 0
            return ((remaining + 999L) / 1_000L).toInt()
        }

    fun snapshot(): TimerState = TimerState(
        active = armed,
        mode = mode,
        remainingMinutes = if (mode == MODE_DURATION) remainingMinutes else 0,
        selectedMinutes = lastPickedMinutes,
    )

    /**
     * 注册回调。
     *
     * @param probe 每秒被调用一次，返回当前播放探测结果，用于判断「当前视频结束」。
     * @param onExpire 到期时调用，负责暂停播放并提示用户。
     * @param onTick 每次心跳后调用，用于刷新倒计时一类的 UI。
     */
    fun bind(probe: () -> ProbeResult, onExpire: () -> Unit, onTick: () -> Unit = {}) {
        this.probe = probe
        this.onExpire = onExpire
        this.onTick = onTick
    }

    fun markBackgroundActive(active: Boolean) {
        backgroundActive = active
        if (!active) disarm()
    }

    fun arm(mode: Int, minutes: Int, currentMessageId: Int) {
        when (mode) {
            MODE_DURATION -> {
                val bounded = minutes.coerceIn(1, MAX_MINUTES)
                this.mode = MODE_DURATION
                this.deadline = elapsedRealtime() + bounded * 60_000L
                this.anchorMessageId = 0
                this.lastPickedMinutes = bounded
            }

            MODE_AFTER_CURRENT -> {
                this.mode = MODE_AFTER_CURRENT
                this.deadline = 0L
                this.anchorMessageId = currentMessageId
            }

            else -> {
                disarm()
                return
            }
        }
        restartTicking()
        // 立刻刷一次，别让迷你播放器的副标题等到下一次心跳才换。
        notifyTick()
    }

    fun disarm() {
        mode = MODE_OFF
        deadline = 0L
        anchorMessageId = 0
        scheduler.removeCallbacks(tick)
        // 心跳已经停了，不在这里主动刷一次的话，取消定时后迷你播放器会一直停在最后那个倒计时上。
        notifyTick()
    }

    private fun notifyTick() {
        try {
            onTick?.invoke()
        } catch (t: Throwable) {
            logger("定时器状态变化回调失败", t)
        }
    }

    private fun restartTicking() {
        scheduler.removeCallbacks(tick)
        scheduler.postDelayed(tick, TICK_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!armed) return
            val result = try {
                probe?.invoke() ?: ProbeResult.UNKNOWN
            } catch (t: Throwable) {
                logger("定时器探测播放状态失败", t)
                ProbeResult.UNKNOWN
            }

            if (!result.sessionAlive) {
                // 先落 backgroundActive 再 disarm：disarm 会回调 onTick，
                // 顺序颠倒的话 UI 会以为后台播放还在，把最后那帧副标题留在屏幕上。
                backgroundActive = false
                disarm()
                return
            }

            val expired = when (mode) {
                MODE_DURATION -> elapsedRealtime() >= deadline
                MODE_AFTER_CURRENT -> result.endedCurrentItem(anchorMessageId)
                else -> false
            }

            if (expired) {
                disarm()
                try {
                    onExpire?.invoke()
                } catch (t: Throwable) {
                    logger("定时到期处理失败", t)
                }
                return
            }
            try {
                onTick?.invoke()
            } catch (t: Throwable) {
                logger("定时器心跳回调失败", t)
            }
            scheduler.postDelayed(this, TICK_MS)
        }
    }

    /** 一次播放状态采样。 */
    internal data class ProbeResult(
        /** 后台播放会话是否还在（播放器还活着）。 */
        val sessionAlive: Boolean,
        /** 当前正在播放的消息 id，取不到时为 0。 */
        val messageId: Int,
        /** 播放器是否已经到达结尾。 */
        val reachedEnd: Boolean,
    ) {
        /** 锚定项播完的判定：播完了，或者已经切到别的条目。 */
        fun endedCurrentItem(anchorId: Int): Boolean {
            if (anchorId == 0) return reachedEnd
            if (messageId != 0 && messageId != anchorId) return true
            return reachedEnd
        }

        companion object {
            val UNKNOWN = ProbeResult(sessionAlive = true, messageId = 0, reachedEnd = false)
        }
    }

    internal companion object {
        const val MODE_OFF = 0
        const val MODE_DURATION = 1
        const val MODE_AFTER_CURRENT = 2

        const val DEFAULT_MINUTES = 30
        private const val MAX_MINUTES = 23 * 60 + 59
        private const val TICK_MS = 1_000L
    }
}
