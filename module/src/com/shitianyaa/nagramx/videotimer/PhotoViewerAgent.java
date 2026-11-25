package com.shitianyaa.nagramx.videotimer;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * PhotoViewer 侧：视频菜单注入与后台播放启动。
 */
final class PhotoViewerAgent {

    private static final int MAX_INJECTION_ATTEMPTS = 5;
    private static final long INJECTION_RETRY_MS = 200L;
    private static final int MENU_TEXT_COLOR = 0xFFFAFAFA;
    private static final int MENU_SELECTOR_COLOR = 0x0FFFFFFF;
    private static final int ACCENT_COLOR = 0xFF73B4EC;
    private static final int ACCENT_SELECTOR_COLOR = 0x0F73B4EC;

    private static class MenuEntry {
        final View item;
        MenuEntry(View item) {
            this.item = item;
        }
    }

    private final HostProfile host;
    private final MediaControllerAgent media;
    private final VideoBackgroundSession session;
    private final HostStrings strings;
    private final PlayerUiAgent playerUi;
    private final HostBridge.Logger logger;
    private final HostBridge bridge;
    private final PhotoViewerMembers photo;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<Object, MenuEntry> injected = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Integer> attempts = Collections.synchronizedMap(new WeakHashMap<>());

    private volatile Object transferredPlayer;
    private ListenVideoAgent listen;

    PhotoViewerAgent(
            HostProfile host,
            MediaControllerAgent media,
            VideoBackgroundSession session,
            HostStrings strings,
            PlayerUiAgent playerUi,
            HostBridge.Logger logger
    ) {
        this.host = host;
        this.media = media;
        this.session = session;
        this.strings = strings;
        this.playerUi = playerUi;
        this.logger = logger != null ? logger : (msg, t) -> {};
        this.bridge = host.bridge;
        this.photo = host.photo;
    }

    void setListenVideo(ListenVideoAgent listen) {
        this.listen = listen;
    }

    Object getTransferredPlayer() {
        return transferredPlayer;
    }

    void scheduleMenuInjection(Object photoViewer) {
        mainHandler.post(() -> injectMenu(photoViewer));
    }

    private void injectMenu(Object photoViewer) {
        synchronized (injected) {
            if (injected.containsKey(photoViewer)) return;
        }

        Object videoMenu = bridge.getField(photoViewer, photo.videoItemField);
        Object activityObj = bridge.invoke(photoViewer, photo.getParentActivity);
        if (!(activityObj instanceof Activity)) activityObj = bridge.getField(photoViewer, photo.parentActivityField);
        if (videoMenu == null || !(activityObj instanceof Activity)) {
            retryInjection(photoViewer);
            return;
        }
        Activity activity = (Activity) activityObj;

        Method addSubItem = bridge.findMethod(videoMenu.getClass(), "addSubItem", 3, m -> {
            Class<?>[] p = m.getParameterTypes();
            return p[0] == int.class && p[1] == int.class && CharSequence.class.isAssignableFrom(p[2]);
        });
        if (addSubItem == null) {
            logger.log("ActionBarMenuItem.addSubItem 签名不匹配，放弃注入定时菜单", null);
            return;
        }

        View listenItem = listen == null ? null : listen.addMenuItem(photoViewer, activity, videoMenu, addSubItem);
        if (listenItem == null) return;
        synchronized (injected) {
            injected.put(photoViewer, new MenuEntry(listenItem));
        }
        synchronized (attempts) {
            attempts.remove(photoViewer);
        }
        hideNativeTimerItem(photoViewer);
        refreshMenuState(photoViewer);
        logger.log("已注入 PhotoViewer 听视频菜单", null);
    }

    void hideNativeTimerItem(Object viewer) {
        if (!injected.containsKey(viewer)) return;
        Object item = bridge.getField(viewer, photo.nativeTimerItemField);
        if (item instanceof View) ((View) item).setVisibility(View.GONE);
    }

    private void retryInjection(Object photoViewer) {
        int nextAttempt;
        synchronized (attempts) {
            Integer current = attempts.get(photoViewer);
            nextAttempt = (current != null ? current : 0) + 1;
            attempts.put(photoViewer, nextAttempt);
        }
        if (nextAttempt > MAX_INJECTION_ATTEMPTS) {
            synchronized (attempts) {
                attempts.remove(photoViewer);
            }
            logger.log("PhotoViewer 菜单多次重试仍未就绪，放弃本次注入", null);
            return;
        }
        mainHandler.postDelayed(() -> injectMenu(photoViewer), INJECTION_RETRY_MS * nextAttempt);
    }

    void refreshMenuState(Object photoViewer) {
        MenuEntry entry;
        synchronized (injected) {
            entry = photoViewer != null ? injected.get(photoViewer) : null;
        }
        if (entry == null || entry.item == null) return;
        Context context = entry.item.getContext();
        if (context == null) return;

        TimerState state = session.snapshot();
        String subtext = "";
        if (state.active) {
            if (state.mode == VideoBackgroundSession.MODE_AFTER_CURRENT) {
                subtext = strings.get(context, "VideoSleepTimerAfterCurrent");
            } else {
                subtext = session.getFormattedRemainingTime();
            }
        }
        bridge.invokeNamed(entry.item, "setSubtext", subtext.isEmpty() ? null : subtext);
        bridge.invokeNamed(
                entry.item,
                "setEnabledByColor",
                state.active,
                MENU_TEXT_COLOR,
                ACCENT_COLOR
        );
        bridge.invokeNamed(
                entry.item,
                "setSelectorColor",
                state.active ? ACCENT_SELECTOR_COLOR : MENU_SELECTOR_COLOR
        );
    }

    void refreshAllMenus() {
        List<Object> viewers;
        synchronized (injected) {
            viewers = new ArrayList<>(injected.keySet());
        }
        for (Object viewer : viewers) {
            refreshMenuState(viewer);
        }
    }

    private boolean transferToMediaController(Object photoViewer, Object player, Object message) {
        transferredPlayer = player;
        Object controller = media.controller();
        if (controller == null) return false;

        Method inject = host.media.injectVideoPlayer;
        if (inject != null) {
            // injectVideoPlayer synchronously broadcasts playback start while the
            // viewer is still visible. Chat listeners must not reclaim its surface.
            MessageIdentityMask.around(MessageIdentityMask.Spec.asMusicHidingVideo(message),
                    () -> bridge.invoke(controller, inject, player, message));
        } else if (photo.injectToMediaController != null) {
            media.invokeViewerTransfer(photoViewer, player);
        }
        if (bridge.getField(controller, host.media.videoPlayerField) != player) return false;

        bridge.setField(photoViewer, photo.videoPlayerField, null);
        bridge.setField(photoViewer, photo.playerInjectedField, true);
        bridge.setField(photoViewer, photo.isPlayingField, false);
        return true;
    }

    @SuppressWarnings("unchecked")
    List<Object> videoPlaylistOf(Object photoViewer, Object current) {
        Object imagesObj = bridge.getField(photoViewer, photo.imagesArrField);
        if (!(imagesObj instanceof List)) {
            return playerUi != null ? playerUi.playlistFor(current) : Collections.singletonList(current);
        }
        List<?> images = (List<?>) imagesObj;
        List<Object> result = new ArrayList<>();
        for (Object item : images) {
            if (item != null && host.isVideoMessage(item)) {
                result.add(item);
            }
        }
        return result.size() <= 1 && playerUi != null ? playerUi.playlistFor(current) : result;
    }

    void preservePlayerOnClose(Object photoViewer) {
        preservePlayerOnClose(photoViewer, null);
    }

    void preservePlayerOnClose(Object photoViewer, List<Object> playlist) {
        Object player = host.playerOf(photoViewer);
        Object message = host.messageOf(photoViewer);
        if (message != null && host.isVideoMessage(message) && player == null) {
            Object controller = media.controller();
            boolean accepted = media.play(message);
            if (accepted) {
                logger.log("视频加载转入后台：message=" + host.messageIdOf(message), null);
                session.markBackgroundActive(true);
                media.postPlayStateChanged();
                if (playerUi != null) playerUi.notifyPlaybackStarted();
            } else logger.log("视频加载转后台失败，保留定时设置", null);
            return;
        }
        if (player == null || message == null || !host.isVideoMessage(message)) {
            logger.log("关闭视频时无法转后台：player=" + (player != null) + ", message=" + (message != null), null);
            return;
        }

        Object controller = media.controller();
        if (controller == null) return;
        Object currentMediaVideo = host.bridge.getField(controller, host.media.videoPlayerField);
        if (currentMediaVideo == player && (!host.niagram1322 || media.playingMessage() != null)) {
            // A retained surface-handshake reference is not necessarily a media session.
            if (media.playingMessage() == null) {
                MessageIdentityMask.around(MessageIdentityMask.Spec.asMusicHidingVideo(message),
                        () -> bridge.invoke(controller, host.media.injectVideoPlayer, player, message));
            }
            // Original PhotoViewer.releasePlayer releases its own videoPlayer.
            // Ownership is detached by clearing that reference, not by fork flags.
            bridge.setField(photoViewer, photo.playerInjectedField, true);
            bridge.setField(photoViewer, photo.videoPlayerField, null);
            bridge.setField(photoViewer, photo.isPlayingField, false);

            session.markBackgroundActive(true);
            media.startMusicPlayerService();
            media.postPlayStateChanged();
            if (playerUi != null) {
                playerUi.notifyPlaybackStarted();
            }
            return;
        }

        Object isPlaying = host.bridge.invoke(player, host.player.isPlaying);
        boolean paused = !Boolean.TRUE.equals(isPlaying);

        Object posVal = host.bridge.invoke(player, host.player.getCurrentPosition);
        long position = 0L;
        if (posVal instanceof Number) {
            long p = ((Number) posVal).longValue();
            if (p > 0L && p != MediaControllerAgent.TIME_UNSET) position = p;
        }

        host.bridge.invoke(player, host.player.setLooping, false);
        boolean started = media.startBackgroundPlayback(
                player,
                message,
                playlist != null ? playlist : videoPlaylistOf(photoViewer, message),
                position,
                (transferPlayer, transferMessage) -> transferToMediaController(photoViewer, transferPlayer, transferMessage)
        );
        if (started) {
            logger.log("视频转入后台成功：message=" + host.messageIdOf(message) + ", timer=" + session.isArmed(), null);
            session.markBackgroundActive(true);
            bridge.setField(photoViewer, photo.playerInjectedField, true);
            bridge.setField(photoViewer, photo.videoPlayerField, null);
            bridge.setField(photoViewer, photo.isPlayingField, false);

            if (paused) {
                host.bridge.setField(controller, host.media.isPausedField, true);
                Object vp = host.bridge.getField(controller, host.media.videoPlayerField);
                if (vp != null) {
                    host.bridge.invoke(vp, host.player.pause);
                }
            }
            media.postPlayStateChanged();
            if (playerUi != null) {
                playerUi.notifyPlaybackStarted();
            }
        }
    }

    private static final int PROGRESS_NONE = -1;
    private static final int PROGRESS_EMPTY = 0;
    private static final int PROGRESS_CANCEL = 1;
    private static final int PROGRESS_LOAD = 2;
    private static final int PROGRESS_PLAY = 3;
    private static final int PROGRESS_PAUSE = 4;

    boolean isPlayerAlive(Object photoViewer) {
        if (photoViewer == null) return false;
        Object player = host.playerOf(photoViewer);
        Object message = host.messageOf(photoViewer);
        return player != null && message != null && host.isVideoMessage(message);
    }

    void ensurePausePlayButtonVisible(Object photoViewer) {
        if (photoViewer == null) return;
        try {
            Object player = host.playerOf(photoViewer);
            if (player == null) return;
            if (host.playbackStateOf(player) != MediaControllerAgent.EXO_STATE_READY) return;
            Object[] progressViews = (Object[]) bridge.getField(photoViewer, "photoProgressViews");
            if (progressViews != null && progressViews.length > 0 && progressViews[0] != null) {
                boolean isPlaying = false;
                if (player != null) {
                    Object isP = bridge.invoke(player, host.player.isPlaying);
                    isPlaying = Boolean.TRUE.equals(isP);
                }
                int targetState = isPlaying ? PROGRESS_PAUSE : PROGRESS_PLAY;
                if (host.niagram1322) {
                    if (isPlaying) return;
                    Object state = bridge.getField(progressViews[0], "backgroundState");
                    if (!Integer.valueOf(targetState).equals(state)) {
                        bridge.invokeNamed(progressViews[0], "l", targetState, false, false);
                    }
                    bridge.invokeNamed(progressViews[0], "m", 1.0f, 1, false);
                } else {
                    bridge.invokeNamed(progressViews[0], "setBackgroundState", targetState, false, true);
                    bridge.invokeNamed(progressViews[0], "setIndexedAlpha", 1, 1.0f, false);
                }
            }
        } catch (Throwable t) {
            logger.log("更新视频播放按钮失败", t);
        }
    }

    void onBackgroundSessionEnded() {
        transferredPlayer = null;
        session.markBackgroundActive(false);
        refreshAllMenus();
    }

    void toast(Context context, String text) {
        mainHandler.post(() -> {
            try {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                logger.log("显示提示失败", t);
            }
        });
    }
}
