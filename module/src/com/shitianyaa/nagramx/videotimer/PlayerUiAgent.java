package com.shitianyaa.nagramx.videotimer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 顶部迷你播放器与播放列表弹层。
 */
final class PlayerUiAgent {

    private static final long REOPEN_DELAY_MS = 200L;

    private final HostProfile host;
    private final VideoBackgroundSession session;
    private final HostStrings strings;
    private final HostBridge.Logger logger;
    private final HostBridge bridge;
    private final PlayerUiMembers ui;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Class<?> androidUtilitiesClass;

    private final Map<Object, Boolean> contextViews = Collections.synchronizedMap(new WeakHashMap<>());
    private int lastCountdownSecond = -1;
    private int lastCountdownMode = -1;
    private Object lastSubtitleMessage = null;

    private Object lastProbedMessage = null;
    private boolean lastProbedIsVideo = false;
    private List<Object> fullscreenPlaylist = Collections.emptyList();

    PlayerUiAgent(
            HostProfile host,
            VideoBackgroundSession session,
            HostStrings strings,
            HostBridge.Logger logger
    ) {
        this.host = host;
        this.session = session;
        this.strings = strings;
        this.logger = logger != null ? logger : (msg, t) -> {};
        this.bridge = host.bridge;
        this.ui = host.playerUi;
        this.androidUtilitiesClass = bridge.loadClass("org.telegram.messenger.AndroidUtilities");
    }

    private Object backgroundVideo() {
        if (!session.isBackgroundActive() && !session.isArmed()) return null;
        Object controller = host.mediaController();
        if (controller == null) return null;
        Object playing = host.media.getPlayingMessageObject == null
                ? bridge.getField(controller, host.media.playingMessageField)
                : bridge.invoke(controller, host.media.getPlayingMessageObject);
        if (playing == null) return null;
        return host.isVideoMessage(playing) ? playing : null;
    }

    private <T> T asMusicHidingVideo(MessageIdentityMask.Action<T> body) {
        Object current = backgroundVideo();
        Object owner = host.niagram1322 ? bridge.getField(current, host.message.messageOwnerField) : null;
        return MessageIdentityMask.around(MessageIdentityMask.Spec.asMusicHidingVideo(current, owner), body);
    }

    @SuppressWarnings("unchecked")
    private <T> T asMusicForPlaylist(MessageIdentityMask.Action<T> body) {
        Object current = backgroundVideo();
        if (current == null) {
            try {
                return body.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
        Object playlistObj = bridge.invoke(host.mediaController(), host.media.getPlaylist);
        List<Object> targets = new ArrayList<>();
        targets.add(current);
        if (playlistObj instanceof List) {
            for (Object item : (List<?>) playlistObj) {
                if (item != null && item != current && host.isVideoMessage(item)) {
                    targets.add(item);
                }
            }
        }
        return MessageIdentityMask.around(
                MessageIdentityMask.Spec.asMusic(targets.toArray()),
                body
        );
    }

    <T> T wrapContextView(MessageIdentityMask.Action<T> body) {
        return asMusicHidingVideo(body);
    }

    private boolean isAudioContextView(Object view) {
        if (view == null || Boolean.TRUE.equals(bridge.getField(view, ui.isLocationField))) return false;
        Object style = bridge.getField(view, ui.currentStyleField);
        return style instanceof Number && ((Number) style).intValue() == ui.styleAudioPlayer;
    }

    boolean openCurrentVideo(Object view) {
        return isAudioContextView(view) && openInFullscreen(backgroundVideo());
    }

    <T> T withVideoSpeed(Object view, MessageIdentityMask.Action<T> action) throws Throwable {
        if (!Boolean.TRUE.equals(bridge.getField(view, ui.contextViewIsMusicField))
                || !session.isBackgroundActive() || !isAudioContextView(view) || backgroundVideo() == null
                || !bridge.setField(view, ui.contextViewIsMusicField, false)) {
            return action.run();
        }
        // Keep the host's audio style outside speed callbacks.
        try {
            return action.run();
        } finally {
            bridge.setField(view, ui.contextViewIsMusicField, true);
        }
    }

    List<Object> playlistFor(Object current) {
        for (Object item : fullscreenPlaylist) {
            if (host.dialogIdOf(item) == host.dialogIdOf(current) && host.messageIdOf(item) == host.messageIdOf(current)) {
                return new ArrayList<>(fullscreenPlaylist);
            }
        }
        return Collections.singletonList(current);
    }

    <T> T wrapPlaylist(MessageIdentityMask.Action<T> body) {
        return asMusicForPlaylist(body);
    }

    <T> T wrapChatScroll(MessageIdentityMask.Action<T> body) {
        Object current = backgroundVideo();
        Object owner = host.niagram1322 ? bridge.getField(current, host.message.messageOwnerField) : null;
        return MessageIdentityMask.around(MessageIdentityMask.Spec.hidingVideo(current, owner), body);
    }

    void rememberContextView(Object view) {
        if (view == null) return;
        // The same host class also renders live locations; never turn that bar into a player.
        if (Boolean.TRUE.equals(bridge.getField(view, ui.isLocationField))) return;
        synchronized (contextViews) {
            if (!contextViews.containsKey(view)) {
                contextViews.put(view, Boolean.FALSE);
            }
        }
    }

    void refreshCountdown() {
        refreshCountdown(false);
    }

    void refreshCountdown(boolean relayout) {
        if (ui.miniPlayerSetText != null) {
            mainHandler.post(() -> renderCountdown(relayout));
        }
    }

    private void renderCountdown(boolean relayout) {
        Context context = host.getAppContext();
        if (context == null) return;
        Object message = session.isBackgroundActive() ? backgroundVideo() : null;
        String text = null;
        int mode = -1;
        if (message != null) {
            mode = session.getMode();
            if (mode == VideoBackgroundSession.MODE_AFTER_CURRENT) {
                lastCountdownSecond = -1;
                text = strings.get(context, "VideoSleepTimerAfterCurrent");
            } else if (mode == VideoBackgroundSession.MODE_DURATION) {
                int seconds = session.getRemainingSeconds();
                if (seconds == lastCountdownSecond && !relayout) return;
                lastCountdownSecond = seconds;
                text = strings.get(context, "VideoSleepTimer") + " · " + shortDuration(seconds);
            } else {
                lastCountdownSecond = -1;
                text = strings.get(context, "AttachVideo");
            }
        }
        if (text == null) {
            lastCountdownSecond = -1;
        }
        boolean modeOrTrackChanged = mode != lastCountdownMode || message != lastSubtitleMessage;
        lastCountdownMode = mode;
        lastSubtitleMessage = message;

        List<Object> views;
        synchronized (contextViews) {
            views = new ArrayList<>(contextViews.keySet());
        }
        for (Object view : views) {
            applySubtitle(view, message, text, relayout, modeOrTrackChanged);
        }
    }

    private String shortDuration(int seconds) {
        Object val = bridge.invokeStatic(androidUtilitiesClass, "formatShortDuration", seconds);
        if (val instanceof CharSequence) {
            return val.toString();
        }
        int minutes = seconds / 60;
        int remSec = seconds % 60;
        if (minutes >= 60) {
            return String.format("%d:%02d:%02d", minutes / 60, minutes % 60, remSec);
        } else {
            return String.format("%d:%02d", minutes, remSec);
        }
    }

    private void applySubtitle(Object view, Object message, String text, boolean relayout, boolean modeOrTrackChanged) {
        boolean twoLine = text != null;
        boolean wasTwoLine;
        synchronized (contextViews) {
            Boolean b = contextViews.get(view);
            wasTwoLine = b != null && b;
        }
        if (!twoLine && !wasTwoLine) return;
        if (!(view instanceof View) || !isAudioContextView(view)) {
            if (wasTwoLine) {
                contextViews.replace(view, Boolean.FALSE);
            }
            return;
        }
        if (twoLine && host.niagram1322 && bridge.getField(view, ui.contextViewMessageField) != message) {
            return;
        }

        Object subObj = bridge.getField(view, ui.subtitleTextViewField);
        if (!(subObj instanceof View)) return;
        View subtitle = (View) subObj;

        Object titleObj = bridge.getField(view, ui.titleTextViewField);
        View title = (titleObj instanceof View) ? (View) titleObj : null;

        try {
            Context context = ((View) view).getContext();
            boolean sideMenued = Boolean.TRUE.equals(bridge.getField(view, ui.isSideMenuedField));
            int rightMargin = 36 + (sideMenued ? 64 : 0);
            if (twoLine) {
                boolean layoutChanged = relayout || !wasTwoLine;
                if (layoutChanged) {
                    if (title != null) {
                        frame(context, title, 20, 37, 0, rightMargin);
                    }
                    int subtitleRightMargin = rightMargin + (host.niagram1322 ? 44 : 0);
                    frame(context, subtitle, 18, 37, 18, subtitleRightMargin);
                }
                if (host.niagram1322 && title != null && (layoutChanged || modeOrTrackChanged)) {
                    updateVideoTitle(context, title, message, wasTwoLine);
                }
                subtitle.animate().cancel();
                bridge.invoke(subtitle, ui.miniPlayerSetText, text, wasTwoLine && modeOrTrackChanged);
                if (!wasTwoLine || subtitle.getVisibility() != View.VISIBLE) {
                    subtitle.setAlpha(0f);
                    subtitle.setVisibility(View.VISIBLE);
                    subtitle.animate().alpha(1f).setDuration(180L).setInterpolator(HostTheme.easeOutQuint()).start();
                } else {
                    subtitle.setAlpha(1f);
                }
            } else {
                if (title != null) {
                    frame(context, title, 36, 37, 0, rightMargin);
                }
                subtitle.animate().cancel();
                if (subtitle.getVisibility() == View.VISIBLE) {
                    subtitle.animate()
                            .alpha(0f)
                            .setDuration(140L)
                            .setInterpolator(HostTheme.easeOutQuint())
                            .withEndAction(() -> {
                                if (!Boolean.TRUE.equals(contextViews.get(view))) {
                                    subtitle.setVisibility(View.GONE);
                                    subtitle.setAlpha(1f);
                                }
                            })
                            .start();
                } else {
                    subtitle.setVisibility(View.GONE);
                }
            }
            contextViews.replace(view, twoLine);
        } catch (RuntimeException e) {
            logger.log("刷新迷你播放器标题和倒计时失败", e);
        }
    }

    private void updateVideoTitle(Context context, View title, Object message, boolean animated) {
        String text = NotificationDresser.captionFirstLine(host, message);
        if (text == null) {
            text = strings.get(context, "AttachVideo") + " · " + host.messageIdOf(message);
        }
        Object label = bridge.invokeNamed(title, "getTextView");
        if (!(label instanceof TextView) || !TextUtils.equals(text, ((TextView) label).getText())) {
            bridge.invoke(title, ui.miniPlayerSetText, text, animated);
        }
    }

    @SuppressLint("RtlHardcoded")
    private void frame(Context context, View view, int height, int left, int top, int right) {
        float density = context.getResources().getDisplayMetrics().density;
        int h = (int) Math.ceil(density * height);
        int l = (int) Math.ceil(density * left);
        int t = (int) Math.ceil(density * top);
        int r = (int) Math.ceil(density * right);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                h,
                Gravity.LEFT | Gravity.TOP
        );
        params.setMargins(l, t, r, 0);
        view.setLayoutParams(params);
    }

    void onSessionEnded() {
        lastCountdownSecond = -1;
        refreshCountdown(true);
    }

    void notifyPlaybackStarted() {
        Runnable task = () -> {
            List<Object> views;
            synchronized (contextViews) {
                views = new ArrayList<>(contextViews.keySet());
            }
            for (Object view : views) {
                wrapContextView(() -> {
                    if (ui.checkPlayer != null) {
                        bridge.invoke(view, ui.checkPlayer, true);
                    }
                    return null;
                });
            }
            refreshCountdown(true);
        };
        mainHandler.post(task);
        mainHandler.postDelayed(task, 250L);
    }

    boolean openInFullscreen(Object cellOrTarget) {
        return openInFullscreen(cellOrTarget, null);
    }

    boolean openInFullscreen(Object cellOrTarget, Runnable onOpened) {
        if (cellOrTarget == null) return false;
        Object target = cellOrTarget;
        if (ui.cellGetMessageObject != null && ui.cellGetMessageObject.getDeclaringClass().isInstance(cellOrTarget)) {
            Object fromCell = bridge.invoke(cellOrTarget, ui.cellGetMessageObject);
            if (fromCell != null) target = fromCell;
        }
        if (!session.isBackgroundActive() || !host.isVideoMessage(target)) return false;

        Object controller = host.mediaController();
        if (controller == null) return false;
        Object activityObj = bridge.getStaticField(ui.launchActivityInstanceField);
        if (!(activityObj instanceof Activity)) {
            logger.log("没有可用的 LaunchActivity，无法回到全屏", null);
            return false;
        }
        Activity activity = (Activity) activityObj;

        Object photoViewer = bridge.invoke(null, host.photo.getInstance);
        if (photoViewer == null) return false;
        if (onOpened != null && host.isPhotoVisible(photoViewer)) return false;
        Object provider = bridge.newInstance(host.niagram1322
                ? "org.telegram.ui.PhotoViewer$g" : "org.telegram.ui.PhotoViewer$EmptyPhotoViewerProvider");
        if (provider == null || host.photo.openPhoto == null || host.photo.setParentActivity == null ||
                host.media.cleanupPlayer4 == null || host.media.playMessage == null) {
            logger.log("全屏转交所需的宿主接口不完整", null);
            return false;
        }

        Object alert = bridge.getStaticField(ui.alertInstanceField);
        if (onOpened == null && alert != null) {
            bridge.invokeNamed(alert, "dismiss");
        }

        final Object finalTarget = target;
        Runnable reopen = () -> {
            try {
                if (!session.isBackgroundActive()) return;
                MediaControllerAgent playback = new MediaControllerAgent(host, session, logger);
                Object playing = playback.playingMessage();
                boolean sameItem = playing != null && host.dialogIdOf(playing) == host.dialogIdOf(finalTarget) &&
                        host.messageIdOf(playing) == host.messageIdOf(finalTarget);
                boolean paused = sameItem && Boolean.TRUE.equals(bridge.getField(controller, host.media.isPausedField));
                bridge.invoke(photoViewer, host.photo.setParentActivity, activity, null, null);
                Object playlistObj = bridge.getField(controller, host.media.playlistField);
                List<Object> playlist = playlistObj instanceof List ? new ArrayList<>((List<?>) playlistObj) : Collections.singletonList(finalTarget);
                fullscreenPlaylist = new ArrayList<>(playlist);
                if (!sameItem) {
                    // PhotoViewer owns loading/streaming of the selected video. Do not
                    // tear down the old player while waiting for MediaController's download.
                    session.markBackgroundActive(false);
                    if (openPhoto(photoViewer, finalTarget, provider)) {
                        bridge.invoke(controller, host.media.cleanupPlayer4, true, true, false, false);
                        bridge.invoke(controller, host.media.resetGoingToShowMessageObject);
                        if (onOpened != null) onOpened.run();
                    } else {
                        session.markBackgroundActive(true);
                        logger.log("所选视频打开失败，保留原后台播放器", null);
                    }
                    return;
                }
                Object player = bridge.getField(controller, host.media.videoPlayerField);
                if (player == null) {
                    logger.log("所选视频播放器尚未就绪，保留后台播放", null);
                    return;
                }
                long position = playback.positionMs();
                if (host.niagram1322) session.markBackgroundActive(false);
                bridge.invoke(controller, host.media.cleanupPlayer4, true, true, false, true);
                if (openPhoto(photoViewer, finalTarget, provider) && (host.playerOf(photoViewer) == player ||
                        bridge.getField(photoViewer, "injectingVideoPlayer") == player)) {
                    // Original cleanupPlayer transfers the player but retains its
                    // reference for the surface handshake. Relinquish it after adoption.
                    if (host.playerOf(photoViewer) == player) bridge.setField(controller, host.media.videoPlayerField, null);
                    restoreViewerPlayback(photoViewer, player, finalTarget, paused);
                    bridge.invoke(controller, host.media.resetGoingToShowMessageObject);
                    if (onOpened != null) onOpened.run();
                } else {
                    logger.log("宿主拒绝接入全屏播放器，恢复后台播放", null);
                    bridge.setField(photoViewer, "injectingVideoPlayer", null);
                    if (host.playerOf(photoViewer) == player) {
                        bridge.setField(photoViewer, host.photo.videoPlayerField, null);
                    }
                    MediaControllerAgent recovery = playback;
                    recovery.startBackgroundPlayback(player, finalTarget, playlist, position, (p, m) -> {
                        if (host.niagram1322) {
                            bridge.setField(photoViewer, host.photo.videoPlayerField, p);
                            bridge.setField(photoViewer, host.photo.currentMessageField, m);
                            recovery.invokeViewerTransfer(photoViewer, p);
                            return bridge.getField(controller, host.media.videoPlayerField) == p;
                        }
                        bridge.invoke(controller, host.media.injectVideoPlayer, p, m);
                        return bridge.getField(controller, host.media.videoPlayerField) == p;
                    });
                    if (paused) recovery.pausePlayback();
                    recovery.postPlayStateChanged();
                }
            } catch (Throwable t) {
                logger.log("回到全屏失败", t);
            }
        };
        if (onOpened == null) mainHandler.postDelayed(reopen, REOPEN_DELAY_MS);
        else reopen.run();
        return true;
    }

    private void restoreViewerPlayback(Object viewer, Object player, Object target, boolean paused) {
        if (host.playerOf(viewer) != player) {
            mainHandler.postDelayed(() -> {
                if (host.playerOf(viewer) == player && host.messageOf(viewer) == target) {
                    restoreViewerPlayback(viewer, player, target, paused);
                }
            }, REOPEN_DELAY_MS);
            return;
        }
        bridge.setField(viewer, host.photo.manuallyPausedField, paused);
        if (paused) bridge.invoke(player, host.player.pause);
        else if (host.niagram1322) bridge.invoke(player, host.player.play);
        Method updateState = host.photo.updatePlayerState;
        int state = host.playbackStateOf(player);
        if (updateState != null && state >= 0) {
            if (updateState.getParameterTypes()[0] == int.class) bridge.invoke(viewer, updateState, state, !paused);
            else bridge.invoke(viewer, updateState, !paused, state);
        }
        bridge.setField(viewer, host.photo.manuallyPausedField, paused);
        Object container = bridge.getField(viewer, "containerView");
        if (container instanceof android.view.View) ((android.view.View) container).invalidate();
    }

    boolean jumpToMessageObject(Object messageObject) {
        if (messageObject == null) return false;
        long dialogId = host.dialogIdOf(messageObject);
        int messageId = host.messageIdOf(messageObject);
        if (dialogId == 0L || messageId == 0) return false;

        Object activityObj = bridge.getStaticField(ui.launchActivityInstanceField);
        if (!(activityObj instanceof Activity)) {
            logger.log("没有可用的 LaunchActivity，无法跳转消息", null);
            return false;
        }
        Activity activity = (Activity) activityObj;

        Object alert = bridge.getStaticField(ui.alertInstanceField);
        if (alert != null) {
            bridge.invokeNamed(alert, "dismiss");
        }

        mainHandler.postDelayed(() -> {
            try {
                Intent intent = NotificationDresser.createJumpIntent(host, activity, activity.getClass(), messageObject);
                if (intent == null) throw new IllegalStateException("Missing message destination");
                activity.startActivity(intent);
            } catch (Throwable t) {
                logger.log("跳转群组消息位置失败", t);
            }
        }, REOPEN_DELAY_MS);
        return true;
    }

    boolean jumpToMessage(Object cell) {
        Object target = bridge.invoke(cell, ui.cellGetMessageObject);
        return jumpToMessageObject(target);
    }

    private boolean openPhoto(Object photoViewer, Object message, Object provider) {
        long dialogId = host.dialogIdOf(message);
        Method opener = host.photo.openPhoto;
        if (opener == null || provider == null) {
            logger.log("找不到 PhotoViewer.openPhoto 兼容重载", null);
            return false;
        }

        if (host.niagram1322) {
            return Boolean.TRUE.equals(bridge.invoke(photoViewer, opener, message, null, dialogId, 0L, 0L, provider));
        }
        Object[] args = new Object[]{message, dialogId, 0L, 0L, provider, Boolean.TRUE};
        if (host.photo.openPhotoList != null) {
            List<Object> items = playlistFor(message);
            for (int index = 0; index < items.size(); index++) {
                if (host.messageIdOf(items.get(index)) == host.messageIdOf(message)
                        && host.dialogIdOf(items.get(index)) == dialogId) {
                    return Boolean.TRUE.equals(bridge.invoke(photoViewer, host.photo.openPhotoList,
                            new ArrayList<>(items), index, dialogId, 0L, 0L, provider));
                }
            }
        }
        return Boolean.TRUE.equals(bridge.invoke(photoViewer, opener, args));
    }
}
