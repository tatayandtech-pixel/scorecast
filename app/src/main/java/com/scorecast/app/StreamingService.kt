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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class StreamingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    @androidx.annotation.RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
                val modeOrdinal = intent.getIntExtra(EXTRA_MODE, RecordingMode.STREAM_ONLY.ordinal)
                val mode = RecordingMode.entries[modeOrdinal]
                val filePath = intent.getStringExtra(EXTRA_FILE)
                val file = filePath?.let { File(it) }
                scope.launch { StreamerHolder.start(applicationContext, url, key, mode, file) }
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

        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW
                ))
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL_ID = "scorecast_stream"
        private const val NOTIF_ID = 1001
        private const val ACTION_START = "com.scorecast.app.START"
        private const val ACTION_STOP = "com.scorecast.app.STOP"
        private const val EXTRA_URL = "url"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_FILE = "file"

        fun start(
            context: Context,
            ingestUrl: String,
            streamKey: String,
            mode: RecordingMode = RecordingMode.STREAM_ONLY,
            recordingFile: File? = null,
        ) {
            val intent = Intent(context, StreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, ingestUrl)
                putExtra(EXTRA_KEY, streamKey)
                putExtra(EXTRA_MODE, mode.ordinal)
                recordingFile?.let { putExtra(EXTRA_FILE, it.absolutePath) }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StreamingService::class.java).apply { action = ACTION_STOP })
        }
    }
}
