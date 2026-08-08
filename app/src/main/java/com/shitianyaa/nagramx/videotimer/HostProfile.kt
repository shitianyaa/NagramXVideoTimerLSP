package com.shitianyaa.nagramx.videotimer

import android.app.Activity
import android.content.Context
import android.view.TextureView
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** `org.telegram.ui.PhotoViewer` 的成员。 */
internal class PhotoViewerMembers(bridge: HostBridge, type: Class<*>) {
    val getInstance: Method? = bridge.findMethod(type, "getInstance", 0) {
        Modifier.isStatic(it.modifiers)
    }
    val hasInstance: Method? = bridge.findMethod(type, "hasInstance", 0) {
        Modifier.isStatic(it.modifiers)
    }
    val setParentActivity: Method? = bridge.findMethod(type, "setParentActivity", 3) {
        it.parameterTypes.firstOrNull() == Activity::class.java
    }
    val getParentActivity: Method? = bridge.findMethod(type, "getParentActivity", 0) {
        Activity::class.java.isAssignableFrom(it.returnType)
    }
    val closePhoto: Method? = bridge.findMethod(type, "closePhoto", 2) {
        it.parameterTypes.all { param -> param == Boolean::class.javaPrimitiveType }
    }
    val isVisible: Method? = bridge.findMethod(type, "isVisible", 0)
    val playVideoOrWeb: Method? = bridge.findMethod(type, "playVideoOrWeb", 0)
    val pauseVideoOrWeb: Method? = bridge.findMethod(type, "pauseVideoOrWeb", 0)
    val getVideoPlayer: Method? = bridge.findMethod(type, "getVideoPlayer", 0)
    val getCurrentMessageObject: Method? = bridge.findMethod(type, "getCurrentMessageObject", 0)

    /** 宿主自带的「把播放器交给 MediaController」路径，原版就有，优先复用。 */
    val injectToMediaController: Method? =
        bridge.findMethod(type, "injectVideoPlayerToMediaController", 0)

    /** 转交后仍可能被匿名播放器回调触达，需要在后台会话期间静音掉。 */
    val playOrStopAnimatedStickers: Method? =
        bridge.findMethod(type, "playOrStopAnimatedStickers", 1) {
            it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
    val seekAnimatedStickersTo: Method? = bridge.findMethod(type, "seekAnimatedStickersTo", 1) {
        it.parameterTypes[0] == Long::class.javaPrimitiveType
    }

    val videoItemField: Field? = bridge.findField(type, "videoItem")
    val loopItemField: Field? = bridge.findField(type, "loopItem")
    val videoPlayerField: Field? = bridge.findField(type, "videoPlayer")
    val currentMessageField: Field? = bridge.findField(type, "currentMessageObject")
    val imagesArrField: Field? = bridge.findField(type, "imagesArr")
    val playerLoopingField: Field? = bridge.findField(type, "playerLooping")
    val manuallyPausedField: Field? = bridge.findField(type, "manuallyPaused")
    val isPlayingField: Field? = bridge.findField(type, "isPlaying")
    val playerInjectedField: Field? = bridge.findField(type, "playerInjected")
    val activityContextField: Field? = bridge.findField(type, "activityContext")

    /** fork 独有；存在即说明宿主已自带完整的后台定时播放实现。 */
    val nativeBackgroundStart: Method? = bridge.findMethod(type, "startVideoBackgroundPlayback", 2) {
        it.returnType == Boolean::class.javaPrimitiveType &&
            it.parameterTypes.all { param -> param == Int::class.javaPrimitiveType }
    }
    val nativeTimerSheet: Method? = bridge.findMethod(type, "showVideoSleepTimerSheet", 0)
    val nativeTimerItemField: Field? = bridge.findField(type, "videoSleepTimerItem")
}

/** `org.telegram.messenger.MediaController` 的成员。 */
internal class MediaControllerMembers(bridge: HostBridge, type: Class<*>) {
    val getInstance: Method? = bridge.findMethod(type, "getInstance", 0) {
        Modifier.isStatic(it.modifiers)
    }
    val injectVideoPlayer: Method? = bridge.findMethod(type, "injectVideoPlayer", 2) {
        !Modifier.isStatic(it.modifiers) &&
            it.parameterTypes[0].name.endsWith(".VideoPlayer") &&
            it.parameterTypes[1].name.endsWith(".MessageObject")
    }
    val cleanupPlayer4: Method? = bridge.findMethod(type, "cleanupPlayer", 4) {
        it.parameterTypes.all { param -> param == Boolean::class.javaPrimitiveType }
    }
    val cleanupPlayer2: Method? = bridge.findMethod(type, "cleanupPlayer", 2) {
        it.parameterTypes.all { param -> param == Boolean::class.javaPrimitiveType }
    }
    val playMessage: Method? = bridge.findMethod(type, "playMessage", 2) {
        it.parameterTypes[0].name.endsWith(".MessageObject") &&
            it.parameterTypes[1] == Boolean::class.javaPrimitiveType
    }
    val pauseMessage: Method? = bridge.findMethod(type, "pauseMessage", 1) {
        it.parameterTypes[0].name.endsWith(".MessageObject")
    }
    val getPlayingMessageObject: Method? = bridge.findMethod(type, "getPlayingMessageObject", 0)
    val getPlaylist: Method? = bridge.findMethod(type, "getPlaylist", 0)
    val isMessagePaused: Method? = bridge.findMethod(type, "isMessagePaused", 0)
    val playNextMessage: Method? = bridge.findMethod(type, "playNextMessage", 0)
    val playPreviousMessage: Method? = bridge.findMethod(type, "playPreviousMessage", 0)
    val playNextMessageWithoutOrder: Method? =
        bridge.findMethod(type, "playNextMessageWithoutOrder", 1) {
            it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
    val clearPlaylist: Method? = bridge.findMethod(type, "clearPlaylist", 0)
    val sortPlaylist: Method? = bridge.findMethod(type, "sortPlaylist", 0)
    val buildShuffledPlayList: Method? = bridge.findMethod(type, "buildShuffledPlayList", 0)
    val updateVideoState: Method? = bridge.findMethod(type, "updateVideoState", 5) {
        it.parameterTypes[0].name.endsWith(".MessageObject") &&
            it.parameterTypes[1] == IntArray::class.java &&
            it.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
            it.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
            it.parameterTypes[4] == Int::class.javaPrimitiveType
    }
    val canStartMusicPlayerService: Method? =
        bridge.findMethod(type, "canStartMusicPlayerService", 0) {
            it.returnType == Boolean::class.javaPrimitiveType
        }
    val setCurrentVideoVisible: Method? = bridge.findMethod(type, "setCurrentVideoVisible", 1) {
        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
    }
    val setTextureView: List<Method> = listOfNotNull(
        bridge.findMethod(type, "setTextureView", 4) {
            TextureView::class.java.isAssignableFrom(it.parameterTypes[0])
        },
        bridge.findMethod(type, "setTextureView", 5) {
            TextureView::class.java.isAssignableFrom(it.parameterTypes[0])
        },
    )
    val getDuration: Method? = bridge.findMethod(type, "getDuration", 0)
    val getProgressMs: Method? = bridge.findMethod(type, "getProgressMs", 1) {
        it.parameterTypes[0].name.endsWith(".MessageObject")
    }
    val seekToProgress: Method? = bridge.findMethod(type, "seekToProgress", 2) {
        it.parameterTypes[1] == Float::class.javaPrimitiveType
    }
    val seekToProgressMs: Method? = bridge.findMethod(type, "seekToProgressMs", 2) {
        it.parameterTypes[1] == Long::class.javaPrimitiveType
    }
    val isSamePlayingMessage: Method? = bridge.findMethod(type, "isSamePlayingMessage", 1)
    val resetGoingToShowMessageObject: Method? =
        bridge.findMethod(type, "resetGoingToShowMessageObject", 0)

    val videoPlayerField: Field? = bridge.findField(type, "videoPlayer")
    val audioPlayerField: Field? = bridge.findField(type, "audioPlayer")
    val isPausedField: Field? = bridge.findField(type, "isPaused")
    val playingMessageField: Field? = bridge.findField(type, "playingMessageObject")
    val playlistField: Field? = bridge.findField(type, "playlist")
    val playlistMapField: Field? = bridge.findField(type, "playlistMap")
    val shuffledPlaylistField: Field? = bridge.findField(type, "shuffledPlaylist")
    val currentPlaylistNumField: Field? = bridge.findField(type, "currentPlaylistNum")
    val forceLoopCurrentPlaylistField: Field? = bridge.findField(type, "forceLoopCurrentPlaylist")
    val currentTextureViewField: Field? = bridge.findField(type, "currentTextureView")
    val currentAspectRatioFrameLayoutField: Field? =
        bridge.findField(type, "currentAspectRatioFrameLayout")
    val currentTextureViewContainerField: Field? =
        bridge.findField(type, "currentTextureViewContainer")
    val currentAspectRatioReadyField: Field? =
        bridge.findField(type, "currentAspectRatioFrameLayoutReady")
    val pipRoundVideoViewField: Field? = bridge.findField(type, "pipRoundVideoView")
    val pipSwitchingStateField: Field? = bridge.findField(type, "pipSwitchingState")
    val baseActivityField: Field? = bridge.findField(type, "baseActivity")

    /** fork 独有的睡眠定时模式常量；原版没有时退回模块自己的取值。 */
    val timerDurationMode: Int =
        (bridge.getStaticField(type, "VIDEO_SLEEP_TIMER_DURATION") as? Number)?.toInt()
            ?: VideoBackgroundSession.MODE_DURATION
    val timerAfterCurrentMode: Int =
        (bridge.getStaticField(type, "VIDEO_SLEEP_TIMER_AFTER_CURRENT") as? Number)?.toInt()
            ?: VideoBackgroundSession.MODE_AFTER_CURRENT
}

/** `org.telegram.ui.Components.VideoPlayer` 的成员。 */
internal class VideoPlayerMembers(bridge: HostBridge, type: Class<*>?) {
    val setDelegate: Method? = bridge.findMethod(type, "setDelegate", 1)
    val setTextureView: Method? = bridge.findMethod(type, "setTextureView", 1)
    val setSurfaceView: Method? = bridge.findMethod(type, "setSurfaceView", 1)
    val setStreamType: Method? = bridge.findMethod(type, "setStreamType", 1) {
        it.parameterTypes[0] == Int::class.javaPrimitiveType
    }
    val setLooping: Method? = bridge.findMethod(type, "setLooping", 1) {
        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
    }
    val isLooping: Method? = bridge.findMethod(type, "isLooping", 0)
    val isPlaying: Method? = bridge.findMethod(type, "isPlaying", 0)
    val getPlayWhenReady: Method? = bridge.findMethod(type, "getPlayWhenReady", 0)
    val getPlaybackState: Method? = bridge.findMethod(type, "getPlaybackState", 0)
    val getDuration: Method? = bridge.findMethod(type, "getDuration", 0)
    val getCurrentPosition: Method? = bridge.findMethod(type, "getCurrentPosition", 0)
    val play: Method? = bridge.findMethod(type, "play", 0)
    val pause: Method? = bridge.findMethod(type, "pause", 0)
    val seekTo: Method? = bridge.findMethod(type, "seekTo", 1) {
        it.parameterTypes[0] == Long::class.javaPrimitiveType
    }
}

/** `org.telegram.messenger.MessageObject` 的成员。 */
internal class MessageObjectMembers(bridge: HostBridge, type: Class<*>?) {
    val isVideo: Method? = bridge.findMethod(type, "isVideo", 0)

    /**
     * 静态的 `MessageObject.isVideoMessage(TLRPC.Message)` —— 实例方法 `isVideo()` 的实现本体
     * （`isVideo()` 就是 `return isVideoMessage(messageOwner);`）。
     *
     * 身份伪装挂在实例方法上，伪装生效期间读 `isVideo()` 拿到的是伪造值。要回答
     * 「这条消息本来是不是视频」必须绕开它，走这个没被 Hook 的静态实现。
     */
    val isVideoMessageStatic: Method? = bridge.findMethod(type, "isVideoMessage", 1) {
        Modifier.isStatic(it.modifiers) && it.parameterTypes[0].name.endsWith("\$Message")
    }
    val isMusic: Method? = bridge.findMethod(type, "isMusic", 0)
    val isVoice: Method? = bridge.findMethod(type, "isVoice", 0)
    val isRoundVideo: Method? = bridge.findMethod(type, "isRoundVideo", 0)
    val isLivePhoto: Method? = bridge.findMethod(type, "isLivePhoto", 0)
    val getId: Method? = bridge.findMethod(type, "getId", 0)
    val getDialogId: Method? = bridge.findMethod(type, "getDialogId", 0)
    val getDocument: Method? = bridge.findMethod(type, "getDocument", 0)
    val getDuration: Method? = bridge.findMethod(type, "getDuration", 0)
    val getMusicTitle: Method? = bridge.findMethod(type, "getMusicTitle", 1) {
        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
    }
    val getMusicAuthor: Method? = bridge.findMethod(type, "getMusicAuthor", 1) {
        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
    }

    val audioProgressField: Field? = bridge.findField(type, "audioProgress")
    val audioProgressMsField: Field? = bridge.findField(type, "audioProgressMs")
    val audioProgressSecField: Field? = bridge.findField(type, "audioProgressSec")
    val currentAccountField: Field? = bridge.findField(type, "currentAccount")
    val messageOwnerField: Field? = bridge.findField(type, "messageOwner")

    val isOutOwner: Method? = bridge.findMethod(type, "isOutOwner", 0)
    val getSenderId: Method? = bridge.findMethod(type, "getSenderId", 0)
}

/**
 * `TLRPC.Message` 与 `TLRPC.MessageFwdHeader` 上要读的几个公有字段。
 *
 * 只用于视频的标题/作者回退：caption 取自 `message`，作者链要按 fork 的顺序依次看
 * 转发来源、发送者、会话。字段声明在基类上，`field.get` 对 `TL_message` 这类子类实例同样有效。
 */
internal class TlMembers(bridge: HostBridge) {
    private val messageClass: Class<*>? = bridge.loadClass("org.telegram.tgnet.TLRPC\$Message")
    private val fwdHeaderClass: Class<*>? =
        bridge.loadClass("org.telegram.tgnet.TLRPC\$MessageFwdHeader")

    /** 消息正文，视频的 caption 就存在这里。 */
    val messageText: Field? = bridge.findField(messageClass, "message")
    val fwdFrom: Field? = bridge.findField(messageClass, "fwd_from")
    val fromId: Field? = bridge.findField(messageClass, "from_id")
    val peerId: Field? = bridge.findField(messageClass, "peer_id")

    /** 隐藏了账号的转发来源只留一个名字字符串。 */
    val fwdFromName: Field? = bridge.findField(fwdHeaderClass, "from_name")
    val fwdFromId: Field? = bridge.findField(fwdHeaderClass, "from_id")
}

/** `org.telegram.messenger.MusicPlayerService` 的成员。 */
internal class MusicServiceMembers(bridge: HostBridge, type: Class<*>?) {
    val createNotification: Method? = bridge.findMethod(type, "createNotification", 2) {
        it.parameterTypes[0].name.endsWith(".MessageObject") &&
            it.parameterTypes[1] == Boolean::class.javaPrimitiveType
    }
    val updatePlaybackState: Method? = bridge.findMethod(type, "updatePlaybackState", 1) {
        it.parameterTypes[0] == Long::class.javaPrimitiveType
    }
    val onStartCommand: Method? = bridge.findMethod(type, "onStartCommand", 3)
    val onDestroy: Method? = bridge.findMethod(type, "onDestroy", 0)

    /**
     * `mediaSession.setCallback(new MediaSessionCompat.Callback() {...})` 那个匿名类。
     *
     * 匿名类不出现在 `getDeclaredClasses()` 里，只能按 `外层类$N` 依次试探，
     * 再用父类是否为 `MediaSessionCompat.Callback` 确认。
     *
     * 需要它是因为 `onSkipToNext` / `onSkipToPrevious` 内部有 `isMusic()` 判断，
     * 而这两个方法不在 `createNotification` 的调用栈里，身份伪装覆盖不到。
     */
    val sessionCallbackClass: Class<*>? = run {
        if (type == null) return@run null
        val callbackBase =
            bridge.loadClass("android.support.v4.media.session.MediaSessionCompat\$Callback")
                ?: return@run null
        (1..MAX_ANONYMOUS_SCAN).firstNotNullOfOrNull { index ->
            bridge.loadClass("${type.name}\$$index")
                ?.takeIf { callbackBase.isAssignableFrom(it) }
        }
    }

    val onSkipToNext: Method? = bridge.findMethod(sessionCallbackClass, "onSkipToNext", 0)
    val onSkipToPrevious: Method? = bridge.findMethod(sessionCallbackClass, "onSkipToPrevious", 0)

    private companion object {
        const val MAX_ANONYMOUS_SCAN = 12
    }
}

/**
 * 顶部迷你播放器 `FragmentContextView` 与播放列表弹层 `AudioPlayerAlert` 的成员。
 *
 * fork 在这两处把 `isMusic() || isVoice()` 扩成包含视频；模块靠身份伪装让宿主自己走进音乐分支，
 * 所以这里要拿到的是「需要被伪装包裹的方法」，而不是具体分支。
 */
internal class PlayerUiMembers(bridge: HostBridge, contextView: Class<*>?, alert: Class<*>?) {
    val contextViewClass: Class<*>? = contextView
    val alertClass: Class<*>? = alert

    /** 开头就有 `messageObject.isVideo()` 早退守卫，视频会被直接判为不可展示。 */
    val checkPlayer: Method? = bridge.findMethod(contextView, "checkPlayer", 1) {
        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
    }
    val contextViewUpdatePlaybackButton: Method? =
        bridge.findMethod(contextView, "updatePlaybackButton", 1)
    val subtitleTextViewField: Field? = bridge.findField(contextView, "subtitleTextView")
    val titleTextViewField: Field? = bridge.findField(contextView, "titleTextView")
    val currentStyleField: Field? = bridge.findField(contextView, "currentStyle")

    /**
     * `isSideMenued`：平板侧边栏布局会给播放条右侧多留 64dp。
     *
     * 重排标题/副标题时必须沿用同一个右边距，否则文字会被右侧按钮压住。
     */
    val isSideMenuedField: Field? = bridge.findField(contextView, "isSideMenued")

    /** `STYLE_AUDIO_PLAYER`，原版是 0。读常量而不写字面量，宿主重排样式枚举时不至于改错布局。 */
    val styleAudioPlayer: Int =
        (bridge.getStaticField(contextView, "STYLE_AUDIO_PLAYER") as? Number)?.toInt() ?: 0

    /**
     * 迷你播放器上的点击回调。
     *
     * Java lambda 会被编译成宿主类上的合成方法，点击时判断
     * `isMusic() || isVoice()` 决定是打开播放列表还是跳回聊天记录。分支在 lambda 体内拦不到，
     * 只能把整个回调包进伪装里。
     *
     * 不按 `lambda$` 前缀匹配：目标 APK 是 R8 混淆过的，方法名会被改写。改用
     * [Method.isSynthetic] —— 混淆不会清掉 synthetic 标志。签名限定为 `void f(View)`，
     * 命中的就是这个 View 自己的几个点击回调，包裹范围可控。
     */
    val contextViewClickLambdas: List<Method> = if (contextView == null) {
        emptyList()
    } else {
        runCatching {
            contextView.declaredMethods.filter { method ->
                (method.isSynthetic || method.name.startsWith("lambda\$")) &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].name == "android.view.View"
            }.onEach { it.isAccessible = true }
        }.getOrDefault(emptyList())
    }

    /** 播放列表弹层：构造、通知回调、标题刷新都要伪装，否则视频会被 `dismiss()`。 */
    val alertConstructor: java.lang.reflect.Constructor<*>? = runCatching {
        alert?.declaredConstructors?.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0].name == "android.content.Context"
        }?.also { it.isAccessible = true }
    }.getOrNull()

    val alertDidReceivedNotification: Method? =
        bridge.findMethod(alert, "didReceivedNotification", 3)
    val alertUpdateTitle: Method? = bridge.findMethod(alert, "updateTitle", 1) {
        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
    }
    val alertUpdateProgress: List<Method> = listOfNotNull(
        bridge.findMethod(alert, "updateProgress", 1),
        bridge.findMethod(alert, "updateProgress", 2),
    )
    val alertInstanceField: Field? = bridge.findField(alert, "instance")

    /**
     * `LaunchActivity.instance`，回全屏时作为 PhotoViewer 的宿主 Activity。
     *
     * 直接读宿主自己维护的静态实例，不另起生命周期 Hook —— 模块侧再存一份 Activity 引用
     * 等于制造内存泄漏。
     */
    val launchActivityInstanceField: Field? =
        bridge.findField(bridge.loadClass("org.telegram.ui.LaunchActivity"), "instance")

    /** 播放列表条目点击：视频要回到全屏而不是原地播放。 */
    val cellClass: Class<*>? = bridge.loadClass("org.telegram.ui.Cells.AudioPlayerCell")
    val cellDidPressedButton: Method? = bridge.findMethod(cellClass, "didPressedButton", 0)
    val cellGetMessageObject: Method? = bridge.findMethod(cellClass, "getMessageObject", 0)

    /** 列表条目缩略图：视频的封面通常是 stripped/cached 尺寸，原版这两种都不认。 */
    val cellSetMessageObject: Method? = bridge.findMethod(cellClass, "setMessageObject", 5)
    val cellRadialProgressField: Field? = bridge.findField(cellClass, "radialProgress")

    /**
     * `ChatActivity` 里会把「正在播放的视频」当成聊天内嵌播放去清理。
     *
     * 后台播放没有聊天内的 Surface，这几处的 `cleanupPlayer(true, true)` 会直接终止会话。
     */
    val chatActivityClass: Class<*>? = bridge.loadClass("org.telegram.ui.ChatActivity")
    val onRemoveFromParent: Method? = bridge.findMethod(chatActivityClass, "onRemoveFromParent", 0)
    val updateTextureViewPosition: Method? =
        bridge.findMethod(chatActivityClass, "updateTextureViewPosition", 2) {
            it.parameterTypes.all { param -> param == Boolean::class.javaPrimitiveType }
        }

    /**
     * 滚动聊天列表的主路径。
     *
     * 方法体尾部有 `if (checkTextureViewPosition && messageObject.isVideo())
     * cleanupPlayer(true, true)`：后台播放没有聊天内的 TextureView，滚动到播放中的视频离屏时
     * 这个条件必然成立，播放器会被直接销毁 —— 表现就是迷你播放器随机消失。
     */
    val updateMessagesVisiblePart: Method? =
        bridge.findMethod(chatActivityClass, "updateMessagesVisiblePart", 1) {
            it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
}

/**
 * 宿主运行时探测结果。所有成员都可能为 null；调用方必须按能力判断后再使用，缺失时安全降级而不是崩溃。
 */
internal class HostProfile private constructor(
    val bridge: HostBridge,
    val versionCode: Int,
    val versionName: String?,
    val photoViewerClass: Class<*>,
    val mediaControllerClass: Class<*>,
    val messageObjectClass: Class<*>?,
    val videoPlayerClass: Class<*>?,
    val musicPlayerServiceClass: Class<*>?,
    val pipRoundVideoViewClass: Class<*>?,
    val fileLoaderClass: Class<*>?,
    val photo: PhotoViewerMembers,
    val media: MediaControllerMembers,
    val player: VideoPlayerMembers,
    val message: MessageObjectMembers,
    val musicService: MusicServiceMembers,
    val playerUi: PlayerUiMembers,
) {
    /** `MessagesController.getInstance(int)`，用于把 peer 解析成显示名。 */
    val messagesControllerClass: Class<*>? =
        bridge.loadClass("org.telegram.messenger.MessagesController")
    val messagesControllerGetInstance: Method? =
        bridge.findMethod(messagesControllerClass, "getInstance", 1) {
            Modifier.isStatic(it.modifiers) && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }

    /** `MessagesController.getPeerName(long)`，宿主自带的 user/chat/channel 统一取名。 */
    val getPeerName: Method? = bridge.findMethod(messagesControllerClass, "getPeerName", 1) {
        it.parameterTypes[0] == Long::class.javaPrimitiveType
    }

    /** `DialogObject.getPeerDialogId(TLRPC.Peer)`：用户为正、群与频道为负，正好是 [getPeerName] 的入参。 */
    private val dialogObjectClass: Class<*>? =
        bridge.loadClass("org.telegram.messenger.DialogObject")

    private val getPeerDialogId: Method? =
        bridge.findMethod(dialogObjectClass, "getPeerDialogId", 1) {
            // 另一个同名重载收 TLRPC.InputPeer，靠后缀区分。
            Modifier.isStatic(it.modifiers) && it.parameterTypes[0].name.endsWith("\$Peer")
        }

    val tl: TlMembers = TlMembers(bridge)

    /** `UserConfig.selectedAccount`，通知与文件路径都以它为账号基准。 */
    private val userConfigClass: Class<*>? = bridge.loadClass("org.telegram.messenger.UserConfig")

    val selectedAccount: Int
        get() = (bridge.getStaticField(userConfigClass, "selectedAccount") as? Number)?.toInt() ?: 0

    private val userConfigGetInstance: Method? =
        bridge.findMethod(userConfigClass, "getInstance", 1) {
            Modifier.isStatic(it.modifiers) && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }

    private val getClientUserId: Method? = bridge.findMethod(userConfigClass, "getClientUserId", 0)

    /** 当前账号自己的用户 id；用于判断「转发自我自己」。取不到返回 0。 */
    fun clientUserId(account: Int): Long {
        val config = bridge.invoke(null, userConfigGetInstance, account) ?: return 0L
        return (bridge.invoke(config, getClientUserId) as? Number)?.toLong() ?: 0L
    }

    private val applicationLoaderClass: Class<*>? =
        bridge.loadClass("org.telegram.messenger.ApplicationLoader")

    /**
     * 宿主进程的 application context。
     *
     * 通知/媒体会话路径上没有 Activity 可用，取文案需要一个 Context。
     */
    val appContext: Context?
        get() = bridge.getStaticField(applicationLoaderClass, "applicationContext") as? Context

    /**
     * `FileLoader.cancelLoadFile(TLRPC.Document)`。
     *
     * `PhotoViewer.onPhotoClosed` 无条件用它取消当前视频的下载，对刚转入后台播放的流式视频是致命的。
     */
    val cancelLoadFileByDocument: Method? = bridge.findMethod(fileLoaderClass, "cancelLoadFile", 1) {
        it.parameterTypes[0].name.endsWith(".Document")
    }

    /** 宿主已经自带 fork 的整套后台定时播放 UI/API，模块不应重复注入。 */
    val hasCompleteNativeTimerUi: Boolean
        get() = photo.nativeBackgroundStart != null && photo.nativeTimerItemField != null

    /** 能否往 PhotoViewer 的视频菜单里插入定时入口。 */
    val canInjectTimerMenu: Boolean
        get() = photo.setParentActivity != null && photo.getParentActivity != null &&
            photo.videoItemField != null

    /** 能否把 PhotoViewer 的播放器整体交给 MediaController 继续后台播放。 */
    val canStartBackgroundPlayback: Boolean
        get() = media.getInstance != null && media.injectVideoPlayer != null &&
            photo.closePhoto != null && photo.videoPlayerField != null &&
            player.setDelegate != null && musicPlayerServiceClass != null

    /** 能否维护后台播放列表；缺失时后台播放仍可用，只是没有上一个/下一个。 */
    val canManagePlaylist: Boolean
        get() = media.playlistField != null && media.playlistMapField != null &&
            media.currentPlaylistNumField != null

    /**
     * 能否让通知栏走音乐分支。
     *
     * 需要同时具备身份欺骗的落点（`isMusic`）和要包裹的宿主方法，缺一样就退回阶段 1 的朴素通知。
     */
    val canDressNotification: Boolean
        get() = message.isMusic != null && musicService.createNotification != null

    /** 把 peer 解析成显示名，失败返回 null。 */
    fun peerName(account: Int, peerId: Long): String? {
        if (peerId == 0L) return null
        val controller = bridge.invoke(null, messagesControllerGetInstance, account) ?: return null
        return (bridge.invoke(controller, getPeerName, peerId) as? String)?.takeIf {
            it.isNotBlank()
        }
    }

    /** 把 `TLRPC.Peer` 解析成显示名，解析不出返回 null。 */
    fun peerNameOf(account: Int, peer: Any?): String? {
        if (peer == null) return null
        val dialogId = (bridge.invoke(null, getPeerDialogId, peer) as? Number)?.toLong() ?: return null
        return peerName(account, dialogId)
    }

    fun mediaController(): Any? = bridge.invoke(null, media.getInstance)

    fun playerOf(photoViewer: Any?): Any? {
        if (photoViewer == null) return null
        return bridge.invoke(photoViewer, photo.getVideoPlayer)
            ?: bridge.getField(photoViewer, photo.videoPlayerField)
    }

    fun messageOf(photoViewer: Any?): Any? {
        if (photoViewer == null) return null
        return bridge.invoke(photoViewer, photo.getCurrentMessageObject)
            ?: bridge.getField(photoViewer, photo.currentMessageField)
    }

    /**
     * 是否为可后台播放的普通视频（排除实况照片与圆形视频）。
     *
     * 视频判定走静态的 `isVideoMessage(TLRPC.Message)` 而不是实例方法 `isVideo()`：后者是
     * [MessageIdentityMask] 的落点，伪装生效期间返回的是伪造值，会让「这条消息本来是不是视频」
     * 得到自相矛盾的答案 —— 迷你播放器与播放列表恰好整段都在伪装里。静态实现是 `isVideo()`
     * 的本体，没被 Hook，任何时候都说真话。
     */
    fun isVideoMessage(messageObject: Any?): Boolean {
        if (messageObject == null) return false
        if (!isVideoIgnoringMask(messageObject)) return false
        // 这两项没有任何 Spec 会伪造，直接问实例方法即可。
        val isLivePhoto = bridge.invoke(messageObject, message.isLivePhoto) as? Boolean ?: false
        val isRound = bridge.invoke(messageObject, message.isRoundVideo) as? Boolean ?: false
        return !isLivePhoto && !isRound
    }

    private fun isVideoIgnoringMask(messageObject: Any): Boolean {
        val owner = bridge.getField(messageObject, message.messageOwnerField)
        if (owner != null) {
            (bridge.invoke(null, message.isVideoMessageStatic, owner) as? Boolean)?.let { return it }
        }
        // 静态实现或 messageOwner 探测不到时只能退回实例方法，伪装期间的结论可能失真。
        return bridge.invoke(messageObject, message.isVideo) as? Boolean ?: false
    }

    /** 记录所有缺失的关键签名，便于用日志排查宿主版本差异。 */
    fun describeGaps(): String {
        val gaps = buildList {
            if (photo.videoItemField == null) add("PhotoViewer.videoItem")
            if (photo.videoPlayerField == null) add("PhotoViewer.videoPlayer")
            if (photo.closePhoto == null) add("PhotoViewer.closePhoto")
            if (photo.imagesArrField == null) add("PhotoViewer.imagesArr")
            if (media.injectVideoPlayer == null) add("MediaController.injectVideoPlayer")
            if (media.cleanupPlayer4 == null) add("MediaController.cleanupPlayer/4")
            if (media.playMessage == null) add("MediaController.playMessage")
            if (media.updateVideoState == null) add("MediaController.updateVideoState")
            if (media.canStartMusicPlayerService == null) {
                add("MediaController.canStartMusicPlayerService")
            }
            if (media.playlistField == null) add("MediaController.playlist")
            if (media.playlistMapField == null) add("MediaController.playlistMap")
            if (media.currentPlaylistNumField == null) add("MediaController.currentPlaylistNum")
            if (media.pipRoundVideoViewField == null) add("MediaController.pipRoundVideoView")
            if (player.setStreamType == null) add("VideoPlayer.setStreamType")
            if (player.setDelegate == null) add("VideoPlayer.setDelegate")
            if (pipRoundVideoViewClass == null) add("PipRoundVideoView")
            if (musicPlayerServiceClass == null) add("MusicPlayerService")
            if (message.isMusic == null) add("MessageObject.isMusic")
            if (musicService.createNotification == null) {
                add("MusicPlayerService.createNotification")
            }
            if (musicService.updatePlaybackState == null) {
                add("MusicPlayerService.updatePlaybackState")
            }
            if (getPeerName == null) add("MessagesController.getPeerName")
            if (message.isVideoMessageStatic == null) {
                add("MessageObject.isVideoMessage(static)")
            }
            if (getPeerDialogId == null) add("DialogObject.getPeerDialogId")
            if (tl.fwdFrom == null) add("TLRPC.Message.fwd_from")
        }
        return if (gaps.isEmpty()) "无" else gaps.joinToString(", ")
    }

    companion object {
        const val TARGET_PACKAGE = "nu.gpu.nagram"

        fun discover(
            classLoader: ClassLoader,
            logger: (String, Throwable?) -> Unit,
        ): HostProfile? {
            val bridge = HostBridge(classLoader, logger)
            val buildConfig = bridge.loadClass("org.telegram.messenger.BuildConfig")
            val photoViewer = bridge.loadClass("org.telegram.ui.PhotoViewer")
            val mediaController = bridge.loadClass("org.telegram.messenger.MediaController")
            if (buildConfig == null || photoViewer == null || mediaController == null) {
                logger("NagramX 必需类不存在，停止安装 Hook", null)
                return null
            }

            val versionCode = listOf("VERSION_CODE", "OFFICIAL_VERSION_CODE")
                .firstNotNullOfOrNull { field ->
                    (bridge.getStaticField(buildConfig, field) as? Number)?.toInt()
                } ?: -1
            val versionName = listOf("VERSION_NAME", "OFFICIAL_VERSION_NAME")
                .firstNotNullOfOrNull { field ->
                    bridge.getStaticField(buildConfig, field) as? String
                }

            val messageObject = bridge.loadClass("org.telegram.messenger.MessageObject")
            val videoPlayer = bridge.loadClass("org.telegram.ui.Components.VideoPlayer")
            val media = MediaControllerMembers(bridge, mediaController)
            if (media.getInstance == null) {
                logger("找不到 MediaController.getInstance()，停止安装 Hook", null)
                return null
            }

            val musicPlayerService =
                bridge.loadClass("org.telegram.messenger.MusicPlayerService")

            return HostProfile(
                bridge = bridge,
                versionCode = versionCode,
                versionName = versionName,
                photoViewerClass = photoViewer,
                mediaControllerClass = mediaController,
                messageObjectClass = messageObject,
                videoPlayerClass = videoPlayer,
                musicPlayerServiceClass = musicPlayerService,
                pipRoundVideoViewClass =
                    bridge.loadClass("org.telegram.ui.Components.PipRoundVideoView"),
                fileLoaderClass = bridge.loadClass("org.telegram.messenger.FileLoader"),
                photo = PhotoViewerMembers(bridge, photoViewer),
                media = media,
                player = VideoPlayerMembers(bridge, videoPlayer),
                message = MessageObjectMembers(bridge, messageObject),
                musicService = MusicServiceMembers(bridge, musicPlayerService),
                playerUi = PlayerUiMembers(
                    bridge,
                    bridge.loadClass("org.telegram.ui.Components.FragmentContextView"),
                    bridge.loadClass("org.telegram.ui.Components.AudioPlayerAlert"),
                ),
            )
        }
    }
}

/** 宿主 `Context` 便捷取值，避免各处重复 `resources.getIdentifier`。 */
internal fun Context.hostResource(type: String, name: String): Int =
    resources.getIdentifier(name, type, packageName)
