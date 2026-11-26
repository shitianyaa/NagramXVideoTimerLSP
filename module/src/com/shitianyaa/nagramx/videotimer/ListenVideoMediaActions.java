package com.shitianyaa.nagramx.videotimer;

import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Looper;

import com.shitianyaa.nagramx.videotimer.core.Xp;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/** 向系统媒体卡片发布 Niagram 听视频队列的可用切换方向。 */
final class ListenVideoMediaActions {
    private static final long SKIP_ACTIONS = PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_SKIP_TO_NEXT;

    private final ListenVideoAgent listen;
    private final Map<MediaSession, PublishedState> sessions = new WeakHashMap<>();

    ListenVideoMediaActions(ListenVideoAgent listen) {
        this.listen = listen;
    }

    void install() {
        Method method = Xp.findMethod(MediaSession.class, "setPlaybackState", PlaybackState.class);
        if (Xp.hook(method, chain -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Looper.myLooper() != Looper.getMainLooper()) {
                return chain.proceed();
            }
            MediaSession session = (MediaSession) chain.getThisObject();
            PlaybackState original = (PlaybackState) chain.getArg(0);
            PublishedState published = sessions.get(session);
            if (published == null) {
                if (!isPlaybackSession(session.getController().getTag())) return chain.proceed();
                published = new PublishedState();
                sessions.put(session, published);
            }

            published.original = original;
            if (original == null) return chain.proceed();
            long actions = actionsFor(original);
            published.actions = actions;
            if (actions == original.getActions()) return chain.proceed();
            PlaybackState updated = new PlaybackState.Builder(original).setActions(actions).build();
            return chain.proceed(new Object[]{updated});
        }) != null) {
            listen.setQueueChangedListener(this::refresh);
        }
    }

    private long actionsFor(PlaybackState state) {
        long actions = state.getActions();
        int playbackState = state.getState();
        if (playbackState != PlaybackState.STATE_PLAYING && playbackState != PlaybackState.STATE_PAUSED
                && playbackState != PlaybackState.STATE_BUFFERING) return actions;
        return queueActions(actions);
    }

    long queueActions(long original) {
        Object current = listen.current();
        if (current == null) return original;
        long actions = original & ~SKIP_ACTIONS;
        if (listen.adjacent(current, false) != null) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (listen.adjacent(current, true) != null) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        return actions;
    }

    private void refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        // 宿主可能在队列初始化之前已发布状态，列表加载后也未必会再次发布。
        for (Map.Entry<MediaSession, PublishedState> entry : sessions.entrySet()) {
            MediaSession session = entry.getKey();
            PublishedState published = entry.getValue();
            if (session != null && published.original != null && published.actions != actionsFor(published.original)) {
                session.setPlaybackState(published.original);
            }
        }
    }

    static boolean isPlaybackSession(String tag) {
        return "TelegramMediaSession".equals(tag) || "telegramAudioPlayer".equals(tag);
    }

    private static final class PublishedState {
        PlaybackState original;
        long actions;
    }
}
