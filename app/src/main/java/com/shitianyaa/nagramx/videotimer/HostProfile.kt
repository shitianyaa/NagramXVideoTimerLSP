package com.shitianyaa.nagramx.videotimer

import android.app.Activity
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
    val nativeTimerUiField: Field?,
    val videoMenuField: Field?,
    val transferPlayerMethod: Method?,
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

    fun currentMediaController(): Any? = bridge.invoke(null, getMediaController)

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
                nativeTimerUiField = bridge.findField(photoViewer, "videoSleepTimerItem"),
                videoMenuField = bridge.findField(photoViewer, "videoItem"),
                transferPlayerMethod = bridge.findMethod(
                    photoViewer,
                    "injectVideoPlayerToMediaController",
                    0,
                ),
                controllerVideoPlayerField = bridge.findField(mediaController, "videoPlayer"),
                timerDurationMode = timerDurationMode,
                timerAfterCurrentMode = timerAfterCurrentMode,
            )
        }
    }
}
