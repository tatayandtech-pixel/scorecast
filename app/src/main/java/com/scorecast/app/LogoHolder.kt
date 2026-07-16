package com.scorecast.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Owner request: two fixed corners instead of free placement — "logo" always top-left, sponsor
 * logo always top-right. */
enum class LogoSlot { TOP_LEFT, TOP_RIGHT }

data class LogoEntry(
    val id: String = UUID.randomUUID().toString(),
    val uriString: String,
    val slot: LogoSlot,
)

/** Manages logo bitmaps and their layout metadata. Logos stay device-local (spec §7). */
object LogoHolder {
    private val _logos = MutableStateFlow<List<LogoEntry>>(emptyList())
    val logos: StateFlow<List<LogoEntry>> = _logos.asStateFlow()

    private val bitmaps = mutableMapOf<String, Bitmap>()

    fun getBitmap(id: String): Bitmap? = bitmaps[id]

    fun addLogo(context: Context, uri: Uri, slot: LogoSlot): Boolean {
        val bmp = decodeSampled(context, uri, maxSide = 512) ?: return false
        val entry = LogoEntry(uriString = uri.toString(), slot = slot)
        bitmaps[entry.id] = bmp
        _logos.value = _logos.value + entry
        return true
    }

    fun removeLogo(id: String) {
        bitmaps.remove(id)?.recycle()
        _logos.value = _logos.value.filter { it.id != id }
    }

    private fun decodeSampled(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val sample = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / maxSide)
        val full = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, full) }
    } catch (_: Exception) { null }
}
