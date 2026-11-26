package com.shitianyaa.nagramx.videotimer;

import java.util.ArrayList;
import java.util.List;

/** 音频状态机的真实时间、结束边界与媒体隔离回归。 */
final class AudioSleepTimerTest {
    private AudioSleepTimerTest() {}

    static void run() {
        durationAndPauses();
        trackChanges();
        expiryCallbacks();
        lifecycle();
    }

    private static void durationAndPauses() {
        Fixture fixture = new Fixture();
        fixture.agent.arm(1);
        fixture.advance(20_000);
        TestMain.check("音频按播放的真实时间扣减", fixture.agent.remaining().equals("00:40"));
        fixture.event(false, 3);
        fixture.advance(90_000);
        TestMain.check("音频暂停期间冻结倒计时", fixture.agent.remaining().equals("00:40"));
        fixture.event(true, 2);
        fixture.advance(90_000);
        TestMain.check("音频缓冲期间冻结倒计时", fixture.agent.remaining().equals("00:40"));
        fixture.event(true, 3);
        fixture.advance(39_000);
        TestMain.check("音频恢复后继续计时", fixture.agent.isArmed() && fixture.playback.paused == 0);
        fixture.advance(1_000);
        TestMain.check("音频到期只暂停一次并清除定时", fixture.playback.paused == 1 && !fixture.agent.isArmed());
        fixture.agent.beforeState(fixture.player, false, 4);
        TestMain.check("按时长到期后重复结束也被消费", fixture.agent.beforeState(fixture.player, false, 4));

        Fixture shortIntervals = new Fixture();
        shortIntervals.agent.arm(1);
        shortIntervals.time.now += 500;
        shortIntervals.event(false, 3);
        shortIntervals.time.now += 10_000;
        shortIntervals.event(true, 3);
        shortIntervals.time.now += 500;
        shortIntervals.event(false, 3);
        shortIntervals.advance(20_000);
        TestMain.check("两个 tick 之间的短播放区间不会丢失", shortIntervals.agent.remaining().equals("00:59"));
    }

    private static void trackChanges() {
        Fixture fixture = new Fixture();
        fixture.agent.arm(10);
        fixture.advance(15_000);
        fixture.playback.current = state(0, 20, 42, new Object(), true);
        fixture.agent.played(true);
        TestMain.check("同账号跨歌单切歌继承按分钟定时", fixture.agent.isArmed() && fixture.agent.remaining().equals("09:45"));
        fixture.agent.played(false);
        TestMain.check("切歌失败保留原音频定时", fixture.agent.isArmed());
        fixture.playback.current = state(1, 20, 42, new Object(), true);
        fixture.agent.played(true);
        TestMain.check("播放账号改变取消音频定时", !fixture.agent.isArmed());

        Fixture voice = new Fixture();
        voice.agent.arm(10);
        voice.playback.current = new AudioSleepTimerAgent.State(null, new Object(), true, false);
        voice.agent.played(true);
        TestMain.check("切入语音或视频清除音频定时", !voice.agent.isArmed());
        TestMain.check("语音或视频状态不能设置音频定时", !voice.agent.arm(10));

        Fixture download = new Fixture();
        download.agent.arm(10);
        download.playback.current = new AudioSleepTimerAgent.State(new AudioSleepTimerAgent.Track(0, 10, 43), null, false, true);
        download.agent.played(true);
        download.advance(90_000);
        TestMain.check("切到待下载曲目保留但冻结定时", download.agent.isArmed() && download.agent.remaining().equals("10:00"));
    }

    private static void expiryCallbacks() {
        Fixture fixture = new Fixture();
        fixture.agent.arm(1);
        TestMain.check("旧播放器的结束回调不影响当前曲目", !fixture.agent.beforeState(new Object(), true, 4)
                && fixture.agent.isArmed());
        fixture.playback.reenter = true;
        fixture.advance(60_000);
        TestMain.check("暂停内的重入结束事件被消费", fixture.playback.reentryConsumed);
        TestMain.check("到期只暂停一次", fixture.playback.paused == 1 && !fixture.agent.isArmed());
        TestMain.check("循环及恢复歌单的重复结束回调被消费", fixture.agent.beforeState(fixture.player, false, 4)
                && fixture.playback.paused == 1);
        fixture.agent.played(true);
        TestMain.check("用户再次播放解除结束围栏", !fixture.agent.beforeState(fixture.player, false, 4));

        Fixture endFallback = new Fixture();
        endFallback.agent.arm(1);
        TestMain.check("拖动到末尾的 IDLE 不提前结束定时", !endFallback.agent.beforeState(endFallback.player, true, 1) && endFallback.agent.isArmed());
        TestMain.check("拖动到末尾的缓冲不提前结束定时", !endFallback.agent.beforeState(endFallback.player, true, 2) && endFallback.agent.isArmed());
        TestMain.check("曲目自然结束透传宿主且不触发按分钟暂停", !endFallback.agent.beforeState(endFallback.player, true, 4)
                && endFallback.agent.isArmed() && endFallback.playback.paused == 0);
    }

    private static void lifecycle() {
        Fixture fixture = new Fixture();
        for (int preset : new int[]{15, 30, 45, 60, 90}) {
            fixture.agent.arm(preset);
            TestMain.check("音频预设时长 " + preset + " 分钟", fixture.agent.snapshot().selectedMinutes == preset);
        }
        fixture.agent.arm(0);
        TestMain.check("音频自定义时长沿用最小一分钟", fixture.agent.snapshot().selectedMinutes == 1);
        fixture.agent.arm(2000);
        TestMain.check("音频自定义时长沿用最大1439分钟", fixture.agent.snapshot().selectedMinutes == 1439);
        fixture.agent.arm(5);
        fixture.agent.cancel();
        TestMain.check("取消移除音频计时任务", fixture.scheduler.tasks.isEmpty());
        fixture.agent.arm(1);
        fixture.agent.dispose();
        fixture.advance(90_000);
        TestMain.check("模块重载销毁任务并禁止后续暂停", fixture.scheduler.tasks.isEmpty() && fixture.playback.paused == 0);
        TestMain.check("销毁后的音频定时不能重新设置", !fixture.agent.arm(5));
        Fixture exhausted = new Fixture();
        exhausted.agent.arm(10);
        exhausted.playback.current = state(0, 10, 42, null, false);
        exhausted.agent.afterState(exhausted.player, true, 4);
        TestMain.check("歌单自然耗尽释放播放器后取消定时", !exhausted.agent.isArmed());
    }

    private static AudioSleepTimerAgent.State state(int account, long dialog, int id, Object player, boolean playing) {
        return new AudioSleepTimerAgent.State(new AudioSleepTimerAgent.Track(account, dialog, id), player, playing, false);
    }

    private static final class Fixture {
        final Object player = new Object();
        final Time time = new Time();
        final Scheduler scheduler = new Scheduler();
        final Playback playback = new Playback();
        final AudioSleepTimerAgent agent = new AudioSleepTimerAgent(playback, (message, failure) -> {}, time, scheduler);
        Fixture() {
            playback.agent = agent;
            playback.current = state(0, 10, 42, player, true);
        }
        void advance(long milliseconds) {
            time.now += milliseconds;
            scheduler.tick();
        }
        void event(boolean playing, int state) {
            playback.current = AudioSleepTimerTest.state(0, 10, 42, player, playing && state == 3);
            agent.beforeState(player, playing, state);
            agent.afterState(player, playing, state);
        }
    }

    private static final class Playback implements AudioSleepTimerAgent.Playback {
        AudioSleepTimerAgent.State current;
        AudioSleepTimerAgent agent;
        int paused;
        boolean reenter, reentryConsumed;
        @Override public AudioSleepTimerAgent.State read() { return current; }
        @Override public void pause(AudioSleepTimerAgent.State expected) {
            paused++;
            if (reenter) reentryConsumed = agent.beforeState(expected.player, false, 4);
        }
        @Override public void changed() {}
    }

    private static final class Time implements VideoBackgroundSession.TimeProvider {
        long now;
        @Override public long elapsedRealtime() { return now; }
    }

    private static final class Scheduler implements VideoBackgroundSession.DelayedTaskScheduler {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void postDelayed(Runnable task, long delay) { if (!tasks.contains(task)) tasks.add(task); }
        @Override public void removeCallbacks(Runnable task) { tasks.remove(task); }
        void tick() {
            if (!tasks.isEmpty()) tasks.remove(0).run();
        }
    }
}
