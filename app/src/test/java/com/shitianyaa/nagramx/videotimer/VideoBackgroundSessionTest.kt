package com.shitianyaa.nagramx.videotimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBackgroundSessionTest {
    @Test
    fun durationMinutesAreClampedAndReported() {
        val now = 10_000L
        val scheduler = FakeScheduler()
        val session = newSession({ now }, scheduler)

        session.arm(VideoBackgroundSession.MODE_DURATION, 0, currentMessageId = 17)

        assertEquals(VideoBackgroundSession.MODE_DURATION, session.mode)
        assertEquals(1, session.lastPickedMinutes)
        assertEquals(1, session.remainingMinutes)
        assertEquals(60, session.remainingSeconds)

        session.arm(VideoBackgroundSession.MODE_DURATION, 2_000, currentMessageId = 17)

        assertEquals(1_439, session.lastPickedMinutes)
        assertEquals(1_439, session.remainingMinutes)
        assertTrue(scheduler.hasPendingTask)
    }

    @Test
    fun disarmStopsTickingAndRefreshesUi() {
        val scheduler = FakeScheduler()
        var tickCount = 0
        val session = newSession({ 0L }, scheduler)
        session.bind(
            probe = { VideoBackgroundSession.ProbeResult.UNKNOWN },
            onExpire = {},
            onTick = { tickCount += 1 },
        )

        session.arm(VideoBackgroundSession.MODE_DURATION, 1, currentMessageId = 17)
        session.disarm()

        assertFalse(session.armed)
        assertFalse(scheduler.hasPendingTask)
        assertEquals(2, tickCount)
    }

    @Test
    fun durationExpiryDisarmsBeforeInvokingExpireCallback() {
        var now = 0L
        val scheduler = FakeScheduler()
        val session = newSession({ now }, scheduler)
        var expireCount = 0
        var callbackSawDisarmedState = false
        session.bind(
            probe = { VideoBackgroundSession.ProbeResult(sessionAlive = true, messageId = 17, reachedEnd = false) },
            onExpire = {
                expireCount += 1
                callbackSawDisarmedState = !session.armed
            },
        )

        session.arm(VideoBackgroundSession.MODE_DURATION, 1, currentMessageId = 17)
        now += 60_000L
        scheduler.runNext()

        assertEquals(1, expireCount)
        assertTrue(callbackSawDisarmedState)
        assertFalse(session.armed)
        assertFalse(scheduler.hasPendingTask)
    }

    @Test
    fun afterCurrentModeExpiresWhenTrackedMessageChanges() {
        val scheduler = FakeScheduler()
        val session = newSession({ 0L }, scheduler)
        var expireCount = 0
        session.bind(
            probe = {
                VideoBackgroundSession.ProbeResult(
                    sessionAlive = true,
                    messageId = 99,
                    reachedEnd = false,
                )
            },
            onExpire = { expireCount += 1 },
        )

        session.arm(VideoBackgroundSession.MODE_AFTER_CURRENT, minutes = 0, currentMessageId = 17)
        scheduler.runNext()

        assertEquals(1, expireCount)
        assertFalse(session.armed)
    }

    @Test
    fun probeResultOnlyEndsMatchingItemAtEndOrOnItemChange() {
        assertFalse(
            VideoBackgroundSession.ProbeResult(
                sessionAlive = true,
                messageId = 17,
                reachedEnd = false,
            ).endedCurrentItem(anchorId = 17),
        )
        assertTrue(
            VideoBackgroundSession.ProbeResult(
                sessionAlive = true,
                messageId = 17,
                reachedEnd = true,
            ).endedCurrentItem(anchorId = 17),
        )
        assertTrue(
            VideoBackgroundSession.ProbeResult(
                sessionAlive = true,
                messageId = 99,
                reachedEnd = false,
            ).endedCurrentItem(anchorId = 17),
        )
        assertFalse(
            VideoBackgroundSession.ProbeResult(
                sessionAlive = true,
                messageId = 0,
                reachedEnd = false,
            ).endedCurrentItem(anchorId = 17),
        )
    }

    private fun newSession(
        elapsedRealtime: () -> Long,
        scheduler: FakeScheduler,
    ): VideoBackgroundSession = VideoBackgroundSession(
        logger = { _, _ -> },
        elapsedRealtime = elapsedRealtime,
        scheduler = scheduler,
    )

    private class FakeScheduler : DelayedTaskScheduler {
        private val pending = mutableListOf<Runnable>()

        val hasPendingTask: Boolean
            get() = pending.isNotEmpty()

        override fun postDelayed(task: Runnable, delayMillis: Long) {
            pending.remove(task)
            pending += task
        }

        override fun removeCallbacks(task: Runnable) {
            pending.removeAll { it === task }
        }

        fun runNext() {
            val task = pending.single()
            pending.remove(task)
            task.run()
        }
    }
}
