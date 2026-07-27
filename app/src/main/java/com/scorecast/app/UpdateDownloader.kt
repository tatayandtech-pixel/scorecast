package com.scorecast.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Downloads a release APK asset (Phase 9) into the app's own external files dir via
 *  [DownloadManager], then builds an install [Intent] through the existing FileProvider. */
object UpdateDownloader {

    private const val SUBDIR = "updates"

    /** Enqueues the download and polls until it finishes; returns the local file, or null on failure. */
    suspend fun downloadApk(context: Context, downloadUrl: String, fileName: String): File? =
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setDestinationInExternalFilesDir(context, SUBDIR, fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setTitle("ScoreCast update")
            val id = manager.enqueue(request)

            var finished = false
            var succeeded = false
            while (!finished) {
                manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        finished = true
                    } else {
                        when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_SUCCESSFUL -> { finished = true; succeeded = true }
                            DownloadManager.STATUS_FAILED -> finished = true
                        }
                    }
                }
                if (!finished) delay(500)
            }

            if (succeeded) File(context.getExternalFilesDir(SUBDIR), fileName).takeIf { it.exists() } else null
        }

    fun installIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
