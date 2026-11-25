package dev.aiauto.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.aiauto.AppLog
import dev.aiauto.core.TaskImage
import dev.aiauto.core.TaskImages
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Turns pictures shared from another app into what Claude takes: scaled down, as JPEG, at most [TaskImages.MAX]. */
object SharedImages {
    private const val QUALITY = 85

    /** Reads and shrinks each picture. Blocking, so call it off the main thread. Ones that can't be read are skipped. */
    fun load(context: Context, uris: List<Uri>): List<TaskImage> = uris.take(TaskImages.MAX).mapNotNull { uri ->
        runCatching { read(context, uri) }.onFailure { AppLog.w("Couldn't read a shared picture", it) }.getOrNull()
    }

    private fun read(context: Context, uri: Uri): TaskImage? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = TaskImages.sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val (width, height) = TaskImages.scaledSize(decoded.width, decoded.height)
        val scaled = if (width == decoded.width && height == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, width, height, true)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        if (bytes.size > TaskImages.MAX_BYTES) return null
        return TaskImage("image/jpeg", Base64.getEncoder().encodeToString(bytes))
    }
}
