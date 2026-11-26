package com.shitianyaa.nagramx.videotimer;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面 JVM 单元测试入口（不依赖第三方测试运行器）。
 */
public final class TestMain {

    private static int passed = 0;
    private static int failed = 0;

    static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 运行 NagramTimer JVM 单元测试 ===");

        testSessionDurationClamped();
        testSessionDisarmStopsTicking();
        testSessionDurationExpiry();
        testSessionAfterCurrentMode();
        testMarkBackgroundActiveDoesNotDisarm();
        testPauseFreezesCountdown();
        testFormattedRemainingTime();
        testProbeResultEnded();
        testLostPlaybackSession();
        testIdentityMask();
        HookRegressionTest.run();
        NiagramClassResolutionTest.run();
        AudioSleepTimerTest.run();
        AudioTimerHostTest.run();

        System.out.println();
        if (failed == 0) {
            System.out.println("ALL TESTS PASSED (" + passed + ")");
        } else {
            System.out.println("TESTS FAILED: " + failed + " failed / " + (passed + failed) + " total");
            System.exit(1);
        }
    }

    private static class FakeScheduler implements VideoBackgroundSession.DelayedTaskScheduler {
        private final List<Runnable> pending = new ArrayList<>();

        boolean hasPendingTask() {
            return !pending.isEmpty();
        }

        @Override
        public void postDelayed(Runnable task, long delayMillis) {
            pending.remove(task);
            pending.add(task);
        }

        @Override
        public void removeCallbacks(Runnable task) {
            pending.remove(task);
        }

        void runNext() {
            if (pending.isEmpty()) throw new IllegalStateException("没有待执行任务");
            Runnable task = pending.remove(0);
            task.run();
        }
    }

    private static void testLostPlaybackSession() {
        FakeScheduler scheduler = new FakeScheduler();
        VideoBackgroundSession session = new VideoBackgroundSession((m, t) -> {}, new MutableTimeProvider(), scheduler);
        session.bind(() -> new VideoBackgroundSession.ProbeResult(false, 0, false, false), () -> {}, () -> {});
        session.markBackgroundActive(true);
        session.arm(VideoBackgroundSession.MODE_DURATION, 10, 17);
        scheduler.runNext();
        check("播放器转交空档保留定时器", session.isArmed());
        check("播放器转交空档保留会话", session.isBackgroundActive());
        check("播放器转交空档继续探测后续视频", scheduler.hasPendingTask());
    }

    private static class MutableTimeProvider implements VideoBackgroundSession.TimeProvider {
        long time = 0L;
        @Override
        public long elapsedRealtime() {
            return time;
        }
    }

    private static void testSessionDurationClamped() {
        System.out.println("\n[VideoBackgroundSession] 时长模式参数裁剪与心跳调度");
        MutableTimeProvider time = new MutableTimeProvider();
        time.time = 10000L;
        FakeScheduler scheduler = new FakeScheduler();
        VideoBackgroundSession session = new VideoBackgroundSession((msg, t) -> {}, time, scheduler);

        session.arm(VideoBackgroundSession.MODE_DURATION, 0, 17);
        check("小于1分钟裁剪到1", session.getMode() == VideoBackgroundSession.MODE_DURATION);
        check("上一次选择分钟数为1", session.getLastPickedMinutes() == 1);
        check("剩余分钟数为1", session.getRemainingMinutes() == 1);
        check("剩余秒数为60", session.getRemainingSeconds() == 60);

        session.arm(VideoBackgroundSession.MODE_DURATION, 2000, 17);
        check("超过最大时长裁剪到1439", session.getLastPickedMinutes() == 1439);
        check("剩余分钟数为1439", session.getRemainingMinutes() == 1439);
        check("调度器具有待执行任务", scheduler.hasPendingTask());
    }

    private static void testSessionDisarmStopsTicking() {
        System.out.println("\n[VideoBackgroundSession] 停用定时器与 UI 刷新");
        MutableTimeProvider time = new MutableTimeProvider();
        FakeScheduler scheduler = new FakeScheduler();
        VideoBackgroundSession session = new VideoBackgroundSession((msg, t) -> {}, time, scheduler);

        int[] tickCount = new int[]{0};
        session.bind(() -> VideoBackgroundSession.ProbeResult.UNKNOWN, () -> {}, () -> tickCount[0]++);

        session.arm(VideoBackgroundSession.MODE_DURATION, 1, 17);
        session.disarm();

        check("disarm 后 armed 为 false", !session.isArmed());
        check("disarm 后调度器无待执行任务", !scheduler.hasPendingTask());
        check("arm 与 disarm 分别触发了一次 tick 刷新", tickCount[0] == 2);
    }

    private static void testSessionDurationExpiry() {
        System.out.println("\n[VideoBackgroundSession] 定时到期回调与状态");
        MutableTimeProvider time = new MutableTimeProvider();
        FakeScheduler scheduler = new FakeScheduler();
        VideoBackgroundSession session = new VideoBackgroundSession((msg, t) -> {}, time, scheduler);

        int[] expireCount = new int[]{0};
        boolean[] callbackSawDisarmed = new boolean[]{false};
        session.bind(
                () -> new VideoBackgroundSession.ProbeResult(true, 17, false),
                () -> {
                    expireCount[0]++;
                    callbackSawDisarmed[0] = !session.isArmed();
                },
                () -> {}
        );

        session.arm(VideoBackgroundSession.MODE_DURATION, 1, 17);
        time.time += 60000L;
        scheduler.runNext();

        check("到期回调触发一次", expireCount[0] == 1);
        check("回调执行时会话已被 disarm", callbackSawDisarmed[0]);
        check("会话保持未激活", !session.isArmed());
        check("心跳未被继续调度", !scheduler.hasPendingTask());
        check("定时到期仍保留用户播放会话", session.isActive());
        session.end();
        check("只有手动结束才移除播放会话", !session.isActive());
    }

    private static void testSessionAfterCurrentMode() {
        System.out.println("\n[VideoBackgroundSession] 当前视频结束后模式");
        MutableTimeProvider time = new MutableTimeProvider();
        FakeScheduler scheduler = new FakeScheduler();
        VideoBackgroundSession session = new VideoBackgroundSession((msg, t) -> {}, time, scheduler);

        int[] expireCount = new int[]{0};
        session.bind(
                () -> new VideoBackgroundSession.ProbeResult(true, 99, false),
                () -> expireCount[0]++,
                () -> {}
        );

        session.arm(VideoBackgroundSession.MODE_AFTER_CURRENT, 0, 17);
        scheduler.runNext();

        check("当前视频切换后定时到期", expireCount[0] == 1);
        check("会话已 disarm", !session.isArmed());
    }

    private static void testMarkBackgroundActiveDoesNotDisarm() {
        System.out.println("\n[VideoBackgroundSession] markBackgroundActive 状态解耦");
        MutableTimeProvider time = new MutableTimeProvider();
        FakeScheduler scheduler = new FakeScheduler();
        VideoBackgroundSession session = new VideoBackgroundSession((msg, t) -> {}, time, scheduler);

        session.arm(VideoBackgroundSession.MODE_DURATION, 10, 17);
        check("初始状态已激活", session.isArmed());

        session.markBackgroundActive(true);
        check("进入后台状态", session.isBackgroundActive());
        check("定时器依然保持激活", session.isArmed());

        session.markBackgroundActive(false);
        check("退出后台状态", !session.isBackgroundActive());
        check("退出后台后定时器依然保持激活", session.isArmed());
        check("剩余分钟数未被重置", session.getRemainingMinutes() == 10);
    }

    private static void testPauseFreezesCountdown() {
        System.out.println("\n[VideoBackgroundSession] 暂停冻结倒计时与播放恢复倒计时");
        MutableTimeProvider time = new MutableTimeProvider();
        FakeScheduler scheduler = new FakeScheduler();
        VideoBackgroundSession session = new VideoBackgroundSession((msg, t) -> {}, time, scheduler);

        boolean[] playing = new boolean[]{false};
        session.bind(
                () -> new VideoBackgroundSession.ProbeResult(true, 17, false, playing[0]),
                () -> {},
                () -> {}
        );

        session.arm(VideoBackgroundSession.MODE_DURATION, 10, 17);
        check("初始 10 分钟", session.getRemainingSeconds() == 600);

        // 模拟暂停 10 秒
        time.time += 10000L;
        scheduler.runNext();
        check("暂停期间倒计时冻结（仍为 600 秒）", session.getRemainingSeconds() == 600);

        // 模拟恢复播放 5 秒
        playing[0] = true;
        time.time += 5000L;
        scheduler.runNext();
        check("恢复播放后扣除时间（595 秒）", session.getRemainingSeconds() == 595);
    }

    private static void testFormattedRemainingTime() {
        System.out.println("\n[VideoBackgroundSession] 紧凑倒计时格式化（无单位纯数字）");
        MutableTimeProvider time = new MutableTimeProvider();
        FakeScheduler scheduler = new FakeScheduler();
        VideoBackgroundSession session = new VideoBackgroundSession((msg, t) -> {}, time, scheduler);

        session.arm(VideoBackgroundSession.MODE_DURATION, 10, 17);
        check("10分钟格式化为 10:00", "10:00".equals(session.getFormattedRemainingTime()));

        session.arm(VideoBackgroundSession.MODE_DURATION, 65, 17);
        check("65分钟格式化为 1:05:00", "1:05:00".equals(session.getFormattedRemainingTime()));
    }

    private static void testProbeResultEnded() {
        System.out.println("\n[ProbeResult] 播完判定边界条件");
        check("同一视频且未结束 -> false",
                !new VideoBackgroundSession.ProbeResult(true, 17, false).endedCurrentItem(17));
        check("同一视频且已到达结尾 -> true",
                new VideoBackgroundSession.ProbeResult(true, 17, true).endedCurrentItem(17));
        check("已切换到新视频 -> true",
                new VideoBackgroundSession.ProbeResult(true, 99, false).endedCurrentItem(17));
        check("无锚定且未到达结尾 -> false",
                !new VideoBackgroundSession.ProbeResult(true, 0, false).endedCurrentItem(17));
    }

    private static void testIdentityMask() {
        System.out.println("\n[MessageIdentityMask] 作用域限定伪装与线程隔离");
        Object videoObj = new Object();
        Object audioObj = new Object();

        check("默认未伪装时返回 null", MessageIdentityMask.resolve(videoObj, MessageIdentityMask.Kind.MUSIC) == null);

        MessageIdentityMask.Spec musicSpec = MessageIdentityMask.Spec.asMusic(videoObj);
        MessageIdentityMask.around(musicSpec, () -> {
            check("包在 asMusic 里时 isMusic 返回 true",
                    Boolean.TRUE.equals(MessageIdentityMask.resolve(videoObj, MessageIdentityMask.Kind.MUSIC)));
            check("未被伪装的对象返回 null",
                    MessageIdentityMask.resolve(audioObj, MessageIdentityMask.Kind.MUSIC) == null);
            check("isVideo 未被指定，返回 null",
                    MessageIdentityMask.resolve(videoObj, MessageIdentityMask.Kind.VIDEO) == null);
        });

        check("退出 around 后伪装恢复为 null", MessageIdentityMask.resolve(videoObj, MessageIdentityMask.Kind.MUSIC) == null);

        MessageIdentityMask.Spec hideSpec = MessageIdentityMask.Spec.asMusicHidingVideo(videoObj);
        MessageIdentityMask.around(hideSpec, () -> {
            check("asMusicHidingVideo: isMusic 返回 true",
                    Boolean.TRUE.equals(MessageIdentityMask.resolve(videoObj, MessageIdentityMask.Kind.MUSIC)));
            check("asMusicHidingVideo: isVideo 返回 false",
                    Boolean.FALSE.equals(MessageIdentityMask.resolve(videoObj, MessageIdentityMask.Kind.VIDEO)));
        });
    }
}
