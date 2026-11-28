package com.shitianyaa.nagramx.videotimer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

import com.shitianyaa.nagramx.videotimer.core.Xp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 安装并编排全部 Hook。
 */
final class NagramXHooks {

    private final XposedModule module;
    private final HostProfile host;
    private final HostBridge.Logger logger;

    private final VideoBackgroundSession session;
    private final ListenVideoAgent listen;
    private final HostTheme theme;
    private final HostStrings strings;
    private final MediaControllerAgent media;
    private final PhotoViewerAgent photo;
    private final NotificationDresser notification;
    private final PlayerUiAgent playerUi;
    private final AudioSleepTimerHooks audioTimer;

    private final ThreadLocal<Integer> suppressCleanup = new ThreadLocal<>();
    private final ThreadLocal<Integer> changingTrack = new ThreadLocal<>();

    private static int guardDepth(ThreadLocal<Integer> guard) {
        Integer value = guard.get();
        if (value == null) {
            guard.remove();
            return 0;
        }
        return value;
    }

    private static void leaveGuard(ThreadLocal<Integer> guard) {
        int remaining = guardDepth(guard) - 1;
        // 阿里规约 01-(七)：最外层作用域退出时移除线程局部状态。
        if (remaining <= 0) guard.remove();
        else guard.set(remaining);
    }

    NagramXHooks(XposedModule module, HostProfile host) {
        this.module = module;
        this.host = host;
        this.logger = (msg, t) -> Xp.log(msg, t);

        this.session = new VideoBackgroundSession(logger);
        this.theme = new HostTheme(host.bridge, logger);
        this.strings = new HostStrings(host.bridge, logger);
        this.media = new MediaControllerAgent(host, session, logger);
        this.playerUi = new PlayerUiAgent(host, session, strings, logger);
        this.photo = new PhotoViewerAgent(host, media, session, strings, playerUi, logger);
        this.photo.setTheme(this.theme);
        this.notification = new NotificationDresser(host, session, strings, logger);
        this.listen = new ListenVideoAgent(host, media, session, theme, strings, photo, playerUi, logger);
        this.photo.setListenVideo(listen);
        this.audioTimer = new AudioSleepTimerHooks(host, media, theme, strings, logger);
    }

    void install() {
        audioTimer.install();
        if (host.hasCompleteNativeTimerUi()) {
            if (host.canInjectTimerMenu()) {
                hookMenuInjection();
                Method updateNativeTimer = host.bridge.findMethod(host.photoViewerClass, "updateVideoSleepTimerMenu", 0);
                if (updateNativeTimer != null) intercept(updateNativeTimer, chain -> {
                    Object result = chain.proceed();
                    photo.hideNativeTimerItem(chain.getThisObject());
                    return result;
                });
                hookMiniPlayer();
            }
            return;
        }
        if (!host.canInjectTimerMenu()) {
            Xp.log("宿主缺少可注入的 PhotoViewer 视频菜单，模块保持停用");
            return;
        }
        if (!host.canStartBackgroundPlayback()) {
            Xp.log("宿主缺少后台播放所需入口，模块保持停用：缺失=" + host.describeGaps());
            return;
        }

        session.bind(
                this::probePlayback,
                this::onTimerExpired,
                () -> {
                    playerUi.refreshCountdown();
                    photo.refreshAllMenus();
                }
        );

        hookMenuInjection();
        hookPhotoViewerClose();
        if (host.niagram1322 && host.player.isPlaying != null) {
            intercept(host.player.isPlaying, chain ->
                    media.isTransferringViewerPlayer(chain.getThisObject()) ? true : chain.proceed());
        }
        hookPhotoViewerState();
        hookServiceEligibility();
        hookRoundVideoOverlay();
        hookPlaylistPreservation();
        hookTrackEnd();
        hookDownloadCancel();
        hookSessionTeardown();
        hookStrayViewerCallbacks();

        hookIdentityQueries();
        hookNotificationDressing();
        hookTelegramMediaSession();
        hookPlatformMediaSession();
        hookMusicMetadata();

        hookMiniPlayer();
        hookPlaylistAlert();

        hookChatActivityGuards();
        hookPlaylistThumbnails();

        Xp.log("Hook 安装完成：version=" + host.versionName + "/" + host.versionCode +
                ", playlist=" + host.canManagePlaylist() +
                ", 通知=" + host.canDressNotification() +
                ", 缺失=" + host.describeGaps());
        if (host.canDressNotification()) {
            String gaps = notification.getDressedNotificationGaps();
            if (!gaps.isEmpty()) {
                Xp.log("通知栏已知差异：" + gaps);
            }
        }
    }

    void dispose() {
        audioTimer.dispose();
    }

    private VideoBackgroundSession.ProbeResult probePlayback() {
        Object playing = media.playingMessage();
        boolean alive = media.hasSession();
        boolean isPlaying = false;
        int id = 0;
        if (playing != null) {
            Object idVal = host.bridge.invoke(playing, host.message.getId);
            if (idVal instanceof Number) {
                id = ((Number) idVal).intValue();
            }
        }
        boolean reachedEnd = media.hasReachedEnd();
        if (alive) {
            isPlaying = media.isActuallyPlaying();
        }

        // The visible viewer owns the user's current video, including loading gaps.
        {
            Object photoViewer = host.bridge.invoke(null, host.photo.getInstance);
            if (photoViewer != null) {
                if (host.isPhotoVisible(photoViewer)) {
                    Object photoMessage = host.messageOf(photoViewer);
                    Object photoPlayer = host.playerOf(photoViewer);
                    if (photoMessage != null && host.isVideoMessage(photoMessage)) {
                        alive = true;
                        playing = photoMessage;
                        Object idVal = host.bridge.invoke(photoMessage, host.message.getId);
                        if (idVal instanceof Number) {
                            id = ((Number) idVal).intValue();
                        }
                        reachedEnd = false;
                        Object isP = photoPlayer != null ? host.bridge.invoke(photoPlayer, host.player.isPlaying) : null;
                        isPlaying = Boolean.TRUE.equals(isP);
                        int state = host.playbackStateOf(photoPlayer);
                        if (state >= 0) {
                            isPlaying = isPlaying && state == MediaControllerAgent.EXO_STATE_READY;
                            reachedEnd = state == MediaControllerAgent.EXO_STATE_ENDED;
                        }
                    }
                }
            }
        }

        return new VideoBackgroundSession.ProbeResult(alive, id, reachedEnd, isPlaying);
    }

    private void onTimerExpired() {
        Xp.log("定时到期，暂停视频");
        if (session.isBackgroundActive()) {
            media.pausePlayback();
        }
        Object photoViewer = host.bridge.invoke(null, host.photo.getInstance);
        if (photoViewer != null) {
            if (host.isPhotoVisible(photoViewer)) {
                Method pauseMethod = host.photo.pauseVideoOrWeb;
                if (pauseMethod != null) {
                    host.bridge.invoke(photoViewer, pauseMethod);
                } else {
                    Object player = host.playerOf(photoViewer);
                    if (player != null) {
                        host.bridge.invoke(player, host.player.pause);
                    }
                }
            }
        }
        photo.refreshAllMenus();
        notification.refresh("定时到期");
        playerUi.onSessionEnded();
        android.content.Context ctx = host.getAppContext();
        if (ctx != null) {
            photo.toast(ctx, strings.get(ctx, "VideoSleepTimerFinished"));
        }
    }

    // ---- Hook 安装 ----

    private void hookMenuInjection() {
        Method method = host.photo.setParentActivity;
        if (method == null) return;
        intercept(method, chain -> {
            Object result = chain.proceed();
            Object thiz = chain.getThisObject();
            if (thiz != null) {
                photo.scheduleMenuInjection(thiz);
            }
            return result;
        });
    }

    private void hookPhotoViewerClose() {
        Method closePhoto = host.photo.closePhoto;
        if (closePhoto == null) return;
        intercept(closePhoto, chain -> {
            Object viewer = chain.getThisObject();
            if (viewer != null && session.isActive() && !listen.isClosingViewer(viewer)) {
                photo.preservePlayerOnClose(viewer);
            }
            return chain.proceed();
        });
    }

    private void hookPhotoViewerState() {
        // An injected MediaController player lacks PhotoViewer's VideoPlayer
        // subclass override. Its real first frame must unlock SurfaceView drawing.
        for (int count : new int[]{0, 3}) {
            Method firstFrame = host.bridge.findMethod(host.videoPlayerClass, "onRenderedFirstFrame", count);
            if (firstFrame == null) continue;
            intercept(firstFrame, chain -> {
                Object result = chain.proceed();
                Object viewer = host.bridge.invoke(null, host.photo.getInstance);
                if (session.isActive() && viewer != null && host.playerOf(viewer) == chain.getThisObject()
                        && Boolean.TRUE.equals(host.bridge.getField(viewer, host.photo.playerInjectedField))
                        && !Boolean.TRUE.equals(host.bridge.getField(viewer, "firstFrameRendered"))) {
                    host.bridge.setField(viewer, "firstFrameRendered", true);
                    host.bridge.setField(viewer, "textureUploaded", true);
                    Object container = host.bridge.getField(viewer, "containerView");
                    if (container instanceof android.view.View) ((android.view.View) container).invalidate();
                    Xp.log("转交视频收到全屏首帧：message=" + host.messageIdOf(host.messageOf(viewer)));
                }
                return result;
            });
        }
        // checkProgress finishes asynchronously and may overwrite the paused icon
        // after updatePlayerState. Reconcile at drawing, using the real player state.
        Method drawProgress = host.bridge.findMethod(host.photoViewerClass, host.niagram1322 ? "K6" : "drawProgress", 5);
        if (drawProgress != null) {
            intercept(drawProgress, chain -> {
                if (session.isActive() && host.isVideoMessage(host.messageOf(chain.getThisObject()))) {
                    photo.ensurePausePlayButtonVisible(chain.getThisObject());
                }
                return chain.proceed();
            });
        }
        Method updateState = host.photo.updatePlayerState;
        if (updateState == null) return;
        intercept(updateState, chain -> {
            Object result = chain.proceed();
            Object viewer = chain.getThisObject();
            if (viewer != null && session.isActive()) {
                photo.ensurePausePlayButtonVisible(viewer);
            }
            return result;
        });
    }

    private void hookServiceEligibility() {
        Method method = host.media.canStartMusicPlayerService;
        if (method != null) {
            intercept(method, chain -> {
                if (session.isBackgroundActive() && host.isVideoMessage(media.playingMessage())) {
                    return Boolean.TRUE;
                } else {
                    return chain.proceed();
                }
            });
        }
    }

    private void hookRoundVideoOverlay() {
        if (host.media.setCurrentVideoVisible != null) {
            intercept(host.media.setCurrentVideoVisible, chain -> {
                if (session.isBackgroundActive()) {
                    media.closeRoundVideoOverlay(chain.getThisObject());
                    return null;
                } else {
                    return chain.proceed();
                }
            });
        }
        for (Method method : host.media.setTextureView) {
            intercept(method, chain -> {
                if (session.isBackgroundActive()) {
                    return null;
                } else {
                    return chain.proceed();
                }
            });
        }

        Method show = host.bridge.findMethod(host.pipRoundVideoViewClass, host.niagram1322 ? "q" : "show", 2,
                m -> m.getParameterTypes()[1] == Runnable.class && m.getReturnType() == void.class);
        if (show != null) {
            Xp.hook(show, ExceptionMode.PASSTHROUGH, chain -> {
                if (session.isBackgroundActive()) {
                    throw new IllegalStateException("background video playback active");
                }
                return chain.proceed();
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void hookPlaylistPreservation() {
        for (Method skip : new Method[]{host.media.playNextMessage, host.media.playPreviousMessage}) {
            if (skip == null) continue;
            if (host.niagram1322) {
                intercept(skip, chain -> {
                    if (listen.current() == null) return chain.proceed();
                    listen.skip(skip == host.media.playNextMessage);
                    return null;
                });
                continue;
            }
            intercept(skip, chain -> {
                if (!session.isBackgroundActive() || !host.isVideoMessage(media.playingMessage())) return chain.proceed();
                Object list = host.bridge.getField(chain.getThisObject(), host.media.playlistField);
                Object shuffled = host.bridge.getField(chain.getThisObject(), host.media.shuffledPlaylistField);
                Xp.log("通知换视频：" + skip.getName() + ", count=" + (list instanceof List ? ((List<?>) list).size() : -1)
                        + ", shuffled=" + (shuffled instanceof List ? ((List<?>) shuffled).size() : -1)
                        + ", index=" + host.bridge.getField(chain.getThisObject(), host.media.currentPlaylistNumField));
                if (media.skipVideoChronologically(skip == host.media.playNextMessage)) return null;
                return notification.around(chain::proceed);
            });
        }
        Method method = host.media.playMessage;
        Field playlistField = host.media.playlistField;
        if (method == null || (playlistField == null && !host.niagram1322)) return;

        intercept(method, chain -> {
            if (!session.isActive()) {
                return chain.proceed();
            }

            Object[] args = chain.getArgs().toArray();
            Object target = (args.length > 0) ? args[0] : null;
            if (!host.isVideoMessage(target)) {
                session.markBackgroundActive(false);
                return chain.proceed();
            }
            if (host.niagram1322 && !listen.owns(target)) {
                session.markBackgroundActive(false);
                return chain.proceed();
            }
            Object previous = media.playingMessage();
            boolean sameDialog = previous == null || host.dialogIdOf(previous) == host.dialogIdOf(target);
            session.markBackgroundActive(true);

            Object listObj = host.bridge.getField(chain.getThisObject(), playlistField);
            List<Object> list = (listObj instanceof List) ? (List<Object>) listObj : null;
            List<Object> snapshot = sameDialog && list != null ? new ArrayList<>(list) : null;

            // A track change must release the previous player. Only the module's
            // timer survives this cleanup; ChatActivity cleanup is a separate scope.
            changingTrack.set(guardDepth(changingTrack) + 1);
            Object result;
            try {
                result = host.niagram1322 ? chain.proceed() : MessageIdentityMask.around(
                        MessageIdentityMask.Spec.asMusic(target),
                        (MessageIdentityMask.Action<Object>) chain::proceed
                );
            } finally {
                leaveGuard(changingTrack);
            }
            if (Boolean.TRUE.equals(result)) {
                if (host.niagram1322 && listen.owns(target)) playerUi.notifyPlaybackStarted();
                if (snapshot != null && !snapshot.isEmpty()) {
                    media.applyPlaylist(chain.getThisObject(), snapshot, target);
                }
            } else {
                Xp.log("视频换轨失败，保留定时设置等待再次播放");
                session.markBackgroundActive(false);
            }
            return result;
        });
    }

    private void hookTrackEnd() {
        Method method = host.media.updateVideoState;
        if (method == null) return;
        intercept(method, chain -> {
            Object[] args = chain.getArgs().toArray();
            int state = (args.length > 4 && args[4] instanceof Number) ? ((Number) args[4]).intValue() : 0;
            if (!session.isBackgroundActive() || state != MediaControllerAgent.EXO_STATE_ENDED) {
                return chain.proceed();
            }

            if (session.getMode() == VideoBackgroundSession.MODE_AFTER_CURRENT) {
                session.disarm();
                onTimerExpired();
                return null;
            }

            Object listObj = host.bridge.getField(chain.getThisObject(), host.media.playlistField);
            int playlistSize = (listObj instanceof List) ? ((List<?>) listObj).size() : 0;
            Method playNext = host.media.playNextMessageWithoutOrder;
            if (playlistSize > 1 && playNext != null) {
                changingTrack.set(guardDepth(changingTrack) + 1);
                try {
                    host.bridge.invoke(chain.getThisObject(), playNext, true);
                } finally {
                    leaveGuard(changingTrack);
                }
                return null;
            }
            media.pausePlayback();
            return null;
        });
    }

    private void hookDownloadCancel() {
        Method method = host.cancelLoadFileByDocument;
        if (method == null) return;
        intercept(method, chain -> {
            if (!session.isBackgroundActive() || guardDepth(changingTrack) > 0) return chain.proceed();
            Object[] args = chain.getArgs().toArray();
            if (args.length == 0 || args[0] == null) return chain.proceed();
            Object target = args[0];
            Object playing = media.playingMessage();
            if (playing == null) return chain.proceed();
            Object playingDocument = host.bridge.invoke(playing, host.message.getDocument);
            if (playingDocument != null && playingDocument == target) {
                Xp.log("跳过取消后台视频的下载");
                return null;
            }
            return chain.proceed();
        });
    }

    private void hookSessionTeardown() {
        Method method = host.media.cleanupPlayer4;
        if (method == null) return;
        intercept(method, chain -> {
            Object[] args = chain.getArgs().toArray();
            boolean stopService = (args.length > 1 && Boolean.TRUE.equals(args[1]));
            boolean transferToPhotoViewer = (args.length > 3 && Boolean.TRUE.equals(args[3]));
            boolean switching = guardDepth(changingTrack) > 0;
            if (session.isBackgroundActive() && !transferToPhotoViewer &&
                    ((guardDepth(suppressCleanup) > 0 && (!switching || stopService))
                    || (host.niagram1322 && switching && stopService && isChatTextureCleanup()))) {
                Xp.log("跳过 ChatActivity 对后台视频的清理");
                return null;
            }
            if (switching && !transferToPhotoViewer) {
                return chain.proceed();
            }
            if (session.isBackgroundActive() && stopService && !transferToPhotoViewer) {
                endSession();
            } else if (session.isBackgroundActive() && transferToPhotoViewer) {
                session.markBackgroundActive(false);
                photo.refreshAllMenus();
                playerUi.onSessionEnded();
            }
            return chain.proceed();
        });
    }

    private boolean isChatTextureCleanup() {
        // ART can inline the chat method and bypass its hook during a playback notification.
        Method update = host.playerUi.updateTextureViewPosition;
        if (update == null) return false;
        String owner = update.getDeclaringClass().getName();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (owner.equals(frame.getClassName()) && update.getName().equals(frame.getMethodName())) return true;
        }
        return false;
    }

    private void hookStrayViewerCallbacks() {
        Method[] callbacks = new Method[]{host.photo.playOrStopAnimatedStickers, host.photo.seekAnimatedStickersTo};
        for (Method method : callbacks) {
            if (method == null) continue;
            intercept(method, chain -> {
                if (photo.getTransferredPlayer() != null && session.isBackgroundActive()) {
                    return null;
                }
                return chain.proceed();
            });
        }
    }

    private void hookPlatformMediaSession() {
        if (host.niagram1322) {
            Class<?> callback = host.bridge.loadClass(NiagramDexClasses.PLATFORM_MEDIA_CALLBACK);
            hookPlatformMediaSession(callback);
            if (callback != null) new ListenVideoMediaActions(listen).install();
        }
    }

    private void hookPlatformMediaSession(Class<?> callbackClass) {
        if (callbackClass == null) {
            Xp.log("未解析到系统媒体回调，视频上一条/下一条入口未安装");
            return;
        }
        for (String name : new String[]{"onSkipToNext", "onSkipToPrevious"}) {
            Method method = host.bridge.findMethod(callbackClass, name, 0,
                    candidate -> !Modifier.isStatic(candidate.getModifiers())
                            && candidate.getReturnType() == void.class);
            if (method == null) continue;
            intercept(method, chain -> {
                if (listen.current() == null) return chain.proceed();
                boolean changed = listen.skip("onSkipToNext".equals(name));
                Xp.log("系统媒体视频切换：" + name + ", changed=" + changed);
                return null;
            });
        }
    }

    private void hookTelegramMediaSession() {
        hookTelegramMediaSession(host.bridge.loadClass("org.telegram.messenger.TelegramMediaSession"),
                host.bridge.loadClass("org.telegram.messenger.TelegramMediaSession$SessionCallback"));
    }

    private void hookTelegramMediaSession(Class<?> holderClass, Class<?> callbackClass) {
        if (holderClass == null || callbackClass == null) return;
        for (String name : new String[]{"onSkipToNext", "onSkipToPrevious", "onSetRepeatMode", "onSetShuffleMode"}) {
            Method method = host.bridge.findMethod(callbackClass, name, name.startsWith("onSet") ? 1 : 0);
            if (method == null) continue;
            intercept(method, chain -> {
                if (!session.isBackgroundActive() || !host.isVideoMessage(media.playingMessage())) return chain.proceed();
                Xp.log("收到 TelegramMediaSession 控制：" + name);
                return notification.around(chain::proceed);
            });
        }
        Method actions = host.bridge.findMethod(holderClass, "getAvailableActions", 0);
        if (actions != null) intercept(actions, chain -> notification.around(chain::proceed));
        Method publish = host.bridge.findMethod(holderClass, "publishPlaybackState", 1);
        if (publish != null) intercept(publish, chain -> {
            Object result = chain.proceed();
            if (session.isBackgroundActive() && host.isVideoMessage(media.playingMessage())) {
                android.app.PendingIntent jump = NotificationDresser.createJumpPendingIntent(host, media.playingMessage());
                Object nativeSession = host.bridge.getField(chain.getThisObject(), "session");
                if (jump != null && nativeSession != null) host.bridge.invokeNamed(nativeSession, "setSessionActivity", jump);
            }
            return result;
        });
        // The shared session inherits an empty custom-action callback. Its native
        // mode setters update settings, session state and the notification together.
        Method custom = host.bridge.findMethod(callbackClass, "onCustomAction", 2);
        if (custom != null) intercept(custom, chain -> {
            if (!callbackClass.isInstance(chain.getThisObject()) || !session.isBackgroundActive()
                    || !host.isVideoMessage(media.playingMessage())) return chain.proceed();
            Object action = chain.getArg(0);
            Class<?> config = host.bridge.loadClass("org.telegram.messenger.SharedConfig");
            Object repeatAction = host.bridge.getStaticField(host.musicPlayerServiceClass, "NOTIFY_REPEAT");
            Object shuffleAction = host.bridge.getStaticField(host.musicPlayerServiceClass, "NOTIFY_SHUFFLE");
            if (action != null && action.equals(repeatAction)) {
                Object repeat = host.bridge.getStaticField(config, "repeatMode");
                if (!(repeat instanceof Number)) return chain.proceed();
                int next = (((Number) repeat).intValue() + 1) % 3;
                host.bridge.invokeNamed(chain.getThisObject(), "onSetRepeatMode", next == 1 ? 2 : next == 2 ? 1 : 0);
                Xp.log("通知循环模式：" + next);
                return null;
            }
            if (action != null && action.equals(shuffleAction)) {
                Object shuffle = host.bridge.getStaticField(config, "shuffleMusic");
                if (!(shuffle instanceof Boolean)) return chain.proceed();
                notification.aroundVoid(() -> host.bridge.invokeNamed(chain.getThisObject(), "onSetShuffleMode", Boolean.TRUE.equals(shuffle) ? 0 : 1));
                Xp.log("通知随机播放：" + !Boolean.TRUE.equals(shuffle));
                return null;
            }
            return chain.proceed();
        });
    }

    private void hookIdentityQueries() {
        if (host.message.isMusic != null) {
            intercept(host.message.isMusic, chain -> {
                Boolean spoofed = MessageIdentityMask.resolve(chain.getThisObject(), MessageIdentityMask.Kind.MUSIC);
                return spoofed != null ? spoofed : chain.proceed();
            });
        }
        if (host.message.isVideo != null) {
            intercept(host.message.isVideo, chain -> {
                Boolean spoofed = MessageIdentityMask.resolve(chain.getThisObject(), MessageIdentityMask.Kind.VIDEO);
                return spoofed != null ? spoofed : chain.proceed();
            });
        } else if (host.niagram1322 && host.message.isVideoMessageStatic != null) {
            intercept(host.message.isVideoMessageStatic, chain -> {
                Boolean spoofed = MessageIdentityMask.resolve(chain.getArg(0), MessageIdentityMask.Kind.VIDEO);
                return spoofed != null ? spoofed : chain.proceed();
            });
        } else {
            Xp.log("找不到 MessageObject.isVideo，迷你播放器与滚动保护不可用");
        }
    }

    private void hookNotificationDressing() {
        if (!host.canDressNotification()) return;

        if (host.musicService.createNotification != null) {
            intercept(host.musicService.createNotification, chain -> {
                NotificationDresser.MusicServiceHandle.current = chain.getThisObject();
                Object[] args = chain.getArgs().toArray();
                Object targetMsg = (args.length > 0 && args[0] != null) ? args[0] : media.playingMessage();
                if (session.isBackgroundActive() && host.isVideoMessage(targetMsg)) Xp.log("生成后台视频媒体通知");
                return MessageIdentityMask.around(MessageIdentityMask.Spec.asMusic(targetMsg), () -> {
                    Object result = chain.proceed();
                    if (targetMsg != null && host.isVideoMessage(targetMsg)) {
                        android.app.PendingIntent jump = NotificationDresser.createJumpPendingIntent(host, targetMsg);
                        if (jump != null) {
                            NotificationDresser.bindMediaSessionActivity(host, chain.getThisObject(), jump);
                        }
                    }
                    return result;
                });
            });
        }
        if (host.musicService.updatePlaybackState != null) {
            intercept(host.musicService.updatePlaybackState, chain -> notification.around(chain::proceed));
        }
        if (host.musicService.onStartCommand != null) {
            intercept(host.musicService.onStartCommand, chain -> {
                NotificationDresser.MusicServiceHandle.current = chain.getThisObject();
                if (session.isBackgroundActive()) Xp.log("视频通知服务收到启动请求");
                return notification.around(chain::proceed);
            });
        }
        if (host.musicService.onDestroy != null) {
            intercept(host.musicService.onDestroy, chain -> {
                if (session.isBackgroundActive()) Xp.log("后台视频期间通知服务被销毁");
                if (NotificationDresser.MusicServiceHandle.current == chain.getThisObject()) {
                    NotificationDresser.MusicServiceHandle.current = null;
                }
                return chain.proceed();
            });
        }

        Method[] skips = new Method[]{host.musicService.onSkipToNext, host.musicService.onSkipToPrevious};
        for (Method method : skips) {
            if (method != null) {
                intercept(method, chain -> {
                    if (session.isBackgroundActive() && host.isVideoMessage(media.playingMessage())) {
                        Xp.log("收到媒体会话切换：" + method.getName());
                    }
                    return notification.around(chain::proceed);
                });
            }
        }
        if (host.musicService.sessionCallbackClass == null && !host.niagram1322) {
            Xp.log("未找到 MediaSession 回调匿名类，锁屏上一首/下一首对视频可能无效");
        }

        // 拦截通知栏的 contentIntent，将其替换为直接跳转至视频所在群组消息位置
        Method startForeground = host.bridge.findMethod(host.musicPlayerServiceClass, "startForeground", int.class, android.app.Notification.class);
        if (startForeground != null) {
            intercept(startForeground, chain -> {
                updateNotificationContentIntent(chain);
                return chain.proceed();
            });
        }
        Method startForeground3 = host.bridge.findMethod(host.musicPlayerServiceClass, "startForeground", int.class, android.app.Notification.class, int.class);
        if (startForeground3 != null) {
            intercept(startForeground3, chain -> {
                updateNotificationContentIntent(chain);
                return chain.proceed();
            });
        }

        Class<?> nmClass = host.bridge.loadClass("android.app.NotificationManager");
        if (nmClass != null) {
            Method nmNotify2 = host.bridge.findMethod(nmClass, "notify", int.class, android.app.Notification.class);
            if (nmNotify2 != null) {
                intercept(nmNotify2, chain -> {
                    updateNotificationContentIntent(chain);
                    return chain.proceed();
                });
            }
            Method nmNotify3 = host.bridge.findMethod(nmClass, "notify", String.class, int.class, android.app.Notification.class);
            if (nmNotify3 != null) {
                intercept(nmNotify3, chain -> {
                    Object[] args = chain.getArgs().toArray();
                    if (args.length > 2 && args[2] instanceof android.app.Notification && (session.isBackgroundActive() || session.isArmed())) {
                        Object playing = media.playingMessage();
                        if (playing != null && host.isVideoMessage(playing)) {
                            android.app.Notification notif = (android.app.Notification) args[2];
                            android.app.PendingIntent jump = NotificationDresser.createJumpPendingIntent(host, playing);
                            if (jump != null) {
                                notif.contentIntent = jump;
                            }
                            Object service = NotificationDresser.MusicServiceHandle.current;
                            if (service != null) {
                                NotificationDresser.bindMediaSessionActivity(host, service, jump);
                                String title = notification.videoTitleFallback(playing, null);
                                String author = notification.videoAuthorFallback(playing, false);
                                NotificationDresser.updateMediaMetadata(host, service, title, author);
                            }
                            notification.dressNotification(notif, playing);
                        }
                    }
                    return chain.proceed();
                });
            }
        }
    }

    private void updateNotificationContentIntent(Chain chain) {
        Object[] args = chain.getArgs().toArray();
        if (args.length > 1 && args[1] instanceof android.app.Notification && (session.isBackgroundActive() || session.isArmed())) {
            Object playing = media.playingMessage();
            if (playing != null && host.isVideoMessage(playing)) {
                android.app.Notification notif = (android.app.Notification) args[1];
                android.app.PendingIntent jump = NotificationDresser.createJumpPendingIntent(host, playing);
                if (jump != null) {
                    notif.contentIntent = jump;
                }
                Object service = NotificationDresser.MusicServiceHandle.current;
                if (service != null) {
                    NotificationDresser.bindMediaSessionActivity(host, service, jump);
                    String title = notification.videoTitleFallback(playing, null);
                    String author = notification.videoAuthorFallback(playing, false);
                    NotificationDresser.updateMediaMetadata(host, service, title, author);
                }
                notification.dressNotification(notif, playing);
            }
        }
    }

    private void hookMusicMetadata() {
        if (host.message.getMusicTitle0 != null) {
            intercept(host.message.getMusicTitle0, chain -> {
                Object target = chain.getThisObject();
                if (dressingVideo(target)) {
                    String title = notification.videoTitleFallback(target, null);
                    if (title != null && !title.isEmpty()) {
                        return title;
                    }
                }
                return chain.proceed();
            });
        }
        if (host.message.getMusicTitle1 != null) {
            intercept(host.message.getMusicTitle1, chain -> {
                Object target = chain.getThisObject();
                if (dressingVideo(target)) {
                    String title = notification.videoTitleFallback(target, null);
                    if (title != null && !title.isEmpty()) {
                        return title;
                    }
                }
                return chain.proceed();
            });
        }
        if (host.message.getMusicAuthor0 != null) {
            intercept(host.message.getMusicAuthor0, chain -> {
                Object target = chain.getThisObject();
                if (dressingVideo(target)) {
                    String author = notification.videoAuthorFallback(target, false);
                    if (author != null && !author.isEmpty()) {
                        return author;
                    }
                }
                return chain.proceed();
            });
        }
        if (host.message.getMusicAuthor1 != null) {
            intercept(host.message.getMusicAuthor1, chain -> {
                Object target = chain.getThisObject();
                if (dressingVideo(target)) {
                    Object[] args = chain.getArgs().toArray();
                    boolean unknown = (args.length > 0 && args[0] instanceof Boolean) ? (Boolean) args[0] : true;
                    String author = notification.videoAuthorFallback(target, unknown);
                    if (author != null && !author.isEmpty()) {
                        return author;
                    }
                }
                return chain.proceed();
            });
        }
        if (host.message.getMusicTitle0 == null && host.message.getMusicTitle1 == null) {
            Xp.log("找不到 MessageObject.getMusicTitle，视频的名称退回原版");
        }
    }

    private boolean dressingVideo(Object target) {
        if (target == null) return false;
        if (session.isBackgroundActive() || session.isArmed()) {
            return host.isVideoMessage(target);
        }
        return false;
    }

    private void hookMiniPlayer() {
        PlayerUiMembers ui = host.playerUi;
        if (ui.contextViewClass == null) {
            Xp.log("找不到 FragmentContextView，跳过迷你播放器");
            return;
        }

        for (java.lang.reflect.Constructor<?> c : ui.contextViewConstructors) {
            Xp.hook(c, ExceptionMode.PROTECTIVE, chain -> {
                Object result = chain.proceed();
                playerUi.rememberContextView(chain.getThisObject());
                return result;
            });
        }

        if (ui.checkPlayer != null) {
            intercept(ui.checkPlayer, chain -> {
                Object result = playerUi.wrapContextView(chain::proceed);
                playerUi.rememberContextView(chain.getThisObject());
                playerUi.refreshCountdown(true);
                return result;
            });
        }
        if (ui.contextViewUpdatePlaybackButton != null) {
            intercept(ui.contextViewUpdatePlaybackButton, chain -> playerUi.withVideoSpeed(chain.getThisObject(),
                    () -> playerUi.wrapContextView(chain::proceed)));
        }
        for (Method callback : ui.contextViewSpeedCallbacks) {
            intercept(callback, chain -> playerUi.withVideoSpeed(chain.getThisObject(), chain::proceed));
        }

        if (ui.contextViewClick != null) {
            intercept(ui.contextViewClick, chain ->
                    listen.openFromMiniPlayer(chain.getThisObject()) ? null : chain.proceed());
        }
        for (Method method : ui.contextViewClickLambdas) {
            intercept(method, chain -> {
                if (!chain.getArgs().isEmpty() && chain.getArg(0) == chain.getThisObject() &&
                        (listen.openFromMiniPlayer(chain.getThisObject()) ||
                                (!host.hasCompleteNativeTimerUi() && playerUi.openCurrentVideo(chain.getThisObject())))) return null;
                return playerUi.wrapContextView(chain::proceed);
            });
        }
        Xp.log("迷你播放器已接管：点击回调=" + ui.contextViewClickLambdas.size() + " 个");
    }

    private void hookPlaylistAlert() {
        PlayerUiMembers ui = host.playerUi;
        if (ui.alertClass == null) {
            Xp.log("找不到 AudioPlayerAlert，跳过播放列表");
            return;
        }

        if (ui.alertConstructor != null) {
            Xp.hook(ui.alertConstructor, ExceptionMode.PROTECTIVE, chain -> playerUi.wrapPlaylist(chain::proceed));
        }
        if (ui.alertDidReceivedNotification != null) {
            intercept(ui.alertDidReceivedNotification, chain -> playerUi.wrapPlaylist(chain::proceed));
        }
        if (ui.alertUpdateTitle != null) {
            intercept(ui.alertUpdateTitle, chain -> playerUi.wrapPlaylist(chain::proceed));
        }
        for (Method method : ui.alertUpdateProgress) {
            intercept(method, chain -> playerUi.wrapPlaylist(chain::proceed));
        }

        if (ui.cellDidPressedButton != null) {
            intercept(ui.cellDidPressedButton, chain -> {
                Object cell = chain.getThisObject();
                if (cell != null && playerUi.openInFullscreen(cell)) {
                    return null;
                }
                return chain.proceed();
            });
        }
    }

    private void hookChatActivityGuards() {
        PlayerUiMembers ui = host.playerUi;
        if (ui.chatActivityClass == null) {
            Xp.log("找不到 ChatActivity，跳过后台播放保护");
            return;
        }

        if (ui.openPhotoViewerForMessage != null) {
            intercept(ui.openPhotoViewerForMessage, chain -> {
                Object target = chain.getArgs().size() > 1 ? chain.getArg(1) : null;
                if (!session.isActive() || !host.isVideoMessage(target)) return chain.proceed();
                // ChatActivity stops the old media player before opening a different
                // video. This is a replacement, not the user's close action.
                changingTrack.set(guardDepth(changingTrack) + 1);
                try {
                    return chain.proceed();
                } finally {
                    leaveGuard(changingTrack);
                    Object viewer = host.bridge.invoke(null, host.photo.getInstance);
                    Object current = host.messageOf(viewer);
                    if (current != null && host.messageIdOf(current) == host.messageIdOf(target)
                            && host.dialogIdOf(current) == host.dialogIdOf(target)) {
                        session.markBackgroundActive(false);
                        Xp.log("消息页切换视频，保留定时：message=" + host.messageIdOf(target));
                    }
                }
            });
        } else {
            Xp.log("找不到 ChatActivity.openPhotoViewerForMessage，消息页切换保护不可用");
        }

        Method[] cleans = new Method[]{ui.onRemoveFromParent, ui.updateTextureViewPosition, ui.updateMessagesVisiblePart};
        for (Method method : cleans) {
            if (method != null) {
                intercept(method, chain -> {
                    suppressCleanup.set(guardDepth(suppressCleanup) + 1);
                    try {
                        if (method == ui.updateMessagesVisiblePart && session.isBackgroundActive()) {
                            return playerUi.wrapChatScroll(chain::proceed);
                        }
                        return chain.proceed();
                    } finally {
                        leaveGuard(suppressCleanup);
                    }
                });
            }
        }

        if (ui.updateMessagesVisiblePart == null) {
            Xp.log("找不到 ChatActivity.updateMessagesVisiblePart，滚动时可能中断后台播放");
        }
    }

    private void hookPlaylistThumbnails() {
        PlayerUiMembers ui = host.playerUi;
        Method method = ui.cellSetMessageObject;
        Field radialField = ui.cellRadialProgressField;
        if (method == null || radialField == null) return;

        intercept(method, chain -> {
            Object result = chain.proceed();
            Object[] args = chain.getArgs().toArray();
            Object target = (args.length > 0) ? args[0] : null;
            if (session.isBackgroundActive() && host.isVideoMessage(target)) {
                applyVideoThumbnail(chain.getThisObject(), target, radialField);
            }
            return result;
        });
    }

    private void applyVideoThumbnail(Object cell, Object message, Field radialField) {
        HostBridge bridge = host.bridge;
        Object radial = bridge.getField(cell, radialField);
        if (radial == null) return;
        Object document = bridge.invoke(message, host.message.getDocument);
        if (document == null) return;
        Object thumbsObj = bridge.getField(document, "thumbs");
        if (!(thumbsObj instanceof ArrayList)) return;
        ArrayList<?> thumbs = (ArrayList<?>) thumbsObj;

        Class<?> fileLoader = host.fileLoaderClass;
        Object thumb = bridge.invokeStatic(fileLoader, "getClosestPhotoSizeWithSize", thumbs, 90);
        if (thumb == null) return;
        Object bytesObj = bridge.getField(thumb, "bytes");
        if (!(bytesObj instanceof byte[])) return;
        byte[] bytes = (byte[]) bytesObj;
        if (bytes.length == 0) return;

        Bitmap bitmap = null;
        if ("TL_photoStrippedSize".equals(thumb.getClass().getSimpleName())) {
            Class<?> imageLoader = bridge.loadClass("org.telegram.messenger.ImageLoader");
            Object val = bridge.invokeStatic(imageLoader, "getStrippedPhotoBitmap", bytes, "b");
            if (val instanceof Bitmap) {
                bitmap = (Bitmap) val;
            }
        } else {
            try {
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Throwable failure) {
                logger.log("解码通知缩略图失败", failure);
            }
        }
        if (bitmap == null) return;

        bridge.invokeNamed(radial, "setImageOverlay", bitmap);
    }

    private void endSession() {
        session.end();
        listen.endSession();
        photo.onBackgroundSessionEnded();
        playerUi.onSessionEnded();
        NotificationDresser.MusicServiceHandle.current = null;
    }

    private void intercept(Method method, Hooker hooker) {
        Xp.hook(method, hooker);
    }
}
