package com.shitianyaa.nagramx.videotimer;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

final class ListenVideoAgent {
    private static final int MENU_ID = 0x4E585656;

    private final HostProfile host;
    private final HostBridge bridge;
    private final MediaControllerAgent media;
    private final VideoBackgroundSession session;
    private final HostStrings strings;
    private final HostTheme theme;
    private final PhotoViewerAgent photo;
    private final PlayerUiAgent playerUi;
    private final HostBridge.Logger logger;
    private final TreeMap<Integer, Object> videos = new TreeMap<>();
    private final Set<Integer> failedVideos = new HashSet<>();
    private final SharedVideoMedia sharedMedia;
    private final Method nativeActive;
    private final Method getSpeed;
    private final Method setSpeed;
    private Object origin;
    private Object closingViewer;
    private long topic;
    private int oldestLoaded;
    private boolean endOfMedia;
    private boolean loadingMedia;
    private final List<Consumer<Boolean>> mediaCallbacks = new ArrayList<>();
    private ListenVideoPage page;
    private Runnable queueChanged = () -> {};

    ListenVideoAgent(HostProfile host, MediaControllerAgent media, VideoBackgroundSession session,
                     HostTheme theme, HostStrings strings, PhotoViewerAgent photo, PlayerUiAgent playerUi,
                     HostBridge.Logger logger) {
        this.host = host;
        this.bridge = host.bridge;
        this.media = media;
        this.session = session;
        this.theme = theme;
        this.strings = strings;
        this.photo = photo;
        this.playerUi = playerUi;
        this.logger = logger;
        sharedMedia = new SharedVideoMedia(bridge, host.niagram1322);
        Class<?> controller = host.mediaControllerClass;
        nativeActive = bridge.findMethod(controller, "isVideoBackgroundPlayback", 0);
        getSpeed = bridge.findMethod(controller, host.niagram1322 ? "x" : "getPlaybackSpeed", 1,
                m -> m.getParameterTypes()[0] == boolean.class && m.getReturnType() == float.class);
        setSpeed = bridge.findMethod(controller, host.niagram1322 ? "o0" : "setPlaybackSpeed", 2, m ->
                m.getParameterTypes()[host.niagram1322 ? 0 : 1] == float.class &&
                        m.getParameterTypes()[host.niagram1322 ? 1 : 0] == boolean.class);
    }

    View addMenuItem(Object viewer, Activity activity, Object menu, Method addSubItem) {
        int icon = activity.getResources().getIdentifier("msg_media", "drawable", activity.getPackageName());
        Object item = bridge.invoke(menu, addSubItem, MENU_ID, icon, strings.get(activity, "ListenVideo"));
        if (!(item instanceof View)) return null;
        View view = (View) item;
        bridge.invokeNamed(view, "setColors", 0xFFFAFAFA, 0xFFFAFAFA);
        bridge.invokeNamed(view, "setSelectorColor", 0x0FFFFFFF);
        view.setOnClickListener(v -> {
            bridge.invokeNamed(menu, "closeSubMenu");
            if (!start(viewer, activity)) photo.toast(activity, strings.get(activity, "ListenVideoFailed"));
        });
        return view;
    }

    private boolean start(Object viewer, Activity activity) {
        Object target = host.messageOf(viewer);
        if (!eligible(target) || host.playerOf(viewer) == null) return false;

        List<Object> shared = photo.videoPlaylistOf(viewer, target);
        if (host.hasCompleteNativeTimerUi()) {
            Object playing = bridge.invoke(host.playerOf(viewer), host.player.isPlaying);
            if (!Boolean.TRUE.equals(bridge.invoke(viewer, host.photo.nativeBackgroundStart, 0, 0))) return false;
            session.markBackgroundActive(true);
            if (Boolean.FALSE.equals(playing)) media.pausePlayback();
        } else {
            photo.preservePlayerOnClose(viewer, shared);
            if (!session.isBackgroundActive() || !sameMessage(target, media.playingMessage()) ||
                    host.playerOf(viewer) != null) return false;
        }
        sharedMedia.clear();
        videoLoadingReset(viewer);
        origin = target;
        videos.clear();
        failedVideos.clear();
        merge(shared);
        videos.put(host.messageIdOf(target), target);
        if (host.canManagePlaylist()) {
            media.applyPlaylist(media.controller(), queue(), target);
        }
        queueChanged.run();
        if (open(activity, () -> closeSourceViewer(viewer, target))) return true;
        closeSourceViewer(viewer, target);
        return false;
    }

    private void closeSourceViewer(Object viewer, Object target) {
        if (!sameMessage(target, host.messageOf(viewer))) return;
        closingViewer = viewer;
        try {
            bridge.invoke(viewer, host.photo.closePhoto, false, true);
        } finally {
            closingViewer = null;
        }
    }

    private void videoLoadingReset(Object viewer) {
        Object value = bridge.getField(viewer, "topicId");
        topic = value instanceof Number ? ((Number) value).longValue() : 0L;
        oldestLoaded = 0;
        endOfMedia = false;
        loadingMedia = false;
        mediaCallbacks.clear();
    }

    void endSession() {
        sharedMedia.clear();
        origin = null;
        videos.clear();
        failedVideos.clear();
        finishMediaLoad(false);
    }

    boolean isClosingViewer(Object viewer) { return closingViewer == viewer; }

    boolean owns(Object message) {
        return origin != null && sameConversation(origin, message) && videos.containsKey(host.messageIdOf(message));
    }

    boolean openFromMiniPlayer(Object view) {
        if (!(view instanceof View) || current() == null ||
                Boolean.TRUE.equals(bridge.getField(view, host.playerUi.isLocationField))) return false;
        Object style = bridge.getField(view, host.playerUi.currentStyleField);
        if (!(style instanceof Number) || ((Number) style).intValue() != host.playerUi.styleAudioPlayer) return false;
        Context context = ((View) view).getContext();
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return context instanceof Activity && open((Activity) context, null);
    }

    private boolean open(Activity activity, Runnable closeSource) {
        if (activity.isFinishing() || activity.isDestroyed() || current() == null) return false;
        if (page != null && page.isShowing()) return true;
        try {
            page = new ListenVideoPage(activity, this, host, theme, strings, logger);
            page.show(closeSource);
            return page.isShowing();
        } catch (RuntimeException e) {
            page = null;
            logger.log("打开听视频页面失败", e);
            return false;
        }
    }

    Object current() {
        Object current = media.playingMessage();
        boolean active = nativeActive == null ? session.isBackgroundActive()
                : Boolean.TRUE.equals(bridge.invoke(media.controller(), nativeActive));
        return active && origin != null && sameConversation(origin, current) && host.isVideoMessage(current)
                ? current : null;
    }

    void pageClosed(ListenVideoPage closed) {
        if (page == closed) page = null;
    }

    void setQueueChangedListener(Runnable listener) {
        queueChanged = listener;
    }

    private void mergeHostPlaylist() {
        Object playlist = bridge.getField(media.controller(), host.media.playlistField);
        if (playlist instanceof List) merge((List<?>) playlist);
    }

    List<Object> queue() {
        mergeHostPlaylist();
        return new ArrayList<>(videos.descendingMap().values());
    }

    private void merge(List<?> items) {
        for (Object item : items) {
            if (sameConversation(origin, item) && eligible(item)) {
                int id = host.messageIdOf(item);
                if (id != 0) videos.putIfAbsent(id, item);
            }
        }
    }

    private boolean eligible(Object item) {
        if (!host.isVideoMessage(item)
                || Boolean.TRUE.equals(bridge.invoke(item, host.message.isHiddenSensitive))
                || Boolean.TRUE.equals(bridge.invoke(item, host.message.isSecretMedia))) return false;
        Object owner = bridge.getField(item, host.message.messageOwnerField);
        Object messageMedia = bridge.getField(owner, "media");
        Object ttl = bridge.getField(messageMedia, "ttl_seconds");
        Object messageTtl = bridge.getField(owner, "ttl");
        return (!(ttl instanceof Number) || ((Number) ttl).intValue() <= 0)
                && (!(messageTtl instanceof Number) || ((Number) messageTtl).intValue() <= 0);
    }

    boolean canLoadMore() {
        return !endOfMedia && sharedMedia.available();
    }

    boolean isLoadingMedia() { return loadingMedia; }

    void loadMore(Consumer<Boolean> completed) {
        if (!canLoadMore() || current() == null) {
            if (completed != null) completed.accept(false);
            return;
        }
        Object account = bridge.getField(origin, host.message.currentAccountField);
        if (!(account instanceof Number)) {
            if (completed != null) completed.accept(false);
            return;
        }
        if (completed != null) mediaCallbacks.add(completed);
        if (loadingMedia) return;
        loadingMedia = true;
        if (!sharedMedia.load(((Number) account).intValue(), host.dialogIdOf(origin), topic, oldestLoaded, (items, end) -> {
            if (items != null) {
                int cursor = oldestLoaded;
                for (Object item : items) {
                    int id = host.messageIdOf(item);
                    if (id > 0 && (cursor == 0 || id < cursor)) cursor = id;
                }
                endOfMedia = end || cursor == oldestLoaded;
                oldestLoaded = cursor;
                merge(items);
                Object playing = current();
                if (playing != null && host.canManagePlaylist()) media.applyPlaylist(media.controller(), queue(), playing);
                queueChanged.run();
            }
            finishMediaLoad(items != null);
        })) {
            finishMediaLoad(false);
        }
    }

    private void finishMediaLoad(boolean success) {
        loadingMedia = false;
        List<Consumer<Boolean>> callbacks = new ArrayList<>(mediaCallbacks);
        mediaCallbacks.clear();
        for (Consumer<Boolean> callback : callbacks) callback.accept(success);
    }

    boolean sameMessage(Object a, Object b) {
        return a != null && (a == b || (sameConversation(a, b) && host.messageIdOf(a) == host.messageIdOf(b)));
    }

    private boolean sameConversation(Object a, Object b) {
        if (a == null || b == null) return false;
        if (a == b) return true;
        long dialog = host.dialogIdOf(a);
        Object account = bridge.getField(a, host.message.currentAccountField);
        return dialog != 0 && dialog == host.dialogIdOf(b) && account != null &&
                account.equals(bridge.getField(b, host.message.currentAccountField));
    }

    boolean select(Object target) {
        Object current = current();
        if (!sameConversation(current, target) || !eligible(target)) return false;
        if (sameMessage(current, target)) return true;
        long position = media.positionMs();
        boolean paused = media.isPaused();
        boolean accepted = media.play(target);
        if (accepted && sameMessage(target, current()) && media.hasSession()) {
            failedVideos.remove(host.messageIdOf(target));
            queueChanged.run();
            return true;
        }

        failedVideos.add(host.messageIdOf(target));
        Object playing = media.playingMessage();
        logger.log("听视频切换未建立播放会话：target=" + host.messageIdOf(target)
                + ", playing=" + host.messageIdOf(playing) + ", accepted=" + accepted, null);
        if (origin != null && session.isActive() && sameMessage(playing, current) && media.hasSession()) {
            session.markBackgroundActive(true);
        } else if (origin != null && session.isActive() && (playing == null || sameMessage(playing, target))) {
            media.seedProgress(current, position);
            if (media.play(current) && sameMessage(current, current())) {
                if (paused) media.pausePlayback();
            } else {
                logger.log("听视频恢复原视频失败：message=" + host.messageIdOf(current), null);
            }
        }
        queueChanged.run();
        return false;
    }

    boolean canSkip(boolean next) {
        return adjacent(current(), next) != null;
    }

    Object adjacent(Object current, boolean next) {
        if (current == null) return null;
        int id = host.messageIdOf(current);
        Map.Entry<Integer, Object> entry = next ? videos.higherEntry(id) : videos.lowerEntry(id);
        while (entry != null && failedVideos.contains(entry.getKey())) {
            int failed = entry.getKey();
            entry = next ? videos.higherEntry(failed) : videos.lowerEntry(failed);
        }
        return entry == null ? null : entry.getValue();
    }

    boolean skip(boolean next) {
        Object target = adjacent(current(), next);
        return target != null && select(target);
    }

    boolean paused() {
        return media.isPaused();
    }

    void toggle() {
        Object current = current();
        if (current == null) return;
        if (paused()) media.play(current);
        else media.pausePlayback();
    }

    long position() { return media.positionMs(); }
    long duration() { return media.durationMs(); }

    void seek(int progress) {
        Object current = current();
        if (current != null) media.seek(current, progress / 1000f);
    }

    boolean canSetSpeed() { return getSpeed != null && setSpeed != null; }

    float speed() {
        Object value = bridge.invoke(media.controller(), getSpeed, false);
        return value instanceof Number ? ((Number) value).floatValue() : 1f;
    }

    void setSpeed(float speed) {
        if (current() == null) return;
        if (host.niagram1322) bridge.invoke(media.controller(), setSpeed, speed, false);
        else bridge.invoke(media.controller(), setSpeed, false, speed);
    }

    String timerStatus(Context context) {
        if (!host.hasCompleteNativeTimerUi()) {
            if (session.getMode() == VideoBackgroundSession.MODE_AFTER_CURRENT) {
                return strings.get(context, "ListenVideoTimerAfterCurrent");
            }
            return session.getFormattedRemainingTime();
        }
        Object controller = media.controller();
        Object mode = bridge.invokeNamed(controller, "getVideoSleepTimerMode");
        if (Integer.valueOf(host.media.timerAfterCurrentMode).equals(mode)) {
            return strings.get(context, "ListenVideoTimerAfterCurrent");
        }
        if (!Integer.valueOf(host.media.timerDurationMode).equals(mode)) return "";
        Object remaining = bridge.invokeNamed(controller, "getVideoSleepTimerRemainingMs");
        if (!(remaining instanceof Number)) return "";
        return ListenVideoPage.time(Math.max(0L, ((Number) remaining).longValue() + 999L));
    }

    void openTimer(Activity activity) {
        Object current = current();
        if (current == null) return;
        Object controller = media.controller();
        boolean nativeTimer = host.hasCompleteNativeTimerUi();
        TimerState state = session.snapshot();
        if (nativeTimer) {
            Object mode = bridge.invokeNamed(controller, "getVideoSleepTimerMode");
            Object ms = bridge.invokeNamed(controller, "getVideoSleepTimerRemainingMs");
            int currentMode = mode instanceof Number ? ((Number) mode).intValue() : 0;
            int remaining = ms instanceof Number ? (int) ((((Number) ms).longValue() + 59_999L) / 60_000L) : 0;
            state = new TimerState(currentMode != 0, currentMode, remaining, remaining);
        }
        new VideoSleepTimerSheet(host, theme, strings, logger).show(activity, state, (mode, minutes) -> {
            Object playing = current();
            if (playing == null) return;
            if (nativeTimer) {
                bridge.invokeNamed(controller, "setVideoSleepTimerInternal", mode, minutes * 60_000L, playing);
            } else {
                session.arm(mode, minutes, host.messageIdOf(playing));
            }
        }, () -> {
            if (nativeTimer) bridge.invokeNamed(controller, "clearVideoSleepTimer");
            else session.disarm();
        });
    }

    void watch(Runnable onOpened) {
        Object current = current();
        if (current != null) playerUi.openInFullscreen(current, onOpened);
    }
}
