package com.shitianyaa.nagramx.videotimer

import android.app.Activity
import android.content.Context
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class HostProfile(
    val bridge: HostBridge,
    val photoViewer: Class<*>,
    val mediaController: Class<*>,
    val musicPlayerService: Class<*>?,
    val versionCode: Int,
    val versionName: String?,
    val setParentActivity: Method?,
    val getParentActivity: Method?,
    val closePhoto: Method?,
    val getMediaController: Method,
    val cleanupPlayer: Method?,
    val nativeBackgroundStart: Method?,
    val nativeTimerClear: Method?,
    val nativeTimerMode: Method?,
    val nativeTimerRemaining: Method?,
    val nativeTimerSheet: Method?,
    val nativeTimerUiField: Field?,
    val videoMenuField: Field?,
    val photoVideoPlayerField: Field?,
    val photoCurrentMessageField: Field?,
    val photoGetVideoPlayerMethod: Method?,
    val photoGetCurrentMessageMethod: Method?,
    val photoPlayMethod: Method?,
    val photoPauseMethod: Method?,
    val photoSwitchToPipMethod: Method?,
    val photoPipInstanceField: Field?,
    val photoIsInlineField: Field?,
    val photoTextureUploadedField: Field?,
    val photoManuallyPausedField: Field?,
    val photoPlayerTransferredField: Field?,
    val photoPlayerInjectedField: Field?,
    val photoIsPlayingField: Field?,
    val pipOverlayIsVisibleMethod: Method?,
    val pipPermissionCheckMethod: Method?,
    val transferPlayerMethod: Method?,
    val legacyInjectPlayerMethod: Method?,
    val legacyTimerLayoutClass: Class<*>?,
    val controllerVideoPlayerField: Field?,
    val timerDurationMode: Int,
    val timerAfterCurrentMode: Int,
) {
    val hasNativeBackgroundPlayback: Boolean
        get() = nativeBackgroundStart != null

    val hasCompleteNativeTimerUi: Boolean
        get() = nativeBackgroundStart != null && nativeTimerUiField != null

    val canInjectTimerUi: Boolean
        get() = setParentActivity != null && getParentActivity != null && closePhoto != null &&
            videoMenuField != null

    val canKeepPhotoViewerForTimer: Boolean
        get() = photoSwitchToPipMethod != null &&
            (photoGetVideoPlayerMethod != null || photoVideoPlayerField != null)

    fun currentMediaController(): Any? = bridge.invoke(null, getMediaController)

    fun currentVideoPlayer(photoViewer: Any?): Any? {
        return bridge.invoke(photoViewer, photoGetVideoPlayerMethod)
            ?: bridge.getField(photoViewer, photoVideoPlayerField)
    }

    fun currentMessage(photoViewer: Any?): Any? {
        return bridge.invoke(photoViewer, photoGetCurrentMessageMethod)
            ?: bridge.getField(photoViewer, photoCurrentMessageField)
    }

    fun isViewerInPip(photoViewer: Any?): Boolean {
        if (photoViewer == null) return false
        if (bridge.getField(photoViewer, photoIsInlineField) == true) return true
        if (bridge.getStaticField(photoPipInstanceField) === photoViewer) return true
        return bridge.invoke(null, pipOverlayIsVisibleMethod) == true
    }

    fun hasPipPermission(context: Context): Boolean? {
        return bridge.invoke(null, pipPermissionCheckMethod, context) as? Boolean
    }

    fun isNativeTimerActive(controller: Any?): Boolean {
        val mode = bridge.invoke(controller, nativeTimerMode) as? Number
        if (mode != null) return mode.toInt() != MODE_OFF
        val remaining = bridge.invoke(controller, nativeTimerRemaining) as? Number
        return remaining?.toLong()?.let { it > 0L } == true
    }

    companion object {
        const val TARGET_PACKAGE = "nu.gpu.nagram"
        const val MODE_OFF = 0

        fun discover(
            classLoader: ClassLoader,
            logger: (String, Throwable?) -> Unit,
        ): HostProfile? {
            val bridge = HostBridge(classLoader, logger)
            val buildConfig = bridge.loadClass("org.telegram.messenger.BuildConfig")
            val photoViewer = bridge.loadClass("org.telegram.ui.PhotoViewer")
            val mediaController = bridge.loadClass("org.telegram.messenger.MediaController")
            val pipVideoOverlay = bridge.loadClass("org.telegram.ui.Components.PipVideoOverlay")
            val pipUtils = bridge.loadClass("org.telegram.messenger.pip.utils.PipUtils")
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

            val getMediaController = bridge.findMethod(mediaController, "getInstance", 0) {
                Modifier.isStatic(it.modifiers)
            }
            if (getMediaController == null) {
                logger("找不到 MediaController.getInstance()，停止安装 Hook", null)
                return null
            }

            val timerDurationMode = (bridge.getStaticField(
                mediaController,
                "VIDEO_SLEEP_TIMER_DURATION",
            ) as? Number)?.toInt() ?: 1
            val timerAfterCurrentMode = (bridge.getStaticField(
                mediaController,
                "VIDEO_SLEEP_TIMER_AFTER_CURRENT",
            ) as? Number)?.toInt() ?: 2

            return HostProfile(
                bridge = bridge,
                photoViewer = photoViewer,
                mediaController = mediaController,
                musicPlayerService = bridge.loadClass("org.telegram.messenger.MusicPlayerService"),
                versionCode = versionCode,
                versionName = versionName,
                setParentActivity = bridge.findMethod(photoViewer, "setParentActivity", 3) {
                    it.parameterTypes.firstOrNull() == Activity::class.java
                },
                getParentActivity = bridge.findMethod(photoViewer, "getParentActivity", 0) {
                    Activity::class.java.isAssignableFrom(it.returnType)
                },
                closePhoto = bridge.findMethod(photoViewer, "closePhoto", 2) {
                    it.parameterTypes.all { type -> type == Boolean::class.javaPrimitiveType }
                },
                getMediaController = getMediaController,
                cleanupPlayer = bridge.findMethod(mediaController, "cleanupPlayer", 4) {
                    it.parameterTypes.all { type -> type == Boolean::class.javaPrimitiveType }
                },
                nativeBackgroundStart = bridge.findMethod(
                    photoViewer,
                    "startVideoBackgroundPlayback",
                    2,
                ) {
                    it.returnType == Boolean::class.javaPrimitiveType &&
                        it.parameterTypes.all { type -> type == Int::class.javaPrimitiveType }
                },
                nativeTimerClear = bridge.findMethod(mediaController, "clearVideoSleepTimer", 0),
                nativeTimerMode = bridge.findMethod(mediaController, "getVideoSleepTimerMode", 0),
                nativeTimerRemaining = bridge.findMethod(
                    mediaController,
                    "getVideoSleepTimerRemainingMs",
                    0,
                ),
                nativeTimerSheet = bridge.findMethod(photoViewer, "showVideoSleepTimerSheet", 0),
                nativeTimerUiField = bridge.findField(photoViewer, "videoSleepTimerItem"),
                videoMenuField = bridge.findField(photoViewer, "videoItem"),
                photoVideoPlayerField = bridge.findField(photoViewer, "videoPlayer"),
                photoCurrentMessageField = bridge.findField(photoViewer, "currentMessageObject"),
                photoGetVideoPlayerMethod = bridge.findMethod(photoViewer, "getVideoPlayer", 0),
                photoGetCurrentMessageMethod = bridge.findMethod(
                    photoViewer,
                    "getCurrentMessageObject",
                    0,
                ),
                photoPlayMethod = bridge.findMethod(photoViewer, "playVideoOrWeb", 0),
                photoPauseMethod = bridge.findMethod(photoViewer, "pauseVideoOrWeb", 0),
                photoSwitchToPipMethod = bridge.findMethod(
                    photoViewer,
                    "switchToPip",
                    Boolean::class.javaPrimitiveType!!,
                ),
                photoPipInstanceField = bridge.findField(photoViewer, "PipInstance"),
                photoIsInlineField = bridge.findField(photoViewer, "isInline"),
                photoTextureUploadedField = bridge.findField(photoViewer, "textureUploaded"),
                photoManuallyPausedField = bridge.findField(photoViewer, "manuallyPaused"),
                photoPlayerTransferredField = bridge.findField(
                    photoViewer,
                    "playerTransferredToMediaController",
                ),
                photoPlayerInjectedField = bridge.findField(photoViewer, "playerInjected"),
                photoIsPlayingField = bridge.findField(photoViewer, "isPlaying"),
                pipOverlayIsVisibleMethod = bridge.findMethod(pipVideoOverlay, "isVisible", 0) {
                    Modifier.isStatic(it.modifiers) &&
                        it.returnType == Boolean::class.javaPrimitiveType
                },
                pipPermissionCheckMethod = bridge.findMethod(
                    pipUtils,
                    "checkAnyPipPermissions",
                    1,
                ) {
                    Modifier.isStatic(it.modifiers) &&
                        it.returnType == Boolean::class.javaPrimitiveType &&
                        Context::class.java.isAssignableFrom(it.parameterTypes[0])
                },
                transferPlayerMethod = bridge.findMethod(
                    photoViewer,
                    "injectVideoPlayerToMediaController",
                    0,
                ),
                legacyInjectPlayerMethod = bridge.findMethod(mediaController, "injectVideoPlayer", 2) {
                    !Modifier.isStatic(it.modifiers) &&
                        it.parameterTypes[0].name.endsWith(".VideoPlayer") &&
                        it.parameterTypes[1].name.endsWith(".MessageObject")
                },
                legacyTimerLayoutClass = bridge.loadClass("org.telegram.ui.VideoSleepTimerLayout"),
                controllerVideoPlayerField = bridge.findField(mediaController, "videoPlayer"),
                timerDurationMode = timerDurationMode,
                timerAfterCurrentMode = timerAfterCurrentMode,
            )
        }
    }
}
