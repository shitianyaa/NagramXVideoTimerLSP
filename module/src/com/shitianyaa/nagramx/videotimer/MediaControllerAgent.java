package com.shitianyaa.nagramx.videotimer;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MediaController 侧操作与 Hook。
 */
final class MediaControllerAgent {

    public static final int EXO_STATE_READY = 3;
    public static final int EXO_STATE_ENDED = 4;
    public static final long TIME_UNSET = Long.MIN_VALUE + 1;
    private static final ThreadLocal<Object> TRANSFERRING_VIEWER_PLAYER = new ThreadLocal<>();

    public interface PlayerTransfer {
        boolean transfer(Object videoPlayer, Object message);
    }

    private final HostProfile host;
    private final VideoBackgroundSession session;
    private final HostBridge.Logger logger;
    private final HostBridge bridge;
    private final MediaControllerMembers media;
    private final VideoPlayerMembers player;

    MediaControllerAgent(HostProfile host, VideoBackgroundSession session, HostBridge.Logger logger) {
        this.host = host;
        this.session = session;
        this.logger = logger != null ? logger : (msg, t) -> {};
        this.bridge = host.bridge;
        this.media = host.media;
        this.player = host.player;
    }

    Object controller() {
        return host.mediaController();
    }

    Object playingMessage() {
        Object controller = controller();
        return media.getPlayingMessageObject == null ? bridge.getField(controller, media.playingMessageField)
                : bridge.invoke(controller, media.getPlayingMessageObject);
    }

    Object activePlayer() {
        return bridge.getField(controller(), media.videoPlayerField);
    }

    boolean hasSession() {
        return playingMessage() != null && (activePlayer() != null ||
                Boolean.TRUE.equals(bridge.getField(controller(), media.downloadingCurrentMessageField)));
    }

    boolean isPaused() {
        Object val = bridge.getField(controller(), media.isPausedField);
        return (val instanceof Boolean) ? (Boolean) val : true;
    }

    boolean isActuallyPlaying() {
        if (isPaused()) return false;
        Object current = activePlayer();
        if (current == null) return false;
        Object playing = bridge.invoke(current, player.isPlaying);
        if (!Boolean.TRUE.equals(playing)) return false;
        int state = host.playbackStateOf(current);
        return state < 0 || state == EXO_STATE_READY;
    }

    boolean hasReachedEnd() {
        Object current = activePlayer();
        if (current == null) return false;
        return host.playbackStateOf(current) == EXO_STATE_ENDED;
    }

    long durationMs() {
        Object current = activePlayer();
        Object fromPlayer = bridge.invoke(current, player.getDuration);
        if (fromPlayer instanceof Number) {
            long d = ((Number) fromPlayer).longValue();
            if (d > 0L && d != TIME_UNSET) return d;
        }
        Object message = playingMessage();
        if (message == null) return 0L;
        Object seconds = bridge.invoke(message, host.message.getDuration);
        if (seconds instanceof Number) {
            return (long) (((Number) seconds).doubleValue() * 1000.0);
        }
        return 0L;
    }

    long positionMs() {
        Object current = activePlayer();
        if (current == null) return 0L;
        Object pos = bridge.invoke(current, player.getCurrentPosition);
        if (pos instanceof Number) {
            long p = ((Number) pos).longValue();
            return (p < 0L || p == TIME_UNSET) ? 0L : p;
        }
        return 0L;
    }

    void pausePlayback() {
        Object c = controller();
        if (c == null) return;
        Object message = playingMessage();
        if (message == null) return;
        if (host.niagram1322) bridge.invoke(c, media.pauseMessage, message, true);
        else bridge.invoke(c, media.pauseMessage, message);
    }

    boolean play(Object message) {
        Object controller = controller();
        return Boolean.TRUE.equals(host.niagram1322 ? bridge.invoke(controller, media.playMessage, message)
                : bridge.invoke(controller, media.playMessage, message, false));
    }

    void seek(Object message, float fraction) {
        if (host.niagram1322) bridge.invoke(controller(), media.seekToProgress, fraction, message);
        else bridge.invoke(controller(), media.seekToProgress, message, fraction);
    }

    boolean isTransferringViewerPlayer(Object player) {
        return player != null && TRANSFERRING_VIEWER_PLAYER.get() == player;
    }

    void invokeViewerTransfer(Object viewer, Object player) {
        if (host.niagram1322) TRANSFERRING_VIEWER_PLAYER.set(player);
        try {
            bridge.invoke(viewer, host.photo.injectToMediaController);
        } finally {
            if (host.niagram1322) TRANSFERRING_VIEWER_PLAYER.remove();
        }
    }

    boolean startBackgroundPlayback(
            Object videoPlayer,
            Object message,
            List<Object> playlist,
            long positionMs,
            PlayerTransfer transfer
    ) {
        Object c = controller();
        if (c == null) return false;
        Object originalDelegate = bridge.getField(videoPlayer, "delegate");
        Object originalTexture = bridge.getField(videoPlayer, "textureView");
        Object originalSurface = bridge.getField(videoPlayer, "surfaceView");
        boolean accepted = false;
        try {
            session.markBackgroundActive(true);

            // 1. 摘掉画面输出
            bridge.invoke(videoPlayer, player.setDelegate, (Object) null);
            bridge.invoke(videoPlayer, player.setSurfaceView, (Object) null);
            bridge.invoke(videoPlayer, player.setTextureView, (Object) null);

            // 2. 清理旧播放器
            Object existingVideo = bridge.getField(c, media.videoPlayerField);
            Object existingAudio = bridge.getField(c, media.audioPlayerField);
            if (existingAudio != null || (existingVideo != null && existingVideo != videoPlayer)) {
                bridge.invoke(c, media.cleanupPlayer2, false, false);
            }

            // 3. 断开渲染容器
            bridge.setField(c, media.currentTextureViewField, null);
            bridge.setField(c, media.currentAspectRatioFrameLayoutField, null);
            bridge.setField(c, media.currentTextureViewContainerField, null);
            bridge.setField(c, media.currentAspectRatioReadyField, false);
            closeRoundVideoOverlay(c);

            // 4. 回种进度
            seedProgress(message, positionMs);

            // 5. 走音乐音频流
            bridge.invoke(videoPlayer, player.setStreamType, AudioManager.STREAM_MUSIC);

            // 6. 注入播放器
            if (transfer == null || !transfer.transfer(videoPlayer, message)) {
                logger.log("播放器转交失败，后台播放启动失败", null);
                return false;
            }
            if (bridge.getField(c, media.videoPlayerField) != videoPlayer) {
                logger.log("MediaController 未接受注入的播放器，后台播放启动失败", null);
                return false;
            }
            accepted = true;
            bridge.setField(c, media.isPausedField, false);

            // 7. 写入播放列表
            applyPlaylist(c, playlist, message);

            startMusicPlayerService();
            return true;
        } finally {
            if (!accepted) {
                session.markBackgroundActive(false);
                bridge.invoke(videoPlayer, player.setDelegate, originalDelegate);
                bridge.invoke(videoPlayer, player.setSurfaceView, originalSurface);
                bridge.invoke(videoPlayer, player.setTextureView, originalTexture);
            }
        }
    }

    void stopBackgroundPlayback() {
        Object c = controller();
        if (c == null) return;
        bridge.invoke(c, media.cleanupPlayer2, true, true);
    }

    void seedProgress(Object message, long positionMs) {
        Object durationObj = bridge.invoke(message, host.message.getDuration);
        double durationSeconds = (durationObj instanceof Number) ? ((Number) durationObj).doubleValue() : 0.0;
        long durationMs = (long) (durationSeconds * 1000.0);
        long bounded = positionMs;
        if (positionMs <= 0L) {
            bounded = 0L;
        } else if (durationMs > 0L) {
            bounded = Math.min(positionMs, Math.max(0L, durationMs - 1L));
        }
        int boundedInt = (int) Math.min((long) Integer.MAX_VALUE, bounded);

        bridge.setField(message, host.message.audioProgressMsField, boundedInt);
        float progress = (durationMs > 0L) ? ((float) bounded / durationMs) : 0f;
        bridge.setField(message, host.message.audioProgressField, progress);
        bridge.setField(message, host.message.audioProgressSecField, (int) (bounded / 1000L));
    }

    @SuppressWarnings("unchecked")
    void applyPlaylist(Object controller, List<Object> playlist, Object current) {
        if (!host.canManagePlaylist()) return;

        Object listObj = bridge.getField(controller, media.playlistField);
        if (!(listObj instanceof List)) return;
        List<Object> list = (List<Object>) listObj;

        Object mapObj = bridge.getField(controller, media.playlistMapField);
        if (!(mapObj instanceof Map)) return;
        Map<Integer, Object> map = (Map<Integer, Object>) mapObj;

        list.clear();
        map.clear();
        Object shuffled = bridge.getField(controller, media.shuffledPlaylistField);
        if (shuffled instanceof List) {
            ((List<?>) shuffled).clear();
        }

        List<Object> ordered = new ArrayList<>();
        if (playlist != null) {
            for (Object candidate : playlist) {
                if (host.isVideoMessage(candidate) && !containsRef(ordered, candidate)) {
                    ordered.add(candidate);
                }
            }
        }
        if (!containsRef(ordered, current)) {
            ordered.add(current);
        }

        for (Object item : ordered) {
            list.add(item);
            Object idVal = bridge.invoke(item, host.message.getId);
            if (idVal instanceof Number) {
                map.put(((Number) idVal).intValue(), item);
            }
        }

        bridge.setField(controller, media.forceLoopCurrentPlaylistField, false);
        bridge.invoke(controller, media.sortPlaylist);
        bridge.setField(controller, media.currentPlaylistNumField, indexOfRef(list, current));
        if (isShuffleEnabled()) {
            bridge.invoke(controller, media.buildShuffledPlayList);
        }
    }

    private boolean containsRef(List<Object> list, Object target) {
        for (Object item : list) {
            if (item == target) return true;
        }
        return false;
    }

    private int indexOfRef(List<Object> list, Object target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) return i;
        }
        return 0;
    }

    private boolean isShuffleEnabled() {
        Class<?> sharedConfig = bridge.loadClass("org.telegram.messenger.SharedConfig");
        Object val = bridge.getStaticField(sharedConfig, "shuffleMusic");
        return (val instanceof Boolean) ? (Boolean) val : false;
    }

    boolean skipVideoChronologically(boolean next) {
        if (isShuffleEnabled()) return false;
        Object c = controller();
        Object current = playingMessage();
        Object listObj = bridge.getField(c, media.playlistField);
        if (!(listObj instanceof List) || current == null) return false;
        List<Object> videos = new ArrayList<>();
        for (Object item : (List<?>) listObj) {
            if (host.dialogIdOf(item) == host.dialogIdOf(current) && host.isVideoMessage(item)) videos.add(item);
        }
        // The host's chat history orders messages by ID. Music's default next
        // traverses it backwards; video next follows newer messages instead.
        videos.sort((a, b) -> Integer.compare(host.messageIdOf(a), host.messageIdOf(b)));
        for (int index = 0; index < videos.size(); index++) {
            if (host.messageIdOf(videos.get(index)) != host.messageIdOf(current)) continue;
            if (videos.size() < 2) return true;
            Object target = videos.get((index + (next ? 1 : videos.size() - 1)) % videos.size());
            if (!play(target)) {
                logger.log("通知切换视频失败：message=" + host.messageIdOf(target), null);
            }
            return true;
        }
        return false;
    }

    void closeRoundVideoOverlay(Object controller) {
        if (controller == null) return;
        Object overlay = bridge.getField(controller, media.pipRoundVideoViewField);
        if (overlay != null) {
            bridge.invokeNamed(overlay, host.niagram1322 ? "k" : "close", false);
        }
        bridge.setField(controller, media.pipRoundVideoViewField, null);
        if (host.niagram1322) bridge.setField(controller, media.pipSwitchingStateField, (byte) 0);
        else bridge.setField(controller, media.pipSwitchingStateField, 0);
    }

    void startMusicPlayerService() {
        Class<?> serviceClass = host.musicPlayerServiceClass;
        if (serviceClass == null) {
            logger.log("无法启动视频通知：缺少 MusicPlayerService", null);
            return;
        }
        Context ctx = host.getAppContext();
        if (!(ctx instanceof Context)) {
            logger.log("无法启动视频通知：应用 Context 不可用", null);
            return;
        }
        try {
            android.content.ComponentName started = ((Context) ctx).startService(new Intent((Context) ctx, serviceClass));
            logger.log("启动视频通知服务：" + (started != null ? started.getClassName() : "系统未返回服务组件"), null);
        } catch (Throwable t) {
            logger.log("启动 MusicPlayerService 失败", t);
        }
    }

    void postPlayStateChanged() {
        Object message = playingMessage();
        if (message == null) return;
        Object accountVal = bridge.getField(message, host.message.currentAccountField);
        if (!(accountVal instanceof Number)) return;
        int account = ((Number) accountVal).intValue();

        Object idVal = bridge.invoke(message, host.message.getId);
        if (!(idVal instanceof Number)) return;
        int id = ((Number) idVal).intValue();

        Class<?> nc = bridge.loadClass("org.telegram.messenger.NotificationCenter");
        Object instance = bridge.invokeStatic(nc, host.niagram1322 ? "g" : "getInstance", account);
        if (instance == null) return;
        Object eventVal = bridge.getStaticField(nc, host.niagram1322 ? "g2" : "messagePlayingPlayStateChanged");
        if (!(eventVal instanceof Number)) return;
        int event = ((Number) eventVal).intValue();

        bridge.invokeNamed(instance, host.niagram1322 ? "l" : "postNotificationName", event, new Object[]{id});
    }
}
