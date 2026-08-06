package com.shitianyaa.nagramx.videotimer

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.Volatile

class App : Application(), XposedServiceHelper.OnServiceListener {
    companion object {
        @Volatile
        var service: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()

        fun addServiceStateListener(listener: ServiceStateListener) {
            listeners.add(listener)
            listener.onServiceStateChanged(service)
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(boundService: XposedService) {
        service = boundService
        listeners.forEach { it.onServiceStateChanged(boundService) }
    }

    override fun onServiceDied(deadService: XposedService) {
        service = null
        listeners.forEach { it.onServiceStateChanged(null) }
    }

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }
}
