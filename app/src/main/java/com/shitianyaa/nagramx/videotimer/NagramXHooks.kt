package com.shitianyaa.nagramx.videotimer

import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule

internal class NagramXHooks(
    private val module: XposedModule,
    private val profile: HostProfile,
) {
    private val coordinator = PlaybackCoordinator(module, profile)

    fun install() {
        if (profile.hasCompleteNativeTimerUi) {
            log(
                "宿主已完整提供后台定时播放 UI/API，模块不重复注入：" +
                    "version=${profile.versionName}/${profile.versionCode}",
            )
            return
        }
        val setParentActivity = profile.setParentActivity
        if (!profile.canInjectTimerUi || setParentActivity == null) {
            log("宿主缺少可注入的 PhotoViewer 菜单生命周期，模块保持停用")
            return
        }

        hookPhotoViewerLifecycle(setParentActivity)
        profile.cleanupPlayer?.let(::hookMediaControllerCleanup)
        log(
            "Hook 已安装：version=${profile.versionName}/${profile.versionCode}, " +
                "nativeBackground=${profile.hasNativeBackgroundPlayback}, " +
                "photoViewerPip=${profile.canKeepPhotoViewerForTimer}, " +
                "legacyTransfer=${profile.transferPlayerMethod != null}",
        )
    }

    private fun hookPhotoViewerLifecycle(method: java.lang.reflect.Method) {
        module.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                coordinator.scheduleMenuInjection(chain.thisObject)
                result
            }
    }

    private fun hookMediaControllerCleanup(method: java.lang.reflect.Method) {
        module.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val args = chain.args
                val transferToViewer = args.size >= 4 && args[3] == true
                coordinator.onMediaControllerCleanup(chain.thisObject, transferToViewer)
                chain.proceed()
            }
    }

    private fun log(message: String, throwable: Throwable? = null) {
        module.log(Log.INFO, TAG, message, throwable)
    }

    companion object {
        private const val TAG = "NagramXVideoTimer"
    }
}
