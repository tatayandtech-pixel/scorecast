package com.scorecast.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingsManager {

    fun newRecordingFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "scorecast_$stamp.mp4")
    }

    fun newPhotoFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "scorecast_$stamp.jpg")
    }

    /** Writes [bitmap] into a fresh file from [newPhotoFile] as a JPEG and returns it. */
    fun savePhoto(context: Context, bitmap: Bitmap): File {
        val file = newPhotoFile(context)
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        return file
    }

    /** Videos and captured photos together, newest first — one unified media list. */
    fun listRecordings(context: Context): List<File> {
        val videos = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?.listFiles { f -> f.extension.equals("mp4", ignoreCase = true) }
            ?.toList() ?: emptyList()
        val photos = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) }
            ?.toList() ?: emptyList()
        return (videos + photos).sortedByDescending { it.lastModified() }
    }

    fun deleteRecording(file: File) { file.delete() }

    fun deleteAll(context: Context) {
        listRecordings(context).forEach { it.delete() }
    }

    private fun mimeTypeFor(file: File): String =
        if (file.extension.equals("jpg", ignoreCase = true)) "image/jpeg" else "video/mp4"

    fun playIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeFor(file)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share recording"
        )
    }
}
