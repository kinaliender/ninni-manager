package com.ender.ninniplayer

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import com.ender.ninni.protocol.Protocol
import java.io.ByteArrayOutputStream

/**
 * A small JPEG of the current track's cover, for the remotes. Low resolution on purpose: it
 * travels over Bluetooth page by page. Decoding and encoding run on a background thread; the
 * result is published on the main thread.
 */
class Artwork(
    private val context: Context,
    private val handler: Handler,
    private val onChange: () -> Unit,
) {
    private companion object {
        const val SIZE = 160
        const val QUALITY = 70
        val BITMAP_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )
        val URI_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )
        /** Only local images: the player phone is usually offline. */
        val LOCAL_SCHEMES = setOf(
            ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE, ContentResolver.SCHEME_ANDROID_RESOURCE,
        )
    }

    private val thread = HandlerThread("artwork").apply { start() }
    private val worker = Handler(thread.looper)
    private var generation = 0

    /** JPEG bytes of the cover; null = no cover. Main thread only. */
    var bytes: ByteArray? = null
        private set

    /** [Protocol.blobId] of [bytes]. */
    var id: Int? = null
        private set

    /** Main thread. Takes the cover from the metadata (a bitmap, or else a local image URI). */
    fun update(metadata: MediaMetadata?) {
        val gen = ++generation
        val bitmap = metadata?.let { m -> BITMAP_KEYS.firstNotNullOfOrNull { m.getBitmap(it) } }
        val uri = if (bitmap == null) metadata?.let(::localUri) else null
        worker.post {
            val jpeg = runCatching { encode(bitmap ?: uri?.let(::load)) }.getOrNull()
            handler.post {
                if (gen != generation) return@post // a newer track is already on its way
                val newId = jpeg?.let(Protocol::blobId)
                if (newId == id) return@post
                bytes = jpeg
                id = newId
                onChange()
            }
        }
    }

    fun release() {
        worker.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private fun localUri(m: MediaMetadata): Uri? = URI_KEYS.asSequence()
        .mapNotNull { m.getString(it)?.ifEmpty { null } }
        .map(Uri::parse)
        .firstOrNull { it.scheme in LOCAL_SCHEMES }

    private fun load(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Centre square, scaled to [SIZE] × [SIZE] (halving first, so big covers stay smooth). */
    private fun encode(source: Bitmap?): ByteArray? {
        if (source == null || source.width <= 0 || source.height <= 0) return null
        val soft = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
        val side = minOf(soft.width, soft.height)
        var square = Bitmap.createBitmap(soft, (soft.width - side) / 2, (soft.height - side) / 2, side, side)
        while (square.width >= SIZE * 2) {
            square = Bitmap.createScaledBitmap(square, square.width / 2, square.height / 2, true)
        }
        val out = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val stream = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, QUALITY, stream)
        return stream.toByteArray().takeIf { it.size <= Protocol.MAX_BLOB }
    }
}
