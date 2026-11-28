package com.shitianyaa.nagramx.videotimer;

import tool.ModuleBuild;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

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
        testDurationClockAndThemeContrast();
        testPlaylistCalculationsAndCache();
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

    private static void testDurationClockAndThemeContrast() {
        System.out.println("\n[UI 工具] 统一时长格式化与主题按钮对比度解析");
        check("0 毫秒格式化为 0:00", "0:00".equals(HostStrings.formatDurationClock(0L)));
        check("59 秒格式化为 0:59", "0:59".equals(HostStrings.formatDurationClock(59_000L)));
        check("1 小时整格式化为 1:00:00", "1:00:00".equals(HostStrings.formatDurationClock(3_600_000L)));
        check("超过 1 小时格式化为 1:12:05", "1:12:05".equals(HostStrings.formatDurationClock(4_325_000L)));
        check("ListenVideoPage.time 复用共享格式", "1:12:05".equals(ListenVideoPage.time(4_325_000L)));

        int darkSheetBg = 0xFF1F1F1F;
        int brokenButtonBg = 0xFF242424;
        int greenAccent = 0xFF4BB368;
        int brokenBlueText = 0xFF4C75BC;
        int resolvedFill = HostTheme.resolvePrimaryFill(darkSheetBg, brokenButtonBg, greenAccent, 0xFF1A9CFF);
        check("低对比暗灰按钮背景回退到主题强调色", resolvedFill == greenAccent);
        int resolvedText = HostTheme.resolveButtonText(resolvedFill, brokenBlueText);
        check("低对比蓝字在绿色主按钮上回退为高对比白色", resolvedText == HostTheme.WHITE);

        int pastelFill = 0xFFA8DAB5;
        int monetDarkInk = 0xFF1A1C19;
        check("浅色粉彩主按钮保留高对比深色文字",
                HostTheme.resolveButtonText(pastelFill, monetDarkInk) == monetDarkInk);
        check("浅色粉彩按钮遇到白色首选文字时自动切深色文字",
                HostTheme.resolveButtonText(pastelFill, HostTheme.WHITE) == HostTheme.DARK_INK);
        check("标准深海蓝主按钮保留白色文字",
                HostTheme.resolveButtonText(0xFF1A9CFF, HostTheme.WHITE) == HostTheme.WHITE);
        check("主题强调色识别拒绝透明色并回退有效高亮色",
                HostTheme.resolveAccent(darkSheetBg, 0x004BB368, greenAccent, 0xFF1A9CFF, 0xFF1A9CFF) == greenAccent);
    }

    private static void testPlaylistCalculationsAndCache() {
        System.out.println("\n[播放列表] 同步窗口缓冲、当前项居中定位与本地状态缓存");
        check("顶部边界上缓冲不低于 0", VideoPlaylistSheet.firstVisibleIndex(0, 88) == 0);
        check("向下滚动后保留上方 2 行缓冲", VideoPlaylistSheet.firstVisibleIndex(88 * 5 + 20, 88) == 3);
        check("向下滚动后保留下方 2 行缓冲并夹紧总数",
                VideoPlaylistSheet.endVisibleIndex(88 * 2, 300, 88, 20) == 8);
        check("列表末尾下缓冲不超出队列总长度",
                VideoPlaylistSheet.endVisibleIndex(88 * 15, 440, 88, 20) == 20);

        check("列表未超出视口时不滚动居中", VideoPlaylistSheet.centeredScrollTop(3, 88, 352, 440) == 0);
        check("第 0 项居中位置夹紧到顶部 0", VideoPlaylistSheet.centeredScrollTop(0, 88, 2000, 440) == 0);
        check("中间项严格居中于视口", VideoPlaylistSheet.centeredScrollTop(10, 88, 2000, 440) == 10 * 88 - 176);
        check("尾部项夹紧到最大可滚偏移", VideoPlaylistSheet.centeredScrollTop(22, 88, 2024, 440) == 2024 - 440);

        java.util.Map<Integer, Boolean> cache = new java.util.HashMap<>();
        cache.put(101, false);
        check("宿主 mediaExists 为 true 时优先判定已缓存",
                Boolean.TRUE.equals(VideoPlaylistSheet.knownLocalStatus(cache, 101, true, false)));
        check("未标明已存在时命中会话缓存结果 false",
                Boolean.FALSE.equals(VideoPlaylistSheet.knownLocalStatus(cache, 101, false, false)));
        check("未入缓存的条目返回 null 交由后台校验",
                VideoPlaylistSheet.knownLocalStatus(cache, 202, false, false) == null);
        check("有效 messageId 为 0 时不误读缓存",
                VideoPlaylistSheet.knownLocalStatus(cache, 0, false, false) == null);

        check("听视频弹窗保留顶部暗色留白高度",
                ListenVideoPage.sheetMaxHeight(2400, 288, 720) == 2112);
        check("可用高度偏小时不超出现有视口",
                ListenVideoPage.sheetMaxHeight(600, 288, 720) == 600);
        check("听视频竖屏下保底高度覆盖定时弹窗层级且不超上限",
                ListenVideoPage.sheetPortraitMinHeight(2592, 2400, 1680) == Math.round(2592 * 0.84f));
        check("听视频矮屏竖屏保底高度夹紧在最大可用高度内",
                ListenVideoPage.sheetPortraitMinHeight(1800, 1512, 1680) == 1512);
        check("高屏竖屏定时滚轮展开7项且可滑高度加高到252dp",
                VideoSleepTimerSheet.pickerItemCount(904, false) == 7
                        && VideoSleepTimerSheet.pickerHeightDp(904, false) == 252);
        check("紧凑竖屏定时滚轮保持5项和180dp避免溢出",
                VideoSleepTimerSheet.pickerItemCount(640, false) == 5
                        && VideoSleepTimerSheet.pickerHeightDp(640, false) == 180);
        check("矮横屏定时滚轮收紧为3项和126dp",
                VideoSleepTimerSheet.pickerItemCount(406, true) == 3
                        && VideoSleepTimerSheet.pickerHeightDp(406, true) == 126);

        check("听视频内容超出预算时滚动区夹紧为可用预算",
                ListenVideoPage.resolveScrollHeight(1600, 1200, 480, 2400, 2208, 1680, false) == 1200);
        check("听视频竖屏内容较短时滚动区撑高至84%保底页高",
                ListenVideoPage.resolveScrollHeight(700, 1728, 480, 2400, 2208, 1680, false)
                        == Math.round(2400 * 0.84f) - 480);
        check("听视频横屏短内容保持自然高度",
                ListenVideoPage.resolveScrollHeight(500, 900, 400, 1080, 1008, 1680, true) == 500);
        check("听视频封面高度随横竖屏规则计算",
                ListenVideoPage.computeCoverHeight(false, 1080, 2400, 540, 330, 1020) == 720
                        && ListenVideoPage.computeCoverHeight(true, 2400, 1080, 540, 330, 1020) == 360);

        check("构建版本排序按数值降序而非字典序",
                ModuleBuild.compareVersionDesc("37.0.0", "9.0.0") < 0
                        && ModuleBuild.compareVersionDesc("36.1.0", "37.0.0") > 0
                        && ModuleBuild.compareVersionDesc("37.0", "34") < 0);
        check("Windows 下转换 MSYS 盘符路径且 Linux 保持原样",
                "D:\\AndroidSDK".equals(ModuleBuild.normalizePathForOs("/d/AndroidSDK", true))
                        && "/d/AndroidSDK".equals(ModuleBuild.normalizePathForOs("/d/AndroidSDK", false)));
        Set<String> pkgs = ModuleBuild.extractHostPackages(
                "static final String PACKAGE_NAGRAMX = \"nu.gpu.nagram\";\n"
                        + "static final String PACKAGE_NIAGRAMX = \"top.nkbe.niagram\";\n");
        check("仅提取 HostProfile 宿主包名常量",
                pkgs.size() == 2 && pkgs.contains("nu.gpu.nagram") && pkgs.contains("top.nkbe.niagram"));
        List<ModuleBuild.DepSpec> deps = ModuleBuild.parseDepsManifest(Arrays.asList(
                "# comment",
                "",
                "dexkit-2.3.0.aar|abcd|org/luckypray/dexkit/2.3.0/dexkit-2.3.0.aar|aar|dexkit-2.3.0.jar"));
        check("deps.manifest 跳过注释并解析5字段",
                deps.size() == 1 && "aar".equals(deps.get(0).kind) && "dexkit-2.3.0.jar".equals(deps.get(0).outputName));
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
