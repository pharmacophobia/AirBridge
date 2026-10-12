package com.airbridge.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class AirBridgeApplication : Application() {

    companion object {
        const val CHANNEL_ID_MIC = "airbridge_mic_channel"
        const val CHANNEL_ID_TRANSFERS = "airbridge_transfers_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val micChannel = NotificationChannel(
                CHANNEL_ID_MIC,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }

            val transferChannel = NotificationChannel(
                CHANNEL_ID_TRANSFERS,
                "AirBridge Transfers",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for image and transcription transfers"
            }

            notificationManager.createNotificationChannel(micChannel)
            notificationManager.createNotificationChannel(transferChannel)
        }
    }
}
