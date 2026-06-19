package com.scorecast.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the capture + RTMP pipeline alive when the app is backgrounded
 * (spec §10). Android 14 requires the camera|microphone service type both in the manifest and at
 * [ServiceCompat.startForeground].
 */
class StreamingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
                scope.launch { StreamerHolder.start(applicationContext, url, key) }
            }
            ACTION_STOP -> {
                scope.launch {
                    StreamerHolder.stop()
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        createChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Live stream running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "scorecast_stream"
        private const val NOTIF_ID = 1001
        private const val ACTION_START = "com.scorecast.app.START"
        private const val ACTION_STOP = "com.scorecast.app.STOP"
        private const val EXTRA_URL = "url"
        private const val EXTRA_KEY = "key"

        fun start(context: Context, ingestUrl: String, streamKey: String) {
            val intent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, ingestUrl)
                putExtra(EXTRA_KEY, streamKey)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, StreamingService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
