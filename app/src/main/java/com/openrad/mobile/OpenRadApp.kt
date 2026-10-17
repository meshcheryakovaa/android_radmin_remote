package com.openrad.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.openrad.mobile.R

class OpenRadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.openrad.mobile.web.DiagnosticLog.start(kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default))
        val channelId = getString(R.string.vpn_channel_id)
        val name = getString(R.string.vpn_channel_name)
        val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
