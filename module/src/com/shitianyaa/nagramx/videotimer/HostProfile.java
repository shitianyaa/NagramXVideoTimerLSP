package com.shitianyaa.nagramx.videotimer;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.view.SurfaceView;
import android.view.TextureView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * 宿主 PhotoViewer 成员。
 */
final class PhotoViewerMembers {
    final Method getInstance;
    final Method hasInstance;
    final Method setParentActivity;
    final Method getParentActivity;
    final Field parentActivityField;
    final Method closePhoto;
    final Method isVisible;
    final Method playVideoOrWeb;
    final Method pauseVideoOrWeb;
    final Method getVideoPlayer;
    final Method getCurrentMessageObject;
    final Method injectToMediaController;
    final Method openPhoto;
    final Method openPhotoList;
    final Method setImageIndex;
    final Method onActionClick;
    final Method playOrStopAnimatedStickers;
    final Method seekAnimatedStickersTo;

    final Field videoItemField;
    final Field loopItemField;
    final Field videoPlayerField;
    final Field currentMessageField;
    final Field imagesArrField;
    final Field playerLoopingField;
    final Field manuallyPausedField;
    final Field isPlayingField;
    final Field playerInjectedField;
    final Field activityContextField;
    final Field isVisibleField;

    final Method nativeBackgroundStart;
    final Method nativeTimerSheet;
    final Field nativeTimerItemField;
    final Method updatePlayerState;

    PhotoViewerMembers(HostBridge bridge, Class<?> type, NiagramXNames.PhotoViewer names) {
        this(bridge, type, names, false);
    }

    PhotoViewerMembers(HostBridge bridge, Class<?> type, NiagramXNames.PhotoViewer names, boolean niagram1322) {
        Field messageField = bridge.findField(type, "currentMessageObject");
        Class<?> messageType = messageField == null ? null : messageField.getType();
        getInstance = bridge.findMethodOrShape(type, niagram1322 ? "p7" : HostBridge.pickName(names != null ? names.getInstance : null, "getInstance"), 0,
                m -> Modifier.isStatic(m.getModifiers()) && m.getReturnType() == type && !m.isSynthetic());
        hasInstance = bridge.findMethod(type, "hasInstance", 0, m -> Modifier.isStatic(m.getModifiers()));
        setParentActivity = bridge.findMethodOrShape(type, niagram1322 ? "Cb" : HostBridge.pickName(names != null ? names.setParentActivity : null, "setParentActivity"), 3, m ->
                m.getParameterTypes().length > 0 && m.getParameterTypes()[0] == Activity.class);
        getParentActivity = niagram1322 ? null : bridge.findMethodOrShape(type, HostBridge.pickName(names != null ? names.getParentActivity : null, "getParentActivity"), 0, m ->
                m.getParameterCount() == 0 && Activity.class.isAssignableFrom(m.getReturnType()));
        parentActivityField = bridge.findField(type, "parentActivity");
        closePhoto = bridge.findMethodOrShape(type, niagram1322 ? "s6" : HostBridge.pickName(names != null ? names.closePhoto : null, "closePhoto"), 2, m -> {
            Class<?>[] p = m.getParameterTypes();
            return p.length == 2 && p[0] == boolean.class && p[1] == boolean.class;
        });
        isVisible = bridge.findMethod(type, "isVisible", 0);
        playVideoOrWeb = bridge.findMethodOrShape(type, HostBridge.pickName(names != null ? names.playVideoOrWeb : null, "playVideoOrWeb"), 0,
                m -> m.getParameterTypes().length == 0 && m.getReturnType() == void.class);
        pauseVideoOrWeb = bridge.findMethodOrShape(type, HostBridge.pickName(names != null ? names.pauseVideoOrWeb : null, "pauseVideoOrWeb"), 0,
                m -> m.getParameterTypes().length == 0 && m.getReturnType() == void.class);
        getVideoPlayer = bridge.findMethodOrShape(type, niagram1322 ? "C7" : HostBridge.pickName(names != null ? names.getVideoPlayer : null, "getVideoPlayer"), 0,
                m -> m.getParameterTypes().length == 0 && !m.getReturnType().isPrimitive());
        getCurrentMessageObject = bridge.findMethod(type, "getCurrentMessageObject", 0);
        injectToMediaController = bridge.findMethodOrShape(type,
                niagram1322 ? "N7" : HostBridge.pickName(names != null ? names.injectVideoPlayerToMediaController : null, "injectVideoPlayerToMediaController"), 0,
                m -> m.getParameterTypes().length == 0 && m.getReturnType() == void.class);
        openPhoto = bridge.findMethodOrShape(type, niagram1322 ? "Sa" : HostBridge.pickName(names != null ? names.openPhotoMessage : null, "openPhoto"), 6, m -> {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 6 || (niagram1322
                    ? p[0] != messageType : !p[0].getName().endsWith(".MessageObject"))) return false;
            // 原版 NagramX 的第 2 参是 dialogId(long)，NiagramX 在它前面插了 ChatActivity
            return p[1] == long.class || p[1].getName().endsWith(".ChatActivity");
        });
        openPhotoList = bridge.findMethodOrShape(type, HostBridge.pickName(names != null ? names.openPhotoList : null, "openPhoto"), 6, m -> {
            Class<?>[] p = m.getParameterTypes();
            return p.length == 6 && p[0] == ArrayList.class && p[1] == int.class;
        });
        setImageIndex = bridge.findMethodOrShape(type, HostBridge.pickName(names != null ? names.setImageIndex : null, "setImageIndex"), 1,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == int.class);
        onActionClick = bridge.findMethodOrShape(type, HostBridge.pickName(names != null ? names.onActionClick : null, "onActionClick"), 1,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == boolean.class);

        playOrStopAnimatedStickers = bridge.findMethodOrShape(type,
                HostBridge.pickName(names != null ? names.playOrStopAnimatedStickers : null, "playOrStopAnimatedStickers"), 1,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == boolean.class);
        seekAnimatedStickersTo = bridge.findMethodOrShape(type,
                HostBridge.pickName(names != null ? names.seekAnimatedStickersTo : null, "seekAnimatedStickersTo"), 1,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == long.class);

        videoItemField = bridge.findField(type, "videoItem");
        loopItemField = bridge.findField(type, "loopItem");
        videoPlayerField = bridge.findField(type, "videoPlayer");
        currentMessageField = bridge.findField(type, "currentMessageObject");
        imagesArrField = bridge.findField(type, "imagesArr");
        playerLoopingField = bridge.findField(type, "playerLooping");
        manuallyPausedField = bridge.findField(type, "manuallyPaused");
        isPlayingField = bridge.findField(type, "isPlaying");
        playerInjectedField = bridge.findField(type, "playerInjected");
        activityContextField = bridge.findField(type, "activityContext");
        isVisibleField = bridge.findField(type, "isVisible");

        nativeBackgroundStart = bridge.findMethod(type, "startVideoBackgroundPlayback", 2, m -> {
            Class<?>[] p = m.getParameterTypes();
            return m.getReturnType() == boolean.class && p.length == 2 && p[0] == int.class && p[1] == int.class;
        });
        nativeTimerSheet = bridge.findMethod(type, "showVideoSleepTimerSheet", 0);
        nativeTimerItemField = bridge.findField(type, "videoSleepTimerItem");
        updatePlayerState = bridge.findMethodOrShape(type, niagram1322 ? "Cc" : HostBridge.pickName(names != null ? names.updatePlayerState : null, "updatePlayerState"), 2, m -> {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() != void.class || p.length != 2) return false;
            // 原版是 (boolean, int)；NiagramX 被 R8 重排为 (int, boolean)
            return (p[0] == boolean.class && p[1] == int.class) || (p[0] == int.class && p[1] == boolean.class);
        });
    }
}

/**
 * 宿主 MediaController 成员。
 */
final class MediaControllerMembers {
    final Method getInstance;
    final Method injectVideoPlayer;
    final Method cleanupPlayer4;
    final Method cleanupPlayer2;
    final Method playMessage;
    final Method pauseMessage;
    final Method getPlayingMessageObject;
    final Method getPlaylist;
    final Method isMessagePaused;
    final Method playNextMessage;
    final Method playPreviousMessage;
    final Method playNextMessageWithoutOrder;
    final Method clearPlaylist;
    final Method sortPlaylist;
    final Method buildShuffledPlayList;
    final Method updateVideoState;
    final Method canStartMusicPlayerService;
    final Method setCurrentVideoVisible;
    final List<Method> setTextureView;
    final Method getDuration;
    final Method getProgressMs;
    final Method seekToProgress;
    final Method seekToProgressMs;
    final Method isSamePlayingMessage;
    final Method resetGoingToShowMessageObject;

    final Field videoPlayerField;
    final Field audioPlayerField;
    final Field isPausedField;
    final Field downloadingCurrentMessageField;
    final Field playingMessageField;
    final Field playlistField;
    final Field playlistMapField;
    final Field shuffledPlaylistField;
    final Field currentPlaylistNumField;
    final Field forceLoopCurrentPlaylistField;
    final Field currentTextureViewField;
    final Field currentAspectRatioFrameLayoutField;
    final Field currentTextureViewContainerField;
    final Field currentAspectRatioReadyField;
    final Field pipRoundVideoViewField;
    final Field pipSwitchingStateField;
    final Field baseActivityField;

    final int timerDurationMode;
    final int timerAfterCurrentMode;

    MediaControllerMembers(HostBridge bridge, Class<?> type) {
        this(bridge, type, false);
    }

    MediaControllerMembers(HostBridge bridge, Class<?> type, boolean niagram1322) {
        Class<?> messageType = bridge.loadClass("org.telegram.messenger.MessageObject");
        getInstance = bridge.findMethodOrShape(type, niagram1322 ? "w" : "getInstance", 0,
                m -> Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && m.getReturnType() == type);
        injectVideoPlayer = bridge.findMethod(type, "injectVideoPlayer", 2, m ->
                !Modifier.isStatic(m.getModifiers()) &&
                        // NiagramX 把 VideoPlayer 搬成默认包短名，不能按类名后缀判断，改用第二参定位
                        m.getParameterTypes()[1].getName().endsWith(".MessageObject"));
        cleanupPlayer4 = bridge.findMethod(type, niagram1322 ? "l" : "cleanupPlayer", 4, m -> {
            for (Class<?> p : m.getParameterTypes()) {
                if (p != boolean.class) return false;
            }
            return true;
        });
        cleanupPlayer2 = bridge.findMethod(type, niagram1322 ? "k" : "cleanupPlayer", 2, m -> {
            for (Class<?> p : m.getParameterTypes()) {
                if (p != boolean.class) return false;
            }
            return true;
        });
        playMessage = niagram1322 ? bridge.findMethod(type, "R", 1, m -> m.getReturnType() == boolean.class)
                : bridge.findMethod(type, "playMessage", 2, m ->
                m.getParameterTypes()[0].getName().endsWith(".MessageObject") &&
                        m.getParameterTypes()[1] == boolean.class);
        pauseMessage = niagram1322 ? bridge.findMethod(type, "Q", 2, m -> m.getReturnType() == boolean.class)
                : bridge.findMethod(type, "pauseMessage", 1, m ->
                m.getParameterTypes()[0].getName().endsWith(".MessageObject"));
        getPlayingMessageObject = bridge.findMethod(type, "getPlayingMessageObject", 0);
        getPlaylist = bridge.findMethod(type, "getPlaylist", 0);
        isMessagePaused = bridge.findMethod(type, niagram1322 ? "C" : "isMessagePaused", 0);
        playNextMessage = bridge.findMethod(type, niagram1322 ? "T" : "playNextMessage", niagram1322 ? 1 : 0);
        playPreviousMessage = bridge.findMethod(type, niagram1322 ? "U" : "playPreviousMessage", 0);
        playNextMessageWithoutOrder = bridge.findMethod(type, "playNextMessageWithoutOrder", 1, m ->
                m.getParameterTypes()[0] == boolean.class);
        clearPlaylist = bridge.findMethod(type, "clearPlaylist", 0);
        sortPlaylist = bridge.findMethod(type, "sortPlaylist", 0);
        buildShuffledPlayList = bridge.findMethod(type, "buildShuffledPlayList", 0);
        updateVideoState = bridge.findMethod(type, niagram1322 ? "L0" : "updateVideoState", 5, m -> {
            Class<?>[] p = m.getParameterTypes();
            return (niagram1322 ? p[0] == messageType : p[0].getName().endsWith(".MessageObject")) &&
                    p[1] == int[].class &&
                    p[2] == boolean.class &&
                    p[3] == boolean.class &&
                    p[4] == int.class;
        });
        canStartMusicPlayerService = bridge.findMethod(type, niagram1322 ? "c" : "canStartMusicPlayerService", 0, m ->
                m.getReturnType() == boolean.class);
        setCurrentVideoVisible = bridge.findMethod(type, niagram1322 ? "m0" : "setCurrentVideoVisible", 1, m ->
                m.getParameterTypes()[0] == boolean.class);

        List<Method> tvList = new ArrayList<>();
        Method tv4 = bridge.findMethod(type, niagram1322 ? "r0" : "setTextureView", 4, m ->
                TextureView.class.isAssignableFrom(m.getParameterTypes()[0]));
        if (tv4 != null) tvList.add(tv4);
        Method tv5 = bridge.findMethod(type, "setTextureView", 5, m ->
                TextureView.class.isAssignableFrom(m.getParameterTypes()[0]));
        if (tv5 != null) tvList.add(tv5);
        setTextureView = Collections.unmodifiableList(tvList);

        getDuration = bridge.findMethod(type, "getDuration", 0);
        getProgressMs = bridge.findMethod(type, "getProgressMs", 1, m ->
                m.getParameterTypes()[0].getName().endsWith(".MessageObject"));
        seekToProgress = bridge.findMethod(type, niagram1322 ? "k0" : "seekToProgress", 2, m ->
                m.getParameterTypes()[niagram1322 ? 0 : 1] == float.class);
        seekToProgressMs = bridge.findMethod(type, "seekToProgressMs", 2, m ->
                m.getParameterTypes()[1] == long.class);
        isSamePlayingMessage = bridge.findMethod(type, "isSamePlayingMessage", 1);
        resetGoingToShowMessageObject = bridge.findMethod(type, "resetGoingToShowMessageObject", 0);

        videoPlayerField = bridge.findField(type, niagram1322 ? "N0" : "videoPlayer");
        audioPlayerField = bridge.findField(type, niagram1322 ? "r0" : "audioPlayer");
        isPausedField = bridge.findField(type, niagram1322 ? "p0" : "isPaused");
        downloadingCurrentMessageField = bridge.findField(type, niagram1322 ? "J0" : "downloadingCurrentMessage");
        playingMessageField = bridge.findField(type, niagram1322 ? "D0" : "playingMessageObject");
        playlistField = bridge.findField(type, "playlist");
        playlistMapField = bridge.findField(type, "playlistMap");
        shuffledPlaylistField = bridge.findField(type, "shuffledPlaylist");
        currentPlaylistNumField = bridge.findField(type, "currentPlaylistNum");
        forceLoopCurrentPlaylistField = bridge.findField(type, "forceLoopCurrentPlaylist");
        currentTextureViewField = bridge.findField(type, niagram1322 ? "P0" : "currentTextureView");
        currentAspectRatioFrameLayoutField = bridge.findField(type, "currentAspectRatioFrameLayout");
        currentTextureViewContainerField = bridge.findField(type, "currentTextureViewContainer");
        currentAspectRatioReadyField = bridge.findField(type, "currentAspectRatioFrameLayoutReady");
        pipRoundVideoViewField = bridge.findField(type, niagram1322 ? "Q0" : "pipRoundVideoView");
        pipSwitchingStateField = bridge.findField(type, niagram1322 ? "R0" : "pipSwitchingState");
        baseActivityField = bridge.findField(type, "baseActivity");

        Object dMode = bridge.getStaticField(type, "VIDEO_SLEEP_TIMER_DURATION");
        timerDurationMode = (dMode instanceof Number) ? ((Number) dMode).intValue() : VideoBackgroundSession.MODE_DURATION;
        Object aMode = bridge.getStaticField(type, "VIDEO_SLEEP_TIMER_AFTER_CURRENT");
        timerAfterCurrentMode = (aMode instanceof Number) ? ((Number) aMode).intValue() : VideoBackgroundSession.MODE_AFTER_CURRENT;
    }
}

/**
 * 宿主 VideoPlayer 成员。
 */
final class VideoPlayerMembers {
    final Method setDelegate;
    final Method setTextureView;
    final Method setSurfaceView;
    final Method setStreamType;
    final Method setLooping;
    final Method isLooping;
    final Method isPlaying;
    final Method getPlayWhenReady;
    final Method getPlaybackState;
    final Method getDuration;
    final Method getCurrentPosition;
    final Method play;
    final Method pause;
    final Method seekTo;

    /** NiagramX 把 getPlayWhenReady/getPlaybackState 内联了，回退到这两个保留字段。 */
    final Field playbackStateField;
    final Field playWhenReadyField;

    VideoPlayerMembers(HostBridge bridge, Class<?> type, NiagramXNames.VideoPlayer names) {
        this(bridge, type, names, false);
    }

    VideoPlayerMembers(HostBridge bridge, Class<?> type, NiagramXNames.VideoPlayer names, boolean niagram1322) {
        setDelegate = bridge.findMethodOrShape(type, niagram1322 ? "L0" : HostBridge.pickName(names != null ? names.setDelegate : null, "setDelegate"), 1,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isInterface());
        setTextureView = bridge.findMethodOrShape(type, niagram1322 ? "X0" : HostBridge.pickName(names != null ? names.setTextureView : null, "setTextureView"), 1,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == TextureView.class);
        setSurfaceView = bridge.findMethodOrShape(type, niagram1322 ? "W0" : HostBridge.pickName(names != null ? names.setSurfaceView : null, "setSurfaceView"), 1,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == SurfaceView.class);
        setStreamType = bridge.findMethodOrShape(type, niagram1322 ? "U0" : HostBridge.pickName(names != null ? names.setStreamType : null, "setStreamType"), 1,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == int.class && m.getReturnType() == void.class);
        setLooping = bridge.findMethodOrShape(type, niagram1322 ? "N0" : HostBridge.pickName(names != null ? names.setLooping : null, "setLooping"), 1,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == boolean.class && m.getReturnType() == void.class);
        isLooping = bridge.findMethodOrShape(type, HostBridge.pickName(names != null ? names.isLooping : null, "isLooping"), 0,
                m -> m.getParameterTypes().length == 0 && m.getReturnType() == boolean.class);
        isPlaying = bridge.findMethodOrShape(type, niagram1322 ? "q0" : HostBridge.pickName(names != null ? names.isPlaying : null, "isPlaying"), 0,
                m -> m.getParameterTypes().length == 0 && m.getReturnType() == boolean.class);
        getPlayWhenReady = bridge.findMethod(type, "getPlayWhenReady", 0);
        getPlaybackState = bridge.findMethod(type, "getPlaybackState", 0);
        getDuration = bridge.findMethodOrShape(type, niagram1322 ? "c0" : HostBridge.pickName(names != null ? names.getDuration : null, "getDuration"), 0,
                m -> m.getParameterTypes().length == 0 && m.getReturnType() == long.class);
        getCurrentPosition = bridge.findMethodOrShape(type,
                niagram1322 ? "Y" : HostBridge.pickName(names != null ? names.getCurrentPosition : null, "getCurrentPosition"), 0,
                m -> m.getParameterTypes().length == 0 && m.getReturnType() == long.class);
        play = bridge.findMethodOrShape(type, niagram1322 ? "z0" : HostBridge.pickName(names != null ? names.play : null, "play"), 0,
                m -> m.getParameterTypes().length == 0 && m.getReturnType() == void.class);
        pause = bridge.findMethodOrShape(type, niagram1322 ? "y0" : HostBridge.pickName(names != null ? names.pause : null, "pause"), 0,
                m -> m.getParameterTypes().length == 0 && m.getReturnType() == void.class);
        seekTo = bridge.findMethodOrShape(type, niagram1322 ? "H0" : HostBridge.pickName(names != null ? names.seekTo : null, "seekTo"), 1,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == long.class);

        playbackStateField = bridge.findField(type, "lastReportedPlaybackState");
        playWhenReadyField = bridge.findField(type, "lastReportedPlayWhenReady");
    }
}

/**
 * 宿主 MessageObject 成员。
 */
final class MessageObjectMembers {
    final Method isVideo;
    final Method isVideoMessageStatic;
    final Method isMusic;
    final Method isVoice;
    final Method isRoundVideo;
    final Method isLivePhoto;
    final Method getId;
    final Method getDialogId;
    final Method getDocument;
    final Method getDialogIdFromOwner;
    final Method isHiddenSensitive;
    final Method isSecretMedia;
    final Method getDuration;
    final Method getMusicTitle;
    final Method getMusicTitle0;
    final Method getMusicTitle1;
    final Method getMusicAuthor;
    final Method getMusicAuthor0;
    final Method getMusicAuthor1;
    final Field captionField;

    final Field audioProgressField;
    final Field audioProgressMsField;
    final Field audioProgressSecField;
    final Field currentAccountField;
    final Field messageOwnerField;

    final Method isOutOwner;
    final Method getSenderId;

    MessageObjectMembers(HostBridge bridge, Class<?> type) {
        this(bridge, type, false);
    }

    MessageObjectMembers(HostBridge bridge, Class<?> type, boolean niagram1322) {
        isVideo = bridge.findMethod(type, "isVideo", 0);
        isVideoMessageStatic = bridge.findMethod(type, niagram1322 ? "X4" : "isVideoMessage", 1, m ->
                Modifier.isStatic(m.getModifiers()) && m.getParameterTypes()[0].getName().endsWith("$Message"));
        isMusic = bridge.findMethod(type, niagram1322 ? "R3" : "isMusic", 0);
        isVoice = bridge.findMethod(type, "isVoice", 0);
        isRoundVideo = bridge.findMethod(type, niagram1322 ? "q4" : "isRoundVideo", 0);
        isLivePhoto = bridge.findMethod(type, niagram1322 ? "L3" : "isLivePhoto", 0);
        getId = bridge.findMethod(type, niagram1322 ? "h1" : "getId", 0);
        getDialogId = bridge.findMethod(type, "getDialogId", 0);
        getDialogIdFromOwner = niagram1322 ? bridge.findMethod(type, "E0", 1, m ->
                Modifier.isStatic(m.getModifiers()) && m.getReturnType() == long.class) : null;
        isHiddenSensitive = bridge.findMethod(type, niagram1322 ? "F3" : "isHiddenSensitive", 0);
        isSecretMedia = bridge.findMethod(type, "isSecretMedia", 0);
        getDocument = bridge.findMethod(type, niagram1322 ? "I0" : "getDocument", 0);
        getDuration = bridge.findMethod(type, niagram1322 ? "O0" : "getDuration", 0);
        getMusicTitle0 = bridge.findMethod(type, niagram1322 ? "z1" : "getMusicTitle", 0);
        getMusicTitle1 = bridge.findMethod(type, niagram1322 ? "z1" : "getMusicTitle", 1, m -> m.getParameterTypes()[0] == boolean.class);
        getMusicTitle = getMusicTitle1 != null ? getMusicTitle1 : getMusicTitle0;

        getMusicAuthor0 = bridge.findMethod(type, niagram1322 ? "B1" : "getMusicAuthor", 0);
        getMusicAuthor1 = bridge.findMethod(type, niagram1322 ? "B1" : "getMusicAuthor", 1, m -> m.getParameterTypes()[0] == boolean.class);
        getMusicAuthor = getMusicAuthor1 != null ? getMusicAuthor1 : getMusicAuthor0;

        captionField = bridge.findField(type, "caption");

        audioProgressField = bridge.findField(type, "audioProgress");
        audioProgressMsField = bridge.findField(type, "audioProgressMs");
        audioProgressSecField = bridge.findField(type, "audioProgressSec");
        currentAccountField = bridge.findField(type, "currentAccount");
        messageOwnerField = bridge.findField(type, "messageOwner");

        isOutOwner = bridge.findMethod(type, "isOutOwner", 0);
        getSenderId = bridge.findMethod(type, "getSenderId", 0);
    }
}

/**
 * TLRPC 成员。
 */
final class TlMembers {
    final Field messageText;
    final Field fwdFrom;
    final Field fromId;
    final Field peerId;
    final Field fwdFromName;
    final Field fwdFromId;

    TlMembers(HostBridge bridge) {
        Class<?> messageClass = bridge.loadClass("org.telegram.tgnet.TLRPC$Message");
        Class<?> fwdHeaderClass = bridge.loadClass("org.telegram.tgnet.TLRPC$MessageFwdHeader");

        messageText = bridge.findField(messageClass, "message");
        fwdFrom = bridge.findField(messageClass, "fwd_from");
        fromId = bridge.findField(messageClass, "from_id");
        peerId = bridge.findField(messageClass, "peer_id");

        fwdFromName = bridge.findField(fwdHeaderClass, "from_name");
        fwdFromId = bridge.findField(fwdHeaderClass, "from_id");
    }
}

/**
 * 宿主 MusicPlayerService 成员。
 */
final class MusicServiceMembers {
    final Method createNotification;
    final Method updatePlaybackState;
    final Method onStartCommand;
    final Method onDestroy;
    final Class<?> sessionCallbackClass;
    final Method onSkipToNext;
    final Method onSkipToPrevious;

    MusicServiceMembers(HostBridge bridge, Class<?> type) {
        this(bridge, type, false);
    }

    MusicServiceMembers(HostBridge bridge, Class<?> type, boolean niagram1322) {
        Class<?> messageType = bridge.loadClass("org.telegram.messenger.MessageObject");
        createNotification = bridge.findMethod(type, niagram1322 ? "a" : "createNotification", 2, m ->
                (niagram1322 ? m.getParameterTypes()[0] == messageType
                        : m.getParameterTypes()[0].getName().endsWith(".MessageObject")) &&
                        m.getParameterTypes()[1] == boolean.class);
        updatePlaybackState = bridge.findMethod(type, niagram1322 ? "f" : "updatePlaybackState", 1, m ->
                m.getParameterTypes()[0] == long.class);
        onStartCommand = bridge.findMethod(type, "onStartCommand", 3);
        onDestroy = bridge.findMethod(type, "onDestroy", 0);

        Class<?> callback = null;
        if (type != null) {
            Class<?> callbackBase = bridge.loadClass("android.support.v4.media.session.MediaSessionCompat$Callback");
            if (callbackBase != null) {
                for (int i = 1; i <= 12; i++) {
                    Class<?> candidate = bridge.loadClass(type.getName() + "$" + i);
                    if (candidate != null && callbackBase.isAssignableFrom(candidate)) {
                        callback = candidate;
                        break;
                    }
                }
            }
        }
        sessionCallbackClass = callback;
        onSkipToNext = bridge.findMethod(sessionCallbackClass, "onSkipToNext", 0);
        onSkipToPrevious = bridge.findMethod(sessionCallbackClass, "onSkipToPrevious", 0);
    }
}

/**
 * 宿主播放器 UI 成员（FragmentContextView、AudioPlayerAlert、ChatActivity）。
 */
final class PlayerUiMembers {
    final Class<?> contextViewClass;
    final Class<?> alertClass;
    final Class<?> chatActivityClass;

    final Method checkPlayer;
    final Method contextViewUpdatePlaybackButton;
    final Field contextViewIsMusicField;
    final List<Method> contextViewSpeedCallbacks;
    final Field subtitleTextViewField;
    final Field titleTextViewField;
    final Method miniPlayerSetText;
    final Field contextViewMessageField;
    final Field currentStyleField;
    final Field isSideMenuedField;
    final Field isLocationField;
    final int styleAudioPlayer;
    final List<Method> contextViewClickLambdas;
    final Method contextViewClick;
    final List<Constructor<?>> contextViewConstructors;

    final Constructor<?> alertConstructor;
    final Method alertDidReceivedNotification;
    final Method alertUpdateTitle;
    final List<Method> alertUpdateProgress;
    final Method cellDidPressedButton;
    final Method cellSetMessageObject;
    final Field cellRadialProgressField;
    final Method cellGetMessageObject;

    final Field launchActivityInstanceField;
    final Field alertInstanceField;

    final Method onRemoveFromParent;
    final Method openPhotoViewerForMessage;
    final Method updateTextureViewPosition;
    final Method updateMessagesVisiblePart;

    PlayerUiMembers(HostBridge bridge, Class<?> contextView, Class<?> alert, NiagramXNames.FragmentContextView names) {
        this(bridge, contextView, alert, names, false);
    }

    PlayerUiMembers(HostBridge bridge, Class<?> contextView, Class<?> alert, NiagramXNames.FragmentContextView names, boolean niagram1322) {
        contextViewClass = contextView;
        contextViewClick = niagram1322 ? bridge.findMethod(contextView, "i0", 0, m -> m.getReturnType() == void.class) : null;
        alertClass = alert;
        chatActivityClass = bridge.loadClass("org.telegram.ui.ChatActivity");

        checkPlayer = bridge.findMethodOrShape(contextView,
                niagram1322 ? "Y" : HostBridge.pickName(names != null ? names.checkPlayer : null, "checkPlayer"), 1,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == boolean.class && m.getReturnType() == void.class);
        contextViewUpdatePlaybackButton = bridge.findMethodOrShape(contextView,
                niagram1322 ? "y0" : HostBridge.pickName(names != null ? names.updatePlaybackButton : null, "updatePlaybackButton"), 1,
                m -> m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == boolean.class && m.getReturnType() == void.class);
        contextViewIsMusicField = bridge.findField(contextView, "isMusic");
        List<Method> speedCallbacks = new ArrayList<>();
        speedCallbacks.add(bridge.findMethod(contextView, "updateColors", 0));
        if (niagram1322) {
            // Slider, tap, menu-dismiss, long press and preset callbacks in Niagram 1322.
            speedCallbacks.add(bridge.findMethod(contextView, "j0", Float.class, Boolean.class));
            speedCallbacks.add(bridge.findMethod(contextView, "k0", float[].class));
            speedCallbacks.add(bridge.findMethod(contextView, "l0", float.class, Boolean.class));
            speedCallbacks.add(bridge.findMethod(contextView, "m0", 0));
            speedCallbacks.add(bridge.findMethod(contextView, "n0", int.class));
        } else {
            speedCallbacks.addAll(bridge.findMethodsByShape(contextView,
                    m -> m.getName().startsWith("lambda$createPlaybackSpeedButton$")));
        }
        speedCallbacks.removeIf(m -> m == null);
        contextViewSpeedCallbacks = Collections.unmodifiableList(speedCallbacks);
        subtitleTextViewField = bridge.findField(contextView, "subtitleTextView");
        titleTextViewField = bridge.findField(contextView, "titleTextView");
        miniPlayerSetText = subtitleTextViewField == null ? null : bridge.findMethod(
                subtitleTextViewField.getType(), niagram1322 ? "f" : "setText", CharSequence.class, boolean.class);
        contextViewMessageField = bridge.findField(contextView, "lastMessageObject");
        currentStyleField = bridge.findField(contextView, "currentStyle");
        isSideMenuedField = bridge.findField(contextView, "isSideMenued");
        isLocationField = bridge.findField(contextView, "isLocation");

        Object style = bridge.getStaticField(contextView, "STYLE_AUDIO_PLAYER");
        styleAudioPlayer = (style instanceof Number) ? ((Number) style).intValue() : 0;

        List<Method> lambdas = new ArrayList<>();
        if (contextView != null) {
            try {
                for (Method m : contextView.getDeclaredMethods()) {
                    if ((m.isSynthetic() || m.getName().startsWith("lambda$")) &&
                            m.getReturnType() == void.class &&
                            m.getParameterTypes().length == 1 &&
                            m.getParameterTypes()[0].getName().equals("android.view.View")) {
                        m.setAccessible(true);
                        lambdas.add(m);
                    }
                }
            } catch (Throwable failure) {
                bridge.report("枚举迷你播放器点击回调失败", failure);
            }
        }
        contextViewClickLambdas = Collections.unmodifiableList(lambdas);

        List<Constructor<?>> cvCtors = new ArrayList<>();
        if (contextView != null) {
            try {
                for (Constructor<?> c : contextView.getDeclaredConstructors()) {
                    c.setAccessible(true);
                    cvCtors.add(c);
                }
            } catch (Throwable failure) {
                bridge.report("枚举迷你播放器构造方法失败", failure);
            }
        }
        contextViewConstructors = Collections.unmodifiableList(cvCtors);

        Constructor<?> alertCtor = null;
        if (alert != null) {
            try {
                for (Constructor<?> c : alert.getDeclaredConstructors()) {
                    if (c.getParameterTypes().length == 2 &&
                            c.getParameterTypes()[0].getName().equals("android.content.Context")) {
                        c.setAccessible(true);
                        alertCtor = c;
                        break;
                    }
                }
            } catch (Throwable failure) {
                bridge.report("枚举音频播放器构造方法失败", failure);
            }
        }
        alertConstructor = alertCtor;

        alertDidReceivedNotification = bridge.findMethod(alert, "didReceivedNotification", 3);
        alertUpdateTitle = bridge.findMethod(alert, "updateTitle", 1, m -> m.getParameterTypes()[0] == boolean.class);

        List<Method> progressMethods = new ArrayList<>();
        Method p1 = bridge.findMethod(alert, "updateProgress", 1);
        if (p1 != null) progressMethods.add(p1);
        Method p2 = bridge.findMethod(alert, "updateProgress", 2);
        if (p2 != null) progressMethods.add(p2);
        alertUpdateProgress = Collections.unmodifiableList(progressMethods);

        Class<?> cellClass = bridge.loadClass("org.telegram.ui.Cells.AudioPlayerCell");
        cellDidPressedButton = bridge.findMethod(cellClass, "didPressedButton", 0);
        cellSetMessageObject = bridge.findMethod(cellClass, "setMessageObject", 1);
        cellRadialProgressField = bridge.findField(cellClass, "radialProgress");
        cellGetMessageObject = bridge.findMethod(cellClass, "getMessageObject", 0);

        Class<?> launchActivity = bridge.loadClass("org.telegram.ui.LaunchActivity");
        launchActivityInstanceField = bridge.findField(launchActivity, "instance");
        alertInstanceField = bridge.findField(alert, "lastInstance");

        onRemoveFromParent = bridge.findMethod(chatActivityClass, "onRemoveFromParent", 0);
        openPhotoViewerForMessage = bridge.findMethod(chatActivityClass, "openPhotoViewerForMessage", 2);
        Method texturePosition = bridge.findMethod(chatActivityClass, niagram1322
                ? NiagramXNames.ChatActivity.updateTextureViewPosition : "updateTextureViewPosition",
                boolean.class, boolean.class);
        updateTextureViewPosition = texturePosition != null || niagram1322 ? texturePosition
                : bridge.findMethod(chatActivityClass, "updateTextureViewPosition", 0);
        updateMessagesVisiblePart = niagram1322
                ? bridge.findMethod(chatActivityClass, NiagramXNames.ChatActivity.updateMessagesVisiblePart, 0)
                : bridge.findMethod(chatActivityClass, "updateMessagesVisiblePart", boolean.class);
    }
}

/**
 * 宿主探测环境与元数据映射。
 */
final class HostProfile {

    /** 原版 NagramX。 */
    static final String PACKAGE_NAGRAMX = "nu.gpu.nagram";
    /** NiagramX（HSSkyBoy fork），无宿主原生后台定时播放。 */
    static final String PACKAGE_NIAGRAMX = "top.nkbe.niagram";

    /** 受支持宿主，顺序即状态页展示顺序。 */
    static final List<String> SUPPORTED_PACKAGES = Collections.unmodifiableList(
            Arrays.asList(PACKAGE_NAGRAMX, PACKAGE_NIAGRAMX));

    static boolean isSupportedPackage(String packageName) {
        return packageName != null && SUPPORTED_PACKAGES.contains(packageName);
    }

    static String hostDisplayName(String packageName) {
        if (PACKAGE_NAGRAMX.equals(packageName)) return "NagramX";
        if (PACKAGE_NIAGRAMX.equals(packageName)) return "NiagramX";
        return packageName != null ? packageName : "unknown";
    }

    final HostBridge bridge;
    final int versionCode;
    final boolean niagram1322;
    final String versionName;

    final Class<?> photoViewerClass;
    final Class<?> mediaControllerClass;
    final Class<?> messageObjectClass;
    final Class<?> videoPlayerClass;
    final Class<?> musicPlayerServiceClass;
    final Class<?> pipRoundVideoViewClass;
    final Class<?> fileLoaderClass;

    final PhotoViewerMembers photo;
    final MediaControllerMembers media;
    final VideoPlayerMembers player;
    final MessageObjectMembers message;
    final MusicServiceMembers musicService;
    final PlayerUiMembers playerUi;

    final Class<?> messagesControllerClass;
    final Method messagesControllerGetInstance;
    final Method getPeerName;

    private final Class<?> dialogObjectClass;
    private final Method getPeerDialogId;

    final TlMembers tl;

    private final Class<?> userConfigClass;
    private final Method userConfigGetInstance;
    private final Method getClientUserId;

    private final Class<?> applicationLoaderClass;
    final Method cancelLoadFileByDocument;

    private HostProfile(
            HostBridge bridge,
            int versionCode,
            boolean niagram1322,
            String versionName,
            Class<?> photoViewerClass,
            Class<?> mediaControllerClass,
            Class<?> messageObjectClass,
            Class<?> videoPlayerClass,
            Class<?> musicPlayerServiceClass,
            Class<?> pipRoundVideoViewClass,
            Class<?> fileLoaderClass,
            PhotoViewerMembers photo,
            MediaControllerMembers media,
            VideoPlayerMembers player,
            MessageObjectMembers message,
            MusicServiceMembers musicService,
            PlayerUiMembers playerUi
    ) {
        this.bridge = bridge;
        this.versionCode = versionCode;
        this.niagram1322 = niagram1322;
        this.versionName = versionName;
        this.photoViewerClass = photoViewerClass;
        this.mediaControllerClass = mediaControllerClass;
        this.messageObjectClass = messageObjectClass;
        this.videoPlayerClass = videoPlayerClass;
        this.musicPlayerServiceClass = musicPlayerServiceClass;
        this.pipRoundVideoViewClass = pipRoundVideoViewClass;
        this.fileLoaderClass = fileLoaderClass;

        this.photo = photo;
        this.media = media;
        this.player = player;
        this.message = message;
        this.musicService = musicService;
        this.playerUi = playerUi;

        this.messagesControllerClass = bridge.loadClass("org.telegram.messenger.MessagesController");
        this.messagesControllerGetInstance = bridge.findMethod(messagesControllerClass, "getInstance", 1, m ->
                Modifier.isStatic(m.getModifiers()) && m.getParameterTypes()[0] == int.class);
        this.getPeerName = bridge.findMethod(messagesControllerClass, "getPeerName", 1, m ->
                m.getParameterTypes()[0] == long.class);

        this.dialogObjectClass = bridge.loadClass("org.telegram.messenger.DialogObject");
        this.getPeerDialogId = bridge.findMethod(dialogObjectClass, "getPeerDialogId", 1, m ->
                Modifier.isStatic(m.getModifiers()) && m.getParameterTypes()[0].getName().endsWith("$Peer"));

        this.tl = new TlMembers(bridge);

        this.userConfigClass = bridge.loadClass("org.telegram.messenger.UserConfig");
        this.userConfigGetInstance = bridge.findMethod(userConfigClass, "getInstance", 1, m ->
                Modifier.isStatic(m.getModifiers()) && m.getParameterTypes()[0] == int.class);
        this.getClientUserId = bridge.findMethod(userConfigClass, "getClientUserId", 0);

        this.applicationLoaderClass = bridge.loadClass("org.telegram.messenger.ApplicationLoader");
        this.cancelLoadFileByDocument = bridge.findMethod(fileLoaderClass, niagram1322 ? "g" : "cancelLoadFile", 1, m ->
                m.getParameterTypes()[0].getName().endsWith("$Document"));
    }

    static HostProfile discover(String packageName, ClassLoader classLoader, ApplicationInfo app,
                                HostBridge.Logger logger) {
        HostBridge bridge = new HostBridge(classLoader, logger);
        Class<?> buildConfig = bridge.loadClass("org.telegram.messenger.BuildConfig");
        Class<?> photoViewer = bridge.loadClass("org.telegram.ui.PhotoViewer");
        Class<?> mediaController = bridge.loadClass("org.telegram.messenger.MediaController");

        if (photoViewer == null || mediaController == null) {
            logger.log("宿主必需类不存在，停止安装 Hook", null);
            return null;
        }
        Field viewerPlayer = bridge.findField(photoViewer, "videoPlayer");
        Field viewerMessage = bridge.findField(photoViewer, "currentMessageObject");
        boolean niagram1322 = PACKAGE_NIAGRAMX.equals(packageName) && buildConfig == null
                && hasObfuscatedPlayerTypes(bridge, photoViewer, mediaController);
        if (!niagram1322 && buildConfig == null) {
            logger.log("Niagram 播放器结构未识别，停止安装 Hook", null);
            return null;
        }
        if (niagram1322) {
            Map<String, String> resolved = NiagramDexClasses.resolve(app, classLoader, logger);
            if (resolved.isEmpty()) {
                logger.log("Niagram 混淆类未完整解析，停止安装 Hook", null);
                return null;
            }
            Map<String, String> names = new HashMap<>(resolved);
            names.put("org.telegram.messenger.MessageObject", viewerMessage.getType().getName());
            names.put("org.telegram.ui.Components.VideoPlayer", viewerPlayer.getType().getName());
            bridge.bindClasses(names);
            logger.log("Niagram 类型锚点：message=" + viewerMessage.getType().getName()
                    + ", player=" + viewerPlayer.getType().getName(), null);
        }

        int code = -1;
        for (String field : new String[]{"VERSION_CODE", "OFFICIAL_VERSION_CODE"}) {
            Object val = bridge.getStaticField(buildConfig, field);
            if (val instanceof Number) {
                code = ((Number) val).intValue();
                break;
            }
        }

        String name = niagram1322 ? "Niagram (R8 类型映射)" : null;
        for (String field : new String[]{"VERSION_NAME", "OFFICIAL_VERSION_NAME"}) {
            Object val = bridge.getStaticField(buildConfig, field);
            if (val instanceof String) {
                name = (String) val;
                break;
            }
        }

        Class<?> messageObject = niagram1322 ? viewerMessage.getType()
                : bridge.loadClass("org.telegram.messenger.MessageObject");

        // NiagramX 用 R8 把 ui 包方法名和部分类名搬走，按已验证版本号启用混淆名表
        boolean obfuscatedNames = NiagramXNames.matches(code);
        if (obfuscatedNames) {
            logger.log("命中 NiagramX 混淆名表：versionCode=" + code + ", versionName=" + name, null);
        }

        PhotoViewerMembers photoMembers = new PhotoViewerMembers(bridge, photoViewer,
                obfuscatedNames ? new NiagramXNames.PhotoViewer() : null, niagram1322);

        // VideoPlayer 在 NiagramX 里被 -repackageclasses 搬成默认包短名，
        // 用 PhotoViewer.videoPlayer 字段的类型做锚点，不硬编码短名。
        Class<?> videoPlayer = photoMembers.videoPlayerField != null
                ? photoMembers.videoPlayerField.getType()
                : bridge.loadClass("org.telegram.ui.Components.VideoPlayer");

        MediaControllerMembers media = new MediaControllerMembers(bridge, mediaController, niagram1322);
        if (media.getInstance == null) {
            logger.log("找不到 MediaController.getInstance()，停止安装 Hook", null);
            return null;
        }

        Class<?> musicPlayerService = bridge.loadClass("org.telegram.messenger.MusicPlayerService");
        Class<?> pipRoundVideoView = bridge.loadClass("org.telegram.ui.Components.PipRoundVideoView");
        Class<?> fileLoader = bridge.loadClass("org.telegram.messenger.FileLoader");
        Class<?> contextView = bridge.loadClass("org.telegram.ui.Components.FragmentContextView");
        Class<?> audioPlayerAlert = bridge.loadClass("org.telegram.ui.Components.AudioPlayerAlert");

        return new HostProfile(
                bridge,
                code,
                niagram1322,
                name,
                photoViewer,
                mediaController,
                messageObject,
                videoPlayer,
                musicPlayerService,
                pipRoundVideoView,
                fileLoader,
                photoMembers,
                media,
                new VideoPlayerMembers(bridge, videoPlayer,
                        obfuscatedNames ? new NiagramXNames.VideoPlayer() : null, niagram1322),
                new MessageObjectMembers(bridge, messageObject, niagram1322),
                new MusicServiceMembers(bridge, musicPlayerService, niagram1322),
                new PlayerUiMembers(bridge, contextView, audioPlayerAlert,
                        obfuscatedNames ? new NiagramXNames.FragmentContextView() : null, niagram1322)
        );
    }

    static boolean hasObfuscatedPlayerTypes(HostBridge bridge, Class<?> viewer, Class<?> controller) {
        Field player = bridge.findField(viewer, "videoPlayer");
        Field message = bridge.findField(viewer, "currentMessageObject");
        if (player == null || message == null || player.getType() == message.getType()
                || player.getType().isPrimitive() || message.getType().isPrimitive()) return false;
        Field state = bridge.findField(player.getType(), "lastReportedPlaybackState");
        Field ready = bridge.findField(player.getType(), "lastReportedPlayWhenReady");
        return state != null && state.getType() == int.class
                && ready != null && ready.getType() == boolean.class
                && bridge.findField(message.getType(), "messageOwner") != null
                && bridge.findField(message.getType(), "currentAccount") != null
                && bridge.findMethodOrShape(controller, "getInstance", 0, method ->
                        Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                                && method.getReturnType() == controller) != null;
    }

    int getSelectedAccount() {
        Object val = bridge.getStaticField(userConfigClass, "selectedAccount");
        return (val instanceof Number) ? ((Number) val).intValue() : 0;
    }

    long clientUserId(int account) {
        Object config = bridge.invoke(null, userConfigGetInstance, account);
        if (config == null) return 0L;
        Object val = bridge.invoke(config, getClientUserId);
        return (val instanceof Number) ? ((Number) val).longValue() : 0L;
    }

    Context getAppContext() {
        Object val = bridge.getStaticField(applicationLoaderClass, niagram1322 ? "d" : "applicationContext");
        return (val instanceof Context) ? (Context) val : null;
    }

    boolean hasCompleteNativeTimerUi() {
        return photo.nativeBackgroundStart != null && photo.nativeTimerItemField != null;
    }

    boolean canInjectTimerMenu() {
        return photo.setParentActivity != null && (photo.getParentActivity != null || photo.parentActivityField != null)
                && photo.videoItemField != null;
    }

    boolean canStartBackgroundPlayback() {
        return media.getInstance != null
                && (media.injectVideoPlayer != null || (niagram1322 && photo.injectToMediaController != null)) &&
                photo.closePhoto != null && photo.videoPlayerField != null &&
                player.setDelegate != null && musicPlayerServiceClass != null;
    }

    boolean canManagePlaylist() {
        return media.playlistField != null && media.playlistMapField != null && media.currentPlaylistNumField != null;
    }

    boolean canDressNotification() {
        return message.isMusic != null && musicService.createNotification != null;
    }

    String peerName(int account, long peerId) {
        if (peerId == 0L) return null;
        Object controller = bridge.invoke(null, messagesControllerGetInstance, account);
        if (controller == null) return null;
        Object val = bridge.invoke(controller, getPeerName, peerId);
        if (val instanceof String && !((String) val).trim().isEmpty()) {
            return (String) val;
        }
        return null;
    }

    String peerNameOf(int account, Object peer) {
        if (peer == null) return null;
        Object val = bridge.invoke(null, getPeerDialogId, peer);
        if (!(val instanceof Number)) return null;
        return peerName(account, ((Number) val).longValue());
    }

    Object mediaController() {
        return bridge.invoke(null, media.getInstance);
    }

    Object playerOf(Object photoViewer) {
        if (photoViewer == null) return null;
        Object p = bridge.invoke(photoViewer, photo.getVideoPlayer);
        if (p != null) return p;
        return bridge.getField(photoViewer, photo.videoPlayerField);
    }

    Object messageOf(Object photoViewer) {
        if (photoViewer == null) return null;
        Object m = bridge.invoke(photoViewer, photo.getCurrentMessageObject);
        if (m != null) return m;
        return bridge.getField(photoViewer, photo.currentMessageField);
    }

    /** NiagramX 把 PhotoViewer.isVisible() 内联了，先调方法，回退到保留字段。 */
    boolean isPhotoVisible(Object photoViewer) {
        if (photoViewer == null) return false;
        Object val = bridge.invoke(photoViewer, photo.isVisible);
        if (val instanceof Boolean) return (Boolean) val;
        Object field = bridge.getField(photoViewer, photo.isVisibleField);
        return Boolean.TRUE.equals(field);
    }

    /** NiagramX 把 VideoPlayer.getPlaybackState() 内联了，回退到 lastReportedPlaybackState。 */
    int playbackStateOf(Object videoPlayer) {
        if (videoPlayer == null) return -1;
        Object val = bridge.invoke(videoPlayer, player.getPlaybackState);
        if (val instanceof Number) return ((Number) val).intValue();
        Object fallback = bridge.getField(videoPlayer, player.playbackStateField);
        return (fallback instanceof Number) ? ((Number) fallback).intValue() : -1;
    }

    /** NiagramX 把 VideoPlayer.getPlayWhenReady() 内联了，回退到 lastReportedPlayWhenReady。 */
    boolean playWhenReadyOf(Object videoPlayer) {
        if (videoPlayer == null) return false;
        Object val = bridge.invoke(videoPlayer, player.getPlayWhenReady);
        if (val instanceof Boolean) return (Boolean) val;
        Object fallback = bridge.getField(videoPlayer, player.playWhenReadyField);
        return Boolean.TRUE.equals(fallback);
    }

    long dialogIdOf(Object messageObject) {
        if (messageObject == null) return 0L;
        Object val = bridge.invoke(messageObject, message.getDialogId);
        if (!(val instanceof Number) && message.getDialogIdFromOwner != null) {
            val = bridge.invoke(null, message.getDialogIdFromOwner, bridge.getField(messageObject, message.messageOwnerField));
        }
        return (val instanceof Number) ? ((Number) val).longValue() : 0L;
    }

    int messageIdOf(Object messageObject) {
        if (messageObject == null) return 0;
        Object val = bridge.invoke(messageObject, message.getId);
        return (val instanceof Number) ? ((Number) val).intValue() : 0;
    }

    boolean isVideoMessage(Object messageObject) {
        if (messageObject == null) return false;
        if (!isVideoIgnoringMask(messageObject)) return false;
        Object isLive = bridge.invoke(messageObject, message.isLivePhoto);
        if (Boolean.TRUE.equals(isLive)) return false;
        Object isRound = bridge.invoke(messageObject, message.isRoundVideo);
        return !Boolean.TRUE.equals(isRound);
    }

    private boolean isVideoIgnoringMask(Object messageObject) {
        Object owner = bridge.getField(messageObject, message.messageOwnerField);
        if (owner != null) {
            if (niagram1322 && message.isVideoMessageStatic != null
                    && MessageIdentityMask.resolve(owner, MessageIdentityMask.Kind.VIDEO) != null) {
                try {
                    return Boolean.TRUE.equals(com.shitianyaa.nagramx.videotimer.core.Xp.module()
                            .getInvoker(message.isVideoMessageStatic)
                            .setType(io.github.libxposed.api.XposedInterface.Invoker.Type.ORIGIN).invoke(null, owner));
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Fall back to normal reflection if the framework cannot invoke the original.
                }
            }
            Object val = bridge.invoke(null, message.isVideoMessageStatic, owner);
            if (val instanceof Boolean) return (Boolean) val;
        }
        Object val = bridge.invoke(messageObject, message.isVideo);
        return Boolean.TRUE.equals(val);
    }

    String describeGaps() {
        List<String> gaps = new ArrayList<>();
        if (photo.videoItemField == null) gaps.add("PhotoViewer.videoItem");
        if (photo.videoPlayerField == null) gaps.add("PhotoViewer.videoPlayer");
        if (photo.closePhoto == null) gaps.add("PhotoViewer.closePhoto");
        if (photo.imagesArrField == null) gaps.add("PhotoViewer.imagesArr");
        if (media.injectVideoPlayer == null) gaps.add("MediaController.injectVideoPlayer");
        if (media.cleanupPlayer4 == null) gaps.add("MediaController.cleanupPlayer/4");
        if (media.playMessage == null) gaps.add("MediaController.playMessage");
        if (media.updateVideoState == null) gaps.add("MediaController.updateVideoState");
        if (media.canStartMusicPlayerService == null) gaps.add("MediaController.canStartMusicPlayerService");
        if (media.playlistField == null) gaps.add("MediaController.playlist");
        if (media.playlistMapField == null) gaps.add("MediaController.playlistMap");
        if (media.currentPlaylistNumField == null) gaps.add("MediaController.currentPlaylistNum");
        if (media.pipRoundVideoViewField == null) gaps.add("MediaController.pipRoundVideoView");
        if (player.setStreamType == null) gaps.add("VideoPlayer.setStreamType");
        if (player.setDelegate == null) gaps.add("VideoPlayer.setDelegate");
        if (pipRoundVideoViewClass == null) gaps.add("PipRoundVideoView");
        if (musicPlayerServiceClass == null) gaps.add("MusicPlayerService");
        if (message.isMusic == null) gaps.add("MessageObject.isMusic");
        if (musicService.createNotification == null) gaps.add("MusicPlayerService.createNotification");
        if (musicService.updatePlaybackState == null) gaps.add("MusicPlayerService.updatePlaybackState");
        if (getPeerName == null) gaps.add("MessagesController.getPeerName");
        if (message.isVideoMessageStatic == null) gaps.add("MessageObject.isVideoMessage(static)");
        if (getPeerDialogId == null) gaps.add("DialogObject.getPeerDialogId");
        if (tl.fwdFrom == null) gaps.add("TLRPC.Message.fwd_from");

        if (gaps.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gaps.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(gaps.get(i));
        }
        return sb.toString();
    }
}
