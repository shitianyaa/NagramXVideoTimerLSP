package com.shitianyaa.nagramx.videotimer

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class ModuleMainKt : XposedModule() {
    private var isMainProcess = false
    private var hooksInstalled = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        isMainProcess = param.processName == HostProfile.TARGET_PACKAGE
        log(
            Log.INFO,
            TAG,
            "模块已加载：process=${param.processName}, framework=$frameworkName/$frameworkVersionCode, api=$apiVersion",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!isMainProcess || hooksInstalled || !param.isFirstPackage ||
            param.packageName != HostProfile.TARGET_PACKAGE
        ) {
            return
        }

        val logger: (String, Throwable?) -> Unit = { message, throwable ->
            log(if (throwable == null) Log.INFO else Log.WARN, TAG, message, throwable)
        }
        try {
            val profile = HostProfile.discover(param.classLoader, logger)
            if (profile == null) return
            NagramXHooks(this, profile).install()
            hooksInstalled = true
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "安装 NagramX Hook 失败", t)
        }
    }

    companion object {
        private const val TAG = "NagramXVideoTimer"
    }
}
