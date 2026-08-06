package com.shitianyaa.nagramx.videotimer

import android.app.Activity
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.service.XposedService

class MainActivity : Activity(), App.ServiceStateListener {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
            textSize = 16f
        }
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            addView(status)
        })
        updateStatus(App.service)
    }

    override fun onStart() {
        super.onStart()
        App.addServiceStateListener(this)
    }

    override fun onStop() {
        App.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        runOnUiThread { updateStatus(service) }
    }

    private fun updateStatus(service: XposedService?) {
        val serviceStatus = if (service == null) {
            listOf(
                getString(R.string.module_status_unavailable),
                getString(R.string.module_status_hint),
            )
        } else {
            listOf(
                getString(R.string.module_status_ready),
                getString(R.string.module_api, service.apiVersion),
                getString(R.string.module_scope, service.scope.toString()),
            )
        }
        status.text = buildList {
            add(getString(R.string.app_name))
            add("")
            addAll(serviceStatus)
            add("")
            add(targetStatus())
        }.joinToString("\n")
    }

    private fun targetStatus(): String {
        val packageInfo = getTargetPackageInfo()
            ?: return getString(R.string.target_status_missing, HostProfile.TARGET_PACKAGE)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val versionName = packageInfo.versionName.orEmpty().ifEmpty { "unknown" }
        return getString(
            R.string.target_status_detected,
            HostProfile.TARGET_PACKAGE,
            versionName,
            versionCode,
        )
    }

    private fun getTargetPackageInfo(): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                HostProfile.TARGET_PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(HostProfile.TARGET_PACKAGE, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
