package com.shitianyaa.nagramx.videotimer;

import android.media.session.PlaybackState;

import com.shitianyaa.nagramx.videotimer.core.Xp;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.HookBuilder;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.*;
import java.util.*;

/** Executes production Hookers against the original host's cleanup/playlist contract.
 * This verifies module decisions, not ART interception or Android UI rendering. */
final class HookRegressionTest {
    static HookRegressionTest current;
    private final Map<Executable, Hooker> registered = new HashMap<>();
    private final Set<Executable> proceeding = new HashSet<>();
    private final NagramXHooks hooks;
    private final VideoBackgroundSession session;
    private final HostProfile host;
    private final MediaController controller = new MediaController();

    private HookRegressionTest() throws Exception {
        current = this;
        MediaController.instance = controller;
        PhotoViewer.instance = new PhotoViewer();
        HostBridge bridge = new HostBridge(getClass().getClassLoader(), (m, t) -> {});
        Constructor<?> ctor = HostProfile.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        host = (HostProfile) ctor.newInstance(bridge, 1260, false, "12.9.2-4335a2e",
                PhotoViewer.class, MediaController.class, MessageObject.class, VideoPlayer.class,
                null, null, FileLoader.class, new PhotoViewerMembers(bridge, PhotoViewer.class, null),
                new MediaControllerMembers(bridge, MediaController.class),
                new VideoPlayerMembers(bridge, VideoPlayer.class, null),
                new MessageObjectMembers(bridge, MessageObject.class),
                new MusicServiceMembers(bridge, null), new PlayerUiMembers(bridge, ContextView.class, null, null));
        Field chatClassField = PlayerUiMembers.class.getDeclaredField("chatActivityClass");
        chatClassField.setAccessible(true);
        chatClassField.set(host.playerUi, ChatActivity.class);
        Field openMessageField = PlayerUiMembers.class.getDeclaredField("openPhotoViewerForMessage");
        openMessageField.setAccessible(true);
        openMessageField.set(host.playerUi, ChatActivity.class.getDeclaredMethod("openPhotoViewerForMessage", Object.class, MessageObject.class));
        Field scrollField = PlayerUiMembers.class.getDeclaredField("updateMessagesVisiblePart");
        scrollField.setAccessible(true);
        scrollField.set(host.playerUi, ChatActivity.class.getDeclaredMethod("updateMessagesVisiblePart", boolean.class));
        Field textureField = PlayerUiMembers.class.getDeclaredField("updateTextureViewPosition");
        textureField.setAccessible(true);
        textureField.set(host.playerUi, ChatActivity.class.getDeclaredMethod("updateTextureViewPosition", boolean.class, boolean.class));
        XposedModule module = new XposedModule() {};
        module.attachFramework((XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (p, m, a) -> {
                    if (m.getName().equals("hook")) {
                        Executable target = (Executable) a[0];
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{HookBuilder.class},
                                (builder, op, args) -> {
                                    if (op.getName().equals("intercept")) {
                                        registered.put(target, (Hooker) args[0]);
                                        return null;
                                    }
                                    return builder;
                                });
                    }
                    return null;
                }), () -> {});
        Xp.bind(module);
        hooks = new NagramXHooks(module, host);
        Field field = NagramXHooks.class.getDeclaredField("session");
        field.setAccessible(true);
        session = (VideoBackgroundSession) field.get(hooks);
        for (String name : new String[]{"hookPhotoViewerClose", "hookPhotoViewerState", "hookPlaylistPreservation", "hookSessionTeardown", "hookDownloadCancel", "hookChatActivityGuards"}) {
            Method installer = NagramXHooks.class.getDeclaredMethod(name);
            installer.setAccessible(true);
            installer.invoke(hooks);
        }
    }

    Object call(Object instance, String name, Class<?>[] types, Object... args) throws Throwable {
        Method method = instance.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        Hooker hook = registered.get(method);
        if (hook == null) return proceed(method, instance, args);
        Chain chain = (Chain) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Chain.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "getExecutable": return method;
                        case "getThisObject": return instance;
                        case "getArgs": return Arrays.asList(args);
                        case "getArg": return args[(Integer) a[0]];
                        case "proceed": return proceed(method, instance, a == null ? args : (Object[]) a[0]);
                        default: throw new UnsupportedOperationException(m.getName());
                    }
                });
        return hook.intercept(chain);
    }

    private Object proceed(Method method, Object instance, Object[] args) throws Throwable {
        proceeding.add(method);
        try { return method.invoke(instance, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
        finally { proceeding.remove(method); }
    }

    boolean isProceeding(String name, int count) {
        for (Executable method : proceeding) {
            if (method.getName().equals(name) && method.getParameterCount() == count) return true;
        }
        return false;
    }

    private void start(MessageObject first, MessageObject second) {
        controller.videoPlayer = new VideoPlayer();
        controller.playingMessageObject = first;
        controller.playlist.add(first);
        controller.playlist.add(second);
        controller.playlistMap.put(first.getId(), first);
        controller.playlistMap.put(second.getId(), second);
        session.markBackgroundActive(true);
        session.arm(VideoBackgroundSession.MODE_DURATION, 10, first.getId());
    }

    private void verifyListenQueue() throws Throwable {
        MessageObject currentVideo = new MessageObject(15);
        MessageObject olderVideo = new MessageObject(8);
        MessageObject newerVideo = new MessageObject(22);
        MessageObject anotherChat = new MessageObject(24);
        anotherChat.dialogId = 12;
        MessageObject anotherAccount = new MessageObject(26);
        anotherAccount.currentAccount = 1;
        MessageObject hidden = new MessageObject(18);
        hidden.hiddenSensitive = true;
        start(currentVideo, olderVideo);

        Field listenField = NagramXHooks.class.getDeclaredField("listen");
        listenField.setAccessible(true);
        ListenVideoAgent listen = (ListenVideoAgent) listenField.get(hooks);
        Field origin = ListenVideoAgent.class.getDeclaredField("origin");
        origin.setAccessible(true);
        origin.set(listen, currentVideo);
        Method merge = ListenVideoAgent.class.getDeclaredMethod("merge", List.class);
        merge.setAccessible(true);
        merge.invoke(listen, Arrays.asList(anotherChat, anotherAccount, hidden, newerVideo,
                olderVideo, currentVideo, new MessageObject(8)));

        TestMain.check("听视频队列按消息排序且只收录当前账号和会话的可播放视频",
                listen.queue().equals(Arrays.asList(newerVideo, currentVideo, olderVideo)));
        TestMain.check("拒绝切换到其他账号、聊天或隐藏视频",
                !listen.select(anotherAccount) && !listen.select(anotherChat) && !listen.select(hidden));
        listen.skip(true);
        TestMain.check("听视频下一条切换到更新的视频", controller.playingMessageObject == newerVideo);
        listen.skip(true);
        TestMain.check("同一会话最后一条不跳到其他视频", controller.playingMessageObject == newerVideo);
        listen.skip(false);
        TestMain.check("听视频上一条回到原视频", controller.playingMessageObject == currentVideo);
        MessageObject failedVideo = new MessageObject(17);
        failedVideo.startWithoutPlayer = true;
        merge.invoke(listen, Collections.singletonList(failedVideo));
        controller.videoPlayer.position = 18_000;
        controller.isPaused = true;
        TestMain.check("宿主接受换条但没有留下播放会话时反馈失败", !listen.skip(true));
        TestMain.check("换条失败恢复原视频、进度和暂停",
                listen.current() == currentVideo && controller.videoPlayer.position == 18_000 && controller.isPaused);
        VideoPlayer oldTrack = controller.videoPlayer;
        controller.cleanupOnTrackNotification = true;
        TestMain.check("再次点击下一条可以越过本次启动失败的条目",
                listen.skip(true) && listen.current() == newerVideo);
        TestMain.check("换条仍释放旧播放器，聊天页同步通知不释放新播放器",
                oldTrack.released && controller.videoPlayer != null && !controller.videoPlayer.released);
        controller.cleanupOnTrackNotification = false;
        listen.skip(false);
        TestMain.check("听视频页显示已设置的定时剩余时间", "10:00".equals(listen.timerStatus(null)));
        session.disarm();
        TestMain.check("取消定时后听视频页清空倒计时", listen.timerStatus(null).isEmpty());

        Method miniPlayerInstaller = NagramXHooks.class.getDeclaredMethod("hookMiniPlayer");
        miniPlayerInstaller.setAccessible(true);
        miniPlayerInstaller.invoke(hooks);
        ContextView mini = new ContextView(false);
        call(mini, "lambda$createPlaybackSpeedButton$0", new Class<?>[]{float.class}, 1.5f);
        TestMain.check("顶部迷你栏调速同步到详情与后台播放器",
                listen.speed() == 1.5f && controller.videoPlayer.speed == 1.5f);
        TestMain.check("后台视频调速保留音乐倍速和宿主栏样式", controller.musicSpeed == 1f && mini.isMusic);
        listen.setSpeed(2f);
        call(mini, "updatePlaybackButton", new Class<?>[]{boolean.class}, false);
        TestMain.check("详情调速后顶部迷你栏同步", mini.shownSpeed == 2f);
        listen.setSpeed(1f);
        currentVideo.music = true;
        call(mini, "lambda$createPlaybackSpeedButton$0", new Class<?>[]{float.class}, 2f);
        TestMain.check("普通音乐使用自己的倍速设置", controller.musicSpeed == 2f && listen.speed() == 1f);
        currentVideo.music = false;
        List<Boolean> pageResults = new ArrayList<>();
        listen.loadMore(pageResults::add);
        TestMain.check("共享媒体接口不可用时反馈加载失败，允许列表恢复重试",
                pageResults.equals(Collections.singletonList(false)) && !listen.isLoadingMedia());
    }

    private void verifyPlatformMediaSkip() throws Throwable {
        MessageObject first = new MessageObject(15);
        MessageObject older = new MessageObject(8);
        MessageObject newer = new MessageObject(22);
        start(first, older);
        Field listenField = NagramXHooks.class.getDeclaredField("listen");
        listenField.setAccessible(true);
        ListenVideoAgent listen = (ListenVideoAgent) listenField.get(hooks);
        Field origin = ListenVideoAgent.class.getDeclaredField("origin");
        origin.setAccessible(true);
        origin.set(listen, first);
        Method merge = ListenVideoAgent.class.getDeclaredMethod("merge", List.class);
        merge.setAccessible(true);
        merge.invoke(listen, Arrays.asList(older, first, newer));
        Method installer = NagramXHooks.class.getDeclaredMethod("hookPlatformMediaSession", Class.class);
        installer.setAccessible(true);
        installer.invoke(hooks, PlatformMediaCallback.class);
        PlatformMediaCallback shared = new PlatformMediaCallback();
        PlatformMediaCallback service = new PlatformMediaCallback();
        ListenVideoMediaActions actions = new ListenVideoMediaActions(listen);
        long play = PlaybackState.ACTION_PLAY;
        long previous = PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        long next = PlaybackState.ACTION_SKIP_TO_NEXT;
        TestMain.check("系统媒体状态补齐两个切换方向并保留播放操作",
                actions.queueActions(play) == (play | previous | next));
        TestMain.check("补齐操作只适用于 Niagram 两个播放会话",
                ListenVideoMediaActions.isPlaybackSession("TelegramMediaSession")
                        && ListenVideoMediaActions.isPlaybackSession("telegramAudioPlayer")
                        && !ListenVideoMediaActions.isPlaybackSession("unrelated"));
        call(shared, "onSkipToNext", new Class<?>[]{});
        TestMain.check("系统共享会话下一条使用听视频队列", controller.playingMessageObject == newer);
        TestMain.check("列表末端的系统媒体状态仅提供上一条",
                actions.queueActions(play | next) == (play | previous));
        call(service, "onSkipToPrevious", new Class<?>[]{});
        TestMain.check("系统服务会话上一条使用同一队列", controller.playingMessageObject == first);
        TestMain.check("系统切换不重复执行宿主音乐分支", shared.next == 0 && service.previous == 0);
        TestMain.check("系统切换保留当前定时", session.isArmed() && "10:00".equals(listen.timerStatus(null)));
        call(service, "onSkipToPrevious", new Class<?>[]{});
        call(service, "onSkipToPrevious", new Class<?>[]{});
        TestMain.check("系统上一条在队列边界停止且不交给音乐分支",
                controller.playingMessageObject == older && service.previous == 0);
        TestMain.check("列表开头的系统媒体状态仅提供下一条",
                actions.queueActions(play | previous) == (play | next));
        older.music = true;
        call(shared, "onSkipToNext", new Class<?>[]{});
        TestMain.check("普通音乐的系统切换透传给宿主", shared.next == 1 && controller.playingMessageObject == older);
        TestMain.check("普通音乐的系统媒体状态保留宿主操作",
                actions.queueActions(play | previous) == (play | previous));
        older.music = false;
        session.end();
        call(service, "onSkipToPrevious", new Class<?>[]{});
        TestMain.check("听视频会话外系统事件透传", service.previous == 1);
        NiagramDexClasses.Spec spec = NiagramDexClasses.Spec.forMethods("callback", "java.lang.Object",
                "onSkipToNext", "onSkipToPrevious");
        TestMain.check("系统回调结构校验接受无参 void 切换方法", spec.accepts(PlatformMediaCallback.class));
        TestMain.check("系统回调结构校验拒绝错误返回类型", !spec.accepts(InvalidPlatformMediaCallback.class));
        Method leave = NagramXHooks.class.getDeclaredMethod("leaveGuard", ThreadLocal.class);
        leave.setAccessible(true);
        for (String name : new String[]{"changingTrack", "suppressCleanup"}) {
            Field field = NagramXHooks.class.getDeclaredField(name);
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ThreadLocal<Integer> guard = (ThreadLocal<Integer>) field.get(hooks);
            try {
                guard.set(2);
                leave.invoke(null, guard);
                TestMain.check("嵌套退出保留外层保护：" + name, Integer.valueOf(1).equals(guard.get()));
                leave.invoke(null, guard);
                TestMain.check("最外层退出移除保护状态：" + name, guard.get() == null);
            } finally {
                guard.remove();
            }
        }
    }

    static void run() {
        try {
            new HookRegressionTest().verifyListenQueue();
            new HookRegressionTest().verifyPlatformMediaSkip();
            HookRegressionTest pausedUi = new HookRegressionTest();
            Method sharedInstaller = NagramXHooks.class.getDeclaredMethod("hookTelegramMediaSession", Class.class, Class.class);
            sharedInstaller.setAccessible(true);
            sharedInstaller.invoke(pausedUi.hooks, SharedMediaHolder.class, SharedMediaCallback.class);
            MessageObject mediaVideo = new MessageObject(10);
            pausedUi.start(mediaVideo, new MessageObject(11));
            SharedMediaCallback sharedCallback = new SharedMediaCallback();
            pausedUi.call(sharedCallback, "onSkipToNext", new Class<?>[]{});
            pausedUi.call(sharedCallback, "onSkipToPrevious", new Class<?>[]{});
            TestMain.check("第二套媒体会话允许视频上一条下一条", sharedCallback.next == 1 && sharedCallback.previous == 1);
            TestMain.check("第二套媒体会话控制保留定时", pausedUi.session.isArmed());
            TestMain.check("媒体身份作用域在回调后恢复", MessageIdentityMask.resolve(mediaVideo, MessageIdentityMask.Kind.MUSIC) == null);
            TestMain.check("第二套媒体会话发布视频切换能力", ((Number) pausedUi.call(new SharedMediaHolder(), "getAvailableActions", new Class<?>[]{})).longValue() == 48L);
            MessageObject channelVideo = new MessageObject(42);
            channelVideo.dialogId = -1000000000123L;
            channelVideo.currentAccount = 2;
            android.content.Intent jump = NotificationDresser.createJumpIntent(pausedUi.host, null, PhotoViewer.class, channelVideo);
            TestMain.check("通知定位带上视频账号", jump.getIntExtra("currentAccount", -1) == 2);
            TestMain.check("通知定位保留完整频道 ID 与消息 ID", jump.getLongExtra("chatId", 0) == 1000000000123L && jump.getIntExtra("message_id", 0) == 42);
            channelVideo.currentAccount = 1;
            android.content.Intent otherAccount = NotificationDresser.createJumpIntent(pausedUi.host, null, PhotoViewer.class, channelVideo);
            TestMain.check("不同账号通知不复用同一跳转身份", !jump.getAction().equals(otherAccount.getAction()));
            channelVideo.dialogId = 123;
            android.content.Intent userJump = NotificationDresser.createJumpIntent(pausedUi.host, null, PhotoViewer.class, channelVideo);
            TestMain.check("私聊通知使用宿主 userId 入口", userJump.getLongExtra("userId", 0) == 123 && userJump.getAction().startsWith("com.tmessages.openchat"));
            pausedUi.session.markBackgroundActive(true);
            PhotoViewer pausedViewer = PhotoViewer.instance;
            pausedViewer.currentMessageObject = new MessageObject(9);
            pausedViewer.videoPlayer = new VideoPlayer();
            pausedViewer.videoPlayer.playing = false;
            // checkProgress's queued file check runs after updatePlayerState.
            pausedViewer.photoProgressViews[0].backgroundState = -1;
            pausedUi.call(pausedViewer, "drawProgress", new Class<?>[]{Object.class, float.class, float.class, float.class, float.class}, null, 0f, 1f, 0f, 1f);
            TestMain.check("异步文件检查后暂停视频仍有播放按钮", pausedViewer.drawnState == 3);
            pausedViewer.videoPlayer.state = 2;
            pausedViewer.photoProgressViews[0].backgroundState = 2;
            pausedUi.call(pausedViewer, "drawProgress", new Class<?>[]{Object.class, float.class, float.class, float.class, float.class}, null, 0f, 1f, 0f, 1f);
            TestMain.check("缓冲中仍保留原生加载按钮", pausedViewer.drawnState == 2);
            pausedViewer.playerInjected = true;
            pausedViewer.firstFrameRendered = false;
            pausedUi.call(pausedViewer.videoPlayer, "onRenderedFirstFrame", new Class<?>[]{});
            TestMain.check("通知换轨的播放器回全屏后首帧解除黑屏遮罩", pausedViewer.firstFrameRendered);
            pausedViewer.firstFrameRendered = false;
            pausedUi.call(new VideoPlayer(), "onRenderedFirstFrame", new Class<?>[]{});
            TestMain.check("旧播放器首帧不改变当前视频画面状态", !pausedViewer.firstFrameRendered);
            pausedUi.call(pausedViewer.videoPlayer, "onRenderedFirstFrame", new Class<?>[]{Object.class, Object.class, long.class}, null, null, 0L);
            TestMain.check("分析事件首帧入口同样解除转交后的黑屏遮罩", pausedViewer.firstFrameRendered);
            HookRegressionTest bars = new HookRegressionTest();
            Field uiField = NagramXHooks.class.getDeclaredField("playerUi");
            uiField.setAccessible(true);
            PlayerUiAgent barAgent = (PlayerUiAgent) uiField.get(bars.hooks);
            ContextView playerBar = new ContextView(false), locationBar = new ContextView(true);
            barAgent.rememberContextView(playerBar);
            barAgent.rememberContextView(locationBar);
            Field viewsField = PlayerUiAgent.class.getDeclaredField("contextViews");
            viewsField.setAccessible(true);
            Map<?, ?> trackedBars = (Map<?, ?>) viewsField.get(barAgent);
            TestMain.check("后台刷新登记播放器栏", trackedBars.containsKey(playerBar));
            TestMain.check("后台刷新不把实时位置栏变为第二播放器", !trackedBars.containsKey(locationBar));
            PlayerUiMembers niagramBar = new PlayerUiMembers(bars.host.bridge, ContextView.class, null, null, true);
            bars.host.bridge.invoke(playerBar.subtitleTextView, niagramBar.miniPlayerSetText, "1:07:00", false);
            TestMain.check("Niagram 迷你栏使用混淆后的宿主文案方法", "1:07:00".contentEquals(playerBar.subtitleTextView.text)
                    && "f".equals(niagramBar.miniPlayerSetText.getName()));
            bars.host.bridge.invoke(playerBar.subtitleTextView, bars.host.playerUi.miniPlayerSetText, "名字", false);
            TestMain.check("原版迷你栏仍解析原始文案方法", "名字".contentEquals(playerBar.subtitleTextView.text));
            MessageObject captionedVideo = new MessageObject(9);
            captionedVideo.caption = "  视频标题\n视频描述  ";
            TestMain.check("迷你栏和通知共用视频说明首行", "视频标题".equals(NotificationDresser.captionFirstLine(bars.host, captionedVideo)));

            HookRegressionTest confirming = new HookRegressionTest();
            PhotoViewer currentViewer = PhotoViewer.instance;
            currentViewer.currentMessageObject = new MessageObject(7);
            VideoPlayer currentPlayer = new VideoPlayer();
            currentPlayer.playing = false;
            currentViewer.videoPlayer = currentPlayer;
            confirming.session.arm(VideoBackgroundSession.MODE_AFTER_CURRENT, 0, 7);
            confirming.call(currentViewer, "closePhoto", new Class<?>[]{boolean.class, boolean.class}, false, true);
            TestMain.check("确认定时后手动返回才转入后台", confirming.session.isBackgroundActive()
                    && confirming.controller.videoPlayer == currentPlayer);
            TestMain.check("手动返回时保留定时器和暂停状态", confirming.session.isArmed()
                    && confirming.controller.isPaused);

            HookRegressionTest preparedQueue = new HookRegressionTest();
            PhotoViewer preparedViewer = PhotoViewer.instance;
            MessageObject preparedCurrent = new MessageObject(70);
            MessageObject preparedNext = new MessageObject(71);
            preparedViewer.currentMessageObject = preparedCurrent;
            preparedViewer.videoPlayer = new VideoPlayer();
            preparedViewer.videoPlayer.playing = false;
            preparedViewer.videoPlayer.position = 25000;
            preparedViewer.imagesArr.add(preparedCurrent);
            Field preparedPhotoField = NagramXHooks.class.getDeclaredField("photo");
            preparedPhotoField.setAccessible(true);
            PhotoViewerAgent photoAgent = (PhotoViewerAgent) preparedPhotoField.get(preparedQueue.hooks);
            photoAgent.preservePlayerOnClose(preparedViewer, Arrays.asList(preparedCurrent, preparedNext));
            TestMain.check("进入听视频复用预先收集的完整列表", preparedQueue.controller.playlist.size() == 2
                    && preparedQueue.controller.playlist.contains(preparedNext));
            TestMain.check("复用列表的播放器交接保留暂停和进度", preparedQueue.controller.isPaused
                    && preparedCurrent.audioProgressMs == 25000 && preparedViewer.videoPlayer == null);

            HookRegressionTest ordinary = new HookRegressionTest();
            PhotoViewer viewer = new PhotoViewer();
            viewer.videoPlayer = new VideoPlayer();
            viewer.currentMessageObject = new MessageObject(1);
            ordinary.call(viewer, "closePhoto", new Class<?>[]{boolean.class, boolean.class}, false, true);
            TestMain.check("普通视频关闭不自动开启后台播放", ordinary.controller.videoPlayer == null);
            HookRegressionTest returningTrack = new HookRegressionTest();
            returningTrack.session.arm(VideoBackgroundSession.MODE_DURATION, 10, 13);
            PhotoViewer.instance.currentMessageObject = new MessageObject(13);
            PhotoViewer.instance.videoPlayer = new VideoPlayer();
            returningTrack.controller.cleanupOnVideoStart = true;
            returningTrack.call(PhotoViewer.instance, "closePhoto", new Class<?>[]{boolean.class, boolean.class}, false, true);
            TestMain.check("回后台广播不触发聊天页清理当前视频", returningTrack.controller.videoPlayer != null);
            TestMain.check("回后台广播保留常驻会话和定时", returningTrack.session.isActive() && returningTrack.session.isArmed());
            HookRegressionTest chronological = new HookRegressionTest();
            MessageObject olderVideo = new MessageObject(20), newerVideo = new MessageObject(21);
            chronological.start(olderVideo, newerVideo);
            chronological.call(chronological.controller, "playNextMessage", new Class<?>[]{});
            TestMain.check("通知下一条切换到更新消息", chronological.controller.playingMessageObject == newerVideo);
            chronological.call(chronological.controller, "playPreviousMessage", new Class<?>[]{});
            TestMain.check("通知上一条切换到更早消息", chronological.controller.playingMessageObject == olderVideo);
            TestMain.check("通知方向调整保留定时", chronological.session.isArmed());

            HookRegressionTest switching = new HookRegressionTest();
            MessageObject first = new MessageObject(1), second = new MessageObject(2);
            switching.start(first, second);
            VideoPlayer oldPlayer = switching.controller.videoPlayer;
            switching.call(switching.controller, "playMessage", new Class<?>[]{MessageObject.class, boolean.class}, second, false);
            TestMain.check("换视频时释放旧播放器", oldPlayer.released);
            TestMain.check("换视频后倒计时继续", switching.session.isArmed() && switching.session.isBackgroundActive());
            TestMain.check("换视频后恢复完整播放列表", switching.controller.playlist.size() == 2);
            TestMain.check("换视频后恢复播放列表索引表", switching.controller.playlistMap.get(2) == second);
            TestMain.check("换视频后当前索引指向新视频", switching.controller.currentPlaylistNum == 1);
            MessageObject otherChat = new MessageObject(3);
            otherChat.dialogId = 20L;
            switching.call(switching.controller, "playMessage", new Class<?>[]{MessageObject.class, boolean.class}, otherChat, false);
            TestMain.check("跨聊天视频继承定时器", switching.session.isArmed());
            TestMain.check("跨聊天视频不继承旧聊天列表", !switching.controller.playlist.contains(first));

            HookRegressionTest messageOpen = new HookRegressionTest();
            messageOpen.start(first, second);
            VideoPlayer previousMessagePlayer = messageOpen.controller.videoPlayer;
            messageOpen.call(new ChatActivity(), "openPhotoViewerForMessage", new Class<?>[]{Object.class, MessageObject.class}, new Object(), second);
            TestMain.check("从消息页打开另一视频释放旧播放器", previousMessagePlayer.released);
            TestMain.check("从消息页打开另一视频保留定时会话", messageOpen.session.isArmed() && messageOpen.session.isActive());
            messageOpen.call(PhotoViewer.instance, "closePhoto", new Class<?>[]{boolean.class, boolean.class}, false, true);
            TestMain.check("消息页另一视频返回后继承后台定时", messageOpen.controller.playingMessageObject == second
                    && messageOpen.session.isBackgroundActive() && messageOpen.session.isArmed());

            // Rebind the framework/controller identities after the independent fixture.
            switching = new HookRegressionTest();
            switching.start(first, second);

            VideoPlayer scrollingPlayer = switching.controller.videoPlayer;
            switching.call(new ChatActivity(), "updateMessagesVisiblePart", new Class<?>[]{boolean.class}, false);
            TestMain.check("滚出原视频时保留后台播放器", switching.controller.videoPlayer == scrollingPlayer && !scrollingPlayer.released);
            TestMain.check("滚动后继续后台定时", switching.session.isBackgroundActive() && switching.session.isArmed());

            switching.call(switching.controller, "cleanupPlayer", new Class<?>[]{boolean.class, boolean.class, boolean.class, boolean.class}, true, true, false, false);
            TestMain.check("用户停止播放时取消倒计时", !switching.session.isArmed());
            TestMain.check("用户停止播放时结束后台会话", !switching.session.isBackgroundActive());
            TestMain.check("用户停止播放时移除常驻会话", !switching.session.isActive());

            HookRegressionTest fullscreen = new HookRegressionTest();
            fullscreen.start(first, second);
            fullscreen.call(fullscreen.controller, "cleanupPlayer", new Class<?>[]{boolean.class, boolean.class, boolean.class, boolean.class}, true, true, false, true);
            TestMain.check("转回全屏时保留倒计时", fullscreen.session.isArmed());
            TestMain.check("转回全屏时退出后台状态", !fullscreen.session.isBackgroundActive());

            HookRegressionTest streaming = new HookRegressionTest();
            streaming.start(first, second);
            FileLoader loader = new FileLoader();
            streaming.call(loader, "cancelLoadFile", new Class<?>[]{OriginalTl.Document.class}, first.getDocument());
            TestMain.check("转后台后保留当前视频的流式下载", loader.cancelled == 0);
            loader.cancelled = 0;
            streaming.call(loader, "cancelLoadFile", new Class<?>[]{OriginalTl.Document.class}, second.getDocument());
            TestMain.check("其他文件的取消下载不受影响", loader.cancelled == 1);
            streaming.controller.videoPlayer = null;
            streaming.controller.downloadingCurrentMessage = true;
            Method probe = NagramXHooks.class.getDeclaredMethod("probePlayback");
            probe.setAccessible(true);
            VideoBackgroundSession.ProbeResult waiting = (VideoBackgroundSession.ProbeResult) probe.invoke(streaming.hooks);
            TestMain.check("等待视频下载时保留播放会话", waiting.sessionAlive);
            TestMain.check("等待视频下载时不消耗倒计时", !waiting.isPlaying);
            HookRegressionTest fullBuffer = new HookRegressionTest();
            PhotoViewer.instance.visible = true;
            PhotoViewer.instance.currentMessageObject = first;
            PhotoViewer.instance.videoPlayer = new VideoPlayer();
            PhotoViewer.instance.videoPlayer.state = 2;
            VideoBackgroundSession.ProbeResult buffering = (VideoBackgroundSession.ProbeResult) probe.invoke(fullBuffer.hooks);
            TestMain.check("全屏视频缓冲时不消耗倒计时", !buffering.isPlaying);
            PhotoViewer.instance.videoPlayer.state = 4;
            PhotoViewer.instance.videoPlayer.playing = false;
            VideoBackgroundSession.ProbeResult ended = (VideoBackgroundSession.ProbeResult) probe.invoke(fullBuffer.hooks);
            TestMain.check("全屏视频停止播放后仍能识别结尾", ended.reachedEnd);
            fullBuffer.controller.videoPlayer = new VideoPlayer();
            fullBuffer.controller.playingMessageObject = first;
            PhotoViewer.instance.currentMessageObject = second;
            PhotoViewer.instance.videoPlayer = null;
            VideoBackgroundSession.ProbeResult visibleLoading = (VideoBackgroundSession.ProbeResult) probe.invoke(fullBuffer.hooks);
            TestMain.check("全屏新视频加载时不沿用旧后台视频计时", visibleLoading.sessionAlive
                    && visibleLoading.messageId == second.getId() && !visibleLoading.isPlaying && !visibleLoading.reachedEnd);

            HookRegressionTest retainedReference = new HookRegressionTest();
            retainedReference.start(first, second);
            PhotoViewer.instance.currentMessageObject = first;
            PhotoViewer.instance.videoPlayer = retainedReference.controller.videoPlayer;
            retainedReference.controller.playingMessageObject = null;
            List<String> serviceAttempts = new ArrayList<>();
            Field mediaField = PhotoViewerAgent.class.getDeclaredField("media");
            mediaField.setAccessible(true);
            Field photoField = NagramXHooks.class.getDeclaredField("photo");
            photoField.setAccessible(true);
            mediaField.set(photoField.get(retainedReference.hooks), new MediaControllerAgent(retainedReference.host,
                    retainedReference.session, (m, t) -> serviceAttempts.add(m)));
            retainedReference.call(PhotoViewer.instance, "closePhoto", new Class<?>[]{boolean.class, boolean.class}, false, true);
            TestMain.check("全屏握手引用转回后台时恢复当前消息", retainedReference.controller.playingMessageObject == first);
            TestMain.check("复用播放器返回后台仍尝试启动通知服务", serviceAttempts.contains("无法启动视频通知：缺少 MusicPlayerService"));
            HookRegressionTest loadingClose = new HookRegressionTest();
            MessageObject downloadingVideo = new MessageObject(9);
            downloadingVideo.waitForDownload = true;
            PhotoViewer.instance.currentMessageObject = downloadingVideo;
            loadingClose.session.arm(VideoBackgroundSession.MODE_DURATION, 5, downloadingVideo.getId());
            loadingClose.call(PhotoViewer.instance, "closePhoto", new Class<?>[]{boolean.class, boolean.class}, false, true);
            TestMain.check("加载中视频返回后保留后台当前项", loadingClose.controller.playingMessageObject == downloadingVideo
                    && loadingClose.controller.downloadingCurrentMessage && loadingClose.session.isBackgroundActive());
            TestMain.check("加载中视频返回后保留定时器", loadingClose.session.isArmed());

            HookRegressionTest failedTransfer = new HookRegressionTest();
            MediaControllerAgent media = new MediaControllerAgent(failedTransfer.host, failedTransfer.session, (m, t) -> {});
            VideoPlayer rejected = new VideoPlayer();
            Object delegate = rejected.delegate, texture = rejected.textureView;
            boolean transferred = media.startBackgroundPlayback(rejected, first,
                    Collections.singletonList(first), 0L, (p, m) -> false);
            TestMain.check("转交失败向调用方报告失败", !transferred);
            TestMain.check("转交失败不遗留后台激活状态", !failedTransfer.session.isBackgroundActive());
            TestMain.check("转交失败恢复原视频回调", rejected.delegate == delegate);
            TestMain.check("转交失败恢复原画面输出", rejected.textureView == texture);

            HookRegressionTest reopen = new HookRegressionTest();
            reopen.start(first, second);
            // Allocate only the Activity identity; no Android methods are executed.
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field singleton = unsafeClass.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            ActivityHolder.instance = (android.app.Activity) unsafeClass.getMethod("allocateInstance", Class.class)
                    .invoke(singleton.get(null), android.app.Activity.class);
            Field activity = PlayerUiMembers.class.getDeclaredField("launchActivityInstanceField");
            activity.setAccessible(true);
            activity.set(reopen.host.playerUi, ActivityHolder.class.getDeclaredField("instance"));
            PlayerUiAgent ui = new PlayerUiAgent(reopen.host, reopen.session,
                    new HostStrings(reopen.host.bridge, (m, t) -> {}), (m, t) -> {});
            second.waitForDownload = true;
            TestMain.check("列表中的另一视频可申请打开全屏", ui.openInFullscreen(second));
            android.os.Handler.runReopen();
            TestMain.check("列表换视频回全屏不丢失倒计时", reopen.session.isArmed());
            TestMain.check("列表换视频回全屏接入所选视频", PhotoViewer.instance.currentMessageObject == second);
            TestMain.check("全屏接入有效播放器", PhotoViewer.instance.videoPlayer != null);
            TestMain.check("未下载视频由 PhotoViewer 加载而非先创建后台播放器", !reopen.controller.downloadingCurrentMessage);
            TestMain.check("全屏接管播放器后控制器不再持有同一播放器", reopen.controller.videoPlayer == null);
            reopen.call(PhotoViewer.instance, "closePhoto", new Class<?>[]{boolean.class, boolean.class}, false, true);
            TestMain.check("全屏往返后仍有后台播放器和定时器", reopen.controller.videoPlayer != null
                    && reopen.session.isBackgroundActive() && reopen.session.isArmed());
            reopen.controller.isPaused = true;
            reopen.controller.setPlaybackSpeed(false, 1.5f);
            int[] reopenCallbacks = {0};
            ui.openInFullscreen(second, () -> {
                if (PhotoViewer.instance.videoPlayer != null && PhotoViewer.instance.manuallyPaused) reopenCallbacks[0]++;
            });
            TestMain.check("返回画面在宿主接管后通知听视频面板退出", reopenCallbacks[0] == 1);
            TestMain.check("后台暂停时返回画面保留宿主手动暂停状态",
                    PhotoViewer.instance.manuallyPaused && !PhotoViewer.instance.videoPlayer.playing);
            TestMain.check("同一视频返回画面保留播放倍速", PhotoViewer.instance.videoPlayer.speed == 1.5f);
            reopen.session.disarm();
            reopen.call(PhotoViewer.instance, "closePhoto", new Class<?>[]{boolean.class, boolean.class}, false, true);
            TestMain.check("定时到期后再次全屏返回仍保留播放器", reopen.controller.videoPlayer != null
                    && reopen.session.isBackgroundActive() && reopen.session.isActive());
            second.waitForDownload = false;

            HookRegressionTest rejectedOpen = new HookRegressionTest();
            rejectedOpen.start(first, second);
            activity.set(rejectedOpen.host.playerUi, ActivityHolder.class.getDeclaredField("instance"));
            PhotoViewer.instance.rejectOpen = true;
            VideoPlayer held = rejectedOpen.controller.videoPlayer;
            PlayerUiAgent rejectedUi = new PlayerUiAgent(rejectedOpen.host, rejectedOpen.session,
                    new HostStrings(rejectedOpen.host.bridge, (m, t) -> {}), (m, t) -> {});
            rejectedUi.openInFullscreen(first, () -> reopenCallbacks[0]++);
            TestMain.check("返回画面失败时听视频面板保持打开", reopenCallbacks[0] == 1);
            TestMain.check("全屏打开失败恢复后台状态", rejectedOpen.session.isBackgroundActive());
            TestMain.check("全屏打开失败保留定时器", rejectedOpen.session.isArmed());
            TestMain.check("全屏打开失败恢复原播放器与消息", rejectedOpen.controller.videoPlayer == held && rejectedOpen.controller.playingMessageObject == first);
            TestMain.check("全屏打开失败恢复播放列表", rejectedOpen.controller.playlistMap.size() == 2);
        } catch (Throwable t) {
            throw new AssertionError("Hook regression harness failed", t);
        }
    }
}

// Host fixtures omit fork-only fields. playMessage reproduces the original host's
// cleanup(false) -> clearPlaylist -> create video player sequence (4335a2e).
class MediaController {
    static MediaController instance;
    VideoPlayer videoPlayer;
    Object audioPlayer;
    MessageObject playingMessageObject;
    boolean isPaused, forceLoopCurrentPlaylist, downloadingCurrentMessage;
    boolean cleanupOnVideoStart, cleanupOnTrackNotification;
    float musicSpeed = 1f, videoSpeed = 1f;
    int currentPlaylistNum;
    final List<Object> playlist = new ArrayList<>(), shuffledPlaylist = new ArrayList<>();
    final Map<Integer, Object> playlistMap = new HashMap<>();
    static MediaController getInstance() { return instance; }
    MessageObject getPlayingMessageObject() { return playingMessageObject; }
    float getPlaybackSpeed(boolean music) { return music ? musicSpeed : videoSpeed; }
    void setPlaybackSpeed(boolean music, float speed) {
        if (music) musicSpeed = speed;
        else videoSpeed = speed;
        if (videoPlayer != null) videoPlayer.speed = speed;
    }
    List<Object> getPlaylist() { return playlist; }
    void injectVideoPlayer(VideoPlayer player, MessageObject message) throws Throwable {
        videoPlayer = player; playingMessageObject = message;
        // Models a synchronous chat listener reacting to the native start broadcast.
        if (cleanupOnVideoStart && message.isVideo()) cleanupPlayer(true, true);
    }
    void cleanupPlayer(boolean notify, boolean stopService) throws Throwable {
        HookRegressionTest.current.call(this, "cleanupPlayer", new Class<?>[]{boolean.class, boolean.class, boolean.class, boolean.class}, notify, stopService, false, false);
    }
    void cleanupPlayer(boolean notify, boolean stopService, boolean ended, boolean fullscreen) throws Throwable {
        if (!HookRegressionTest.current.isProceeding("cleanupPlayer", 4)) {
            HookRegressionTest.current.call(this, "cleanupPlayer", new Class<?>[]{boolean.class, boolean.class, boolean.class, boolean.class}, notify, stopService, ended, fullscreen);
            return;
        }
        if (fullscreen) PhotoViewer.instance.injectingVideoPlayer = videoPlayer;
        if (videoPlayer != null && !fullscreen) videoPlayer.released = true;
        if (!fullscreen) videoPlayer = null;
        playingMessageObject = null;
    }
    boolean playMessage(MessageObject message, boolean silent) throws Throwable {
        if (!HookRegressionTest.current.isProceeding("playMessage", 2)) {
            return (Boolean) HookRegressionTest.current.call(this, "playMessage", new Class<?>[]{MessageObject.class, boolean.class}, message, silent);
        }
        cleanupPlayer(false, false);
        playlist.clear(); playlistMap.clear(); shuffledPlaylist.clear();
        if (message.startWithoutPlayer) return true;
        if (message.waitForDownload) {
            videoPlayer = null; playingMessageObject = message; downloadingCurrentMessage = true;
            return true;
        }
        videoPlayer = new VideoPlayer(); playingMessageObject = message;
        videoPlayer.position = message.audioProgressMs;
        isPaused = false;
        if (cleanupOnTrackNotification) {
            HookRegressionTest.current.call(new ChatActivity(), "updateTextureViewPosition",
                    new Class<?>[]{boolean.class, boolean.class}, true, false);
        }
        return true;
    }
    void pauseMessage(MessageObject message) {
        if (playingMessageObject == message && videoPlayer != null) {
            isPaused = true;
            videoPlayer.pause();
        }
    }
    void sortPlaylist() {}
    void playNextMessage() {}
    void playPreviousMessage() {}
}
class MessageObject {
    final int id;
    CharSequence caption;
    int currentAccount, audioProgressMs, audioProgressSec;
    float audioProgress;
    long dialogId = 10L;
    boolean waitForDownload, hiddenSensitive, music, startWithoutPlayer;
    final OriginalTl.Document document = new OriginalTl.Document();
    MessageObject(int id) { this.id = id; }
    int getId() { return id; }
    double getDuration() { return 120; }
    long getDialogId() { return dialogId; }
    OriginalTl.Document getDocument() { return document; }
    boolean isVideo() { Boolean masked = MessageIdentityMask.resolve(this, MessageIdentityMask.Kind.VIDEO); return masked == null ? !music : masked; }
    boolean isMusic() { return music; }
    boolean isRoundVideo() { return false; }
    boolean isLivePhoto() { return false; }
    boolean isHiddenSensitive() { return hiddenSensitive; }
}
class OriginalTl { static class Document {} }
class FileLoader {
    int cancelled;
    void cancelLoadFile(OriginalTl.Document document) { cancelled++; }
}
class VideoPlayer {
    boolean released;
    boolean playing = true;
    float speed = 1f;
    long position;
    int state = 3;
    Object delegate = new Object(), textureView = new Object(), surfaceView;
    boolean isPlaying() { return playing; }
    void pause() { playing = false; }
    int getPlaybackState() { return state; }
    long getCurrentPosition() { return position; }
    void setDelegate(Object delegate) { this.delegate = delegate; }
    void setTextureView(Object texture) { textureView = texture; }
    void setSurfaceView(Object surface) { surfaceView = surface; }
    void setLooping(boolean looping) {}
    void setStreamType(int type) {}
    void onRenderedFirstFrame() {}
    void onRenderedFirstFrame(Object event, Object output, long time) {}
}
class PhotoViewer {
    static PhotoViewer instance;
    static PhotoViewer getInstance() { return instance; }
    VideoPlayer videoPlayer;
    VideoPlayer injectingVideoPlayer;
    MessageObject currentMessageObject;
    final ArrayList<Object> imagesArr = new ArrayList<>();
    int closeCount;
    int loadRequests;
    boolean isPlaying, playerInjected, rejectOpen, visible, firstFrameRendered, textureUploaded, manuallyPaused;
    final ProgressView[] photoProgressViews = {new ProgressView()};
    int drawnState;
    void drawProgress(Object canvas, float x, float scale, float y, float alpha) { drawnState = photoProgressViews[0].backgroundState; }
    void updatePlayerState(boolean playWhenReady, int state) {}
    boolean isVisible() { return visible; }
    void setParentActivity(android.app.Activity activity, Object fragment, Object provider) {}
    boolean openPhoto(MessageObject message, long dialogId, long mergeId, long topicId,
            org.telegram.ui.PhotoViewer.PhotoViewerProvider provider, boolean fullscreen) {
        if (rejectOpen) return false;
        currentMessageObject = message;
        videoPlayer = injectingVideoPlayer != null ? injectingVideoPlayer : new VideoPlayer();
        injectingVideoPlayer = null;
        return true;
    }
    void setImageIndex(int index) {
        currentMessageObject = (MessageObject) imagesArr.get(index);
        videoPlayer = currentMessageObject.waitForDownload ? null : new VideoPlayer();
    }
    void onActionClick(boolean download) { loadRequests++; videoPlayer = new VideoPlayer(); }
    void playVideoOrWeb() { if (videoPlayer != null) videoPlayer.playing = true; }
    void closePhoto(boolean animated, boolean edit) { closeCount++; videoPlayer = null; }
}
class ProgressView {
    int backgroundState;
    void setBackgroundState(int state, boolean animated, boolean animateIcon) { backgroundState = state; }
    void setIndexedAlpha(int index, float alpha, boolean animated) {}
}
class PlatformMediaCallback {
    int next, previous;
    void onSkipToNext() { next++; }
    void onSkipToPrevious() { previous++; }
}
class InvalidPlatformMediaCallback {
    boolean onSkipToNext() { return true; }
    void onSkipToPrevious() {}
}
class SharedMediaCallback {
    int next, previous;
    void onSkipToNext() { if (Boolean.TRUE.equals(MessageIdentityMask.resolve(MediaController.instance.playingMessageObject, MessageIdentityMask.Kind.MUSIC))) next++; }
    void onSkipToPrevious() { if (Boolean.TRUE.equals(MessageIdentityMask.resolve(MediaController.instance.playingMessageObject, MessageIdentityMask.Kind.MUSIC))) previous++; }
}
class SharedMediaHolder {
    long getAvailableActions() { return Boolean.TRUE.equals(MessageIdentityMask.resolve(MediaController.instance.playingMessageObject, MessageIdentityMask.Kind.MUSIC)) ? 48L : 0L; }
}
class ActivityHolder { static android.app.Activity instance; }
class ContextView {
    final boolean isLocation;
    final int currentStyle = 0;
    final Label subtitleTextView = new Label();
    boolean isMusic = true;
    float shownSpeed;
    ContextView(boolean location) { isLocation = location; }
    void checkPlayer(boolean create) {}
    void updatePlaybackButton(boolean animated) { shownSpeed = MediaController.instance.getPlaybackSpeed(isMusic); }
    void lambda$createPlaybackSpeedButton$0(float speed) { MediaController.instance.setPlaybackSpeed(isMusic, speed); }

    static class Label {
        CharSequence text = "";
        void setText(CharSequence text, boolean animated) { this.text = text; }
        void f(CharSequence text, boolean animated) { this.text = text; }
    }
}
class ChatActivity {
    void updateTextureViewPosition(boolean update, boolean scroll) throws Throwable {
        MediaController.instance.cleanupPlayer(true, true, false, false);
    }

    void updateMessagesVisiblePart(boolean inLayout) throws Throwable {
        MediaController.instance.cleanupPlayer(true, true, false, false);
    }

    void openPhotoViewerForMessage(Object cell, MessageObject target) throws Throwable {
        // Original host clears the old player before calling PhotoViewer.openPhoto.
        MediaController.instance.cleanupPlayer(true, true, false, false);
        PhotoViewer.instance.currentMessageObject = target;
        PhotoViewer.instance.videoPlayer = new VideoPlayer();
        PhotoViewer.instance.visible = true;
    }
}
