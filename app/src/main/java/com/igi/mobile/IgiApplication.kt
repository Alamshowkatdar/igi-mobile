package com.igi.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.igi.mobile.data.GameRepository

class IgiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createDownloadChannel()
        GameRepository.get(this).warmUp()
    }

    private fun createDownloadChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_DOWNLOAD,
            "Game data downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress while game data is prepared for offline play"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_DOWNLOAD = "igi_download"
    }
}
