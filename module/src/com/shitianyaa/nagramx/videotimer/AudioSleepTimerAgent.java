package com.shitianyaa.nagramx.videotimer;

import android.os.SystemClock;
import java.util.Objects;

/** 音频定时策略。播放事件与界面操作在宿主主线程结算。 */
final class AudioSleepTimerAgent {
    interface Playback {
        State read();
        void pause(State expected);
        void changed();
    }

    static final class Track {
        final int account;
        final long dialog;
        final int message;

        Track(int account, long dialog, int message) {
            this.account = account;
            this.dialog = dialog;
            this.message = message;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Track)) return false;
            Track track = (Track) other;
            return account == track.account && dialog == track.dialog && message == track.message;
        }

        @Override public int hashCode() { return Objects.hash(account, dialog, message); }
    }

    static final class State {
        // 非音乐消息保留 State，但 track 为 null，明确触发类型切换取消。
        final Track track;
        final Object player;
        final boolean playing;
        final boolean downloading;

        State(Track track, Object player, boolean playing, boolean downloading) {
            this.track = track;
            this.player = player;
            this.playing = playing;
            this.downloading = downloading;
        }
    }

    private final Playback playback;
    private final PlayingClock clock;
    private final VideoBackgroundSession timer;
    private Track anchor;
    private State stopped;
    private volatile boolean disposed;

    AudioSleepTimerAgent(Playback playback, HostBridge.Logger logger) {
        this(playback, logger, SystemClock::elapsedRealtime,
                new VideoBackgroundSession.MainThreadDelayedTaskScheduler());
    }

    AudioSleepTimerAgent(Playback playback, HostBridge.Logger logger,
                        VideoBackgroundSession.TimeProvider time,
                        VideoBackgroundSession.DelayedTaskScheduler scheduler) {
        this.playback = Objects.requireNonNull(playback, "playback");
        clock = new PlayingClock(Objects.requireNonNull(time, "time"));
        timer = new VideoBackgroundSession(logger, clock::elapsed, scheduler);
        timer.bind(this::probe, this::expire, playback::changed);
    }

    boolean arm(int minutes) {
        State state = playback.read();
        if (disposed || state == null || state.track == null || state.player == null) return false;
        anchor = state.track;
        stopped = null;
        clock.reset(state.playing);
        timer.arm(VideoBackgroundSession.MODE_DURATION, minutes, anchor.message);
        return timer.isArmed();
    }

    TimerState snapshot() { return timer.snapshot(); }
    String remaining() { return timer.getFormattedRemainingTime(); }
    boolean isArmed() { return !disposed && timer.isArmed(); }

    void cancel() {
        clock.setRunning(false);
        anchor = null;
        timer.disarm();
    }

    void dispose() {
        disposed = true;
        stopped = null;
        cancel();
    }

    /** 成功的显式播放解除结束围栏；失败的换曲不改变原定时。 */
    void played(boolean success) {
        if (disposed || !success) return;
        stopped = null;
        reconcile(playback.read());
    }

    void observe() {
        if (!disposed) reconcile(playback.read());
    }

    private void reconcile(State state) {
        if (state == null) {
            clock.setRunning(false);
            return; // 换曲同步清理与新播放器建立之间的空档。
        }
        if (stopped != null && !same(stopped, state)) stopped = null;
        if (isArmed() && (state.track == null || anchor.account != state.track.account)) {
            cancel();
        }
        clock.setRunning(isArmed() && state.track != null && state.playing);
    }

    /** 在原 delegate 续播之前执行，返回 true 表示消费本次结束事件。 */
    boolean beforeState(Object player, boolean playWhenReady, int state) {
        if (disposed) return false;
        State current = playback.read();
        if (current == null || current.player != player || current.track == null) return false;
        boolean terminal = state == MediaControllerAgent.EXO_STATE_ENDED;
        if (terminal && stopped != null && same(stopped, current)) return true;
        reconcile(current);
        clock.setRunning(isArmed() && playWhenReady && state == MediaControllerAgent.EXO_STATE_READY);
        return false;
    }

    void afterState(Object player, boolean playWhenReady, int state) {
        if (disposed) return;
        State current = playback.read();
        if (current == null || (current.player == null && !current.downloading)) {
            cancel();
            return;
        }
        reconcile(current);
        // VideoPlayer 在 delegate 返回后才更新 lastReported 字段，使用事件中的新状态。
        if (current != null && current.player == player) {
            clock.setRunning(isArmed() && current.track != null && playWhenReady
                    && state == MediaControllerAgent.EXO_STATE_READY);
        }
    }

    private VideoBackgroundSession.ProbeResult probe() {
        observe();
        // 时钟已经扣除了暂停和缓冲。保持探测可结算，避免丢失 tick 之间的短播放区间。
        return new VideoBackgroundSession.ProbeResult(isArmed(),
                anchor == null ? 0 : anchor.message, false, true);
    }

    private void expire() {
        State current = playback.read();
        Track expected = anchor;
        if (disposed || current == null || current.track == null || expected == null
                || expected.account != current.track.account) {
            cancel();
            return;
        }
        stopped = current; // 先建立围栏，pause 内同步重入的结束回调也必须被消费。
        cancel();
        playback.pause(current);
    }

    private static boolean same(State first, State second) {
        return first.player == second.player && Objects.equals(first.track, second.track);
    }

    /** 返回已播放的真实时间，倍速和 seek 不参与这个时钟。 */
    private static final class PlayingClock {
        private final VideoBackgroundSession.TimeProvider time;
        private long accumulated;
        private long last;
        private boolean running;

        PlayingClock(VideoBackgroundSession.TimeProvider time) { this.time = time; }
        void reset(boolean playing) {
            accumulated = 0L;
            last = time.elapsedRealtime();
            running = playing;
        }
        void setRunning(boolean playing) {
            elapsed();
            running = playing;
        }
        long elapsed() {
            long now = time.elapsedRealtime();
            if (running) accumulated += Math.max(0L, now - last);
            last = now;
            return accumulated;
        }
    }
}
