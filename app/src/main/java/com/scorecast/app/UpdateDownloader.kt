package com.scorecast.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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

    /**
     * True only if [apkFile] really is a newer ScoreCast: same package name, and signed by exactly
     * the same certificate(s) as the copy already installed.
     *
     * This is defence in depth, not the primary control — Android's own package installer performs
     * the signature-match check and refuses a mismatched upgrade. What checking here adds:
     *
     *  - The **package-name** check, which the installer's signature enforcement does *not* give us.
     *    An attacker-supplied APK with a different applicationId isn't an "upgrade" at all, so no
     *    signature comparison happens — it would just install alongside ScoreCast as a second app,
     *    wearing our name and icon. That's the repackaging case worth catching.
     *  - A clear, attributable rejection instead of a generic system install failure.
     *
     * Residual window: [DownloadManager] can only write to external storage, and on API 26–28 any
     * app holding WRITE_EXTERNAL_STORAGE can rewrite the file between this check and the install.
     * The installer's own check still catches that; closing it here would mean copying the verified
     * APK into internal storage and serving the install from there.
     */
    fun isTrustedUpdate(context: Context, apkFile: File): Boolean {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apkFile.absolutePath, signingFlags()) ?: return false
        if (archive.packageName != context.packageName) return false

        val downloaded = certificateHashes(archive.signaturesCompat())
        val installed = certificateHashes(
            pm.getPackageInfo(context.packageName, signingFlags()).signaturesCompat()
        )
        return downloaded.isNotEmpty() && downloaded == installed
    }

    fun installIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @Suppress("DEPRECATION")
    private fun signingFlags() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    /** GET_SIGNING_CERTIFICATES (API 28+) actually verifies the APK's signature blocks; the
     *  GET_SIGNATURES fallback on API 26–27 only reads them, which is why the installer's own
     *  check — not this one — remains the control that matters. */
    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.signaturesCompat(): Array<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            } ?: emptyArray()
        } else {
            signatures ?: emptyArray()
        }

    private fun certificateHashes(signatures: Array<Signature>): Set<String> {
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { digest.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
            .toSet()
    }
}
