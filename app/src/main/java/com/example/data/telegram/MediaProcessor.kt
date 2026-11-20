package com.example.data.telegram

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.max

data class PreparedStickerResult(
    val jpegBytes: ByteArray?,
    val inspectionNote: String,
    val requiresManualReview: Boolean = false
)

object MediaProcessor {
    const val MAX_DOWNLOAD_BYTES: Long = 10L * 1024L * 1024L // 10 MB free-tier safety limit
    private const val MAX_TGS_UNZIPPED_BYTES = 2 * 1024 * 1024 // 2 MB zip-bomb cap for .tgs
    private const val MAX_IMAGE_DIMENSION = 1024
    private const val JPEG_QUALITY = 84

    suspend fun readStreamWithLimit(
        inputStream: InputStream,
        maxBytes: Long = MAX_DOWNLOAD_BYTES
    ): ByteArray? = withContext(Dispatchers.IO) {
        val buffer = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream()
        var total = 0L
        inputStream.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    return@withContext null
                }
                out.write(buffer, 0, read)
            }
        }
        out.toByteArray()
    }

    suspend fun readAndResizeUri(
        context: Context,
        uri: Uri,
        maxBytes: Long = MAX_DOWNLOAD_BYTES
    ): ByteArray? = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            readStreamWithLimit(stream, maxBytes)
        } ?: return@withContext null
        downscaleImageIfPossible(raw)
    }

    suspend fun readRawUriBytes(
        context: Context,
        uri: Uri,
        maxBytes: Long = MAX_DOWNLOAD_BYTES
    ): ByteArray? = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            readStreamWithLimit(stream, maxBytes)
        }
    }

    suspend fun downscaleImageIfPossible(rawBytes: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        if (rawBytes.isEmpty()) return@withContext rawBytes

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return@withContext ByteArray(0)
        }

        var sampleSize = 1
        val largest = max(bounds.outWidth, bounds.outHeight)
        while (largest / sampleSize > MAX_IMAGE_DIMENSION * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: return@withContext ByteArray(0)

        bitmapToCompressedJpeg(decoded)
    }

    /**
     * Converts static WEBP, video WEBM, or animated TGS preview bytes into a scannable JPEG frame.
     * Never invents a fake safe image if decoding/rasterization is unsupported.
     */
    suspend fun prepareStickerFrame(
        context: Context,
        rawBytes: ByteArray,
        mimeType: String,
        stickerFormat: String
    ): PreparedStickerResult = withContext(Dispatchers.IO) {
        if (rawBytes.isEmpty()) {
            return@withContext PreparedStickerResult(
                jpegBytes = null,
                inspectionNote = "Empty sticker bytes downloaded."
            )
        }

        when {
            mimeType.startsWith("video/") || stickerFormat == "WEBM_VIDEO" && !mimeType.startsWith("image/") -> {
                val ext = if (mimeType.contains("webm") || stickerFormat == "WEBM_VIDEO") "webm" else "mp4"
                val frame = extractFrameFromVideoBytes(context, rawBytes, ext)
                if (frame != null && frame.isNotEmpty()) {
                    PreparedStickerResult(
                        jpegBytes = frame,
                        inspectionNote = "Extracted key frame from WEBM video sticker."
                    )
                } else {
                    PreparedStickerResult(
                        jpegBytes = null,
                        inspectionNote = "WEBM video sticker codec could not be decoded on this device."
                    )
                }
            }

            mimeType == "application/x-tgsticker" || stickerFormat == "TGS_ANIMATED" && !mimeType.startsWith("image/") -> {
                val summary = inspectTgsMetadataSafe(rawBytes)
                PreparedStickerResult(
                    jpegBytes = null,
                    inspectionNote = summary,
                    requiresManualReview = true
                )
            }

            else -> {
                val jpeg = downscaleImageIfPossible(rawBytes)
                if (jpeg.isNotEmpty()) {
                    PreparedStickerResult(
                        jpegBytes = jpeg,
                        inspectionNote = "Converted ${mimeType.uppercase()} sticker into JPEG inspection frame."
                    )
                } else {
                    PreparedStickerResult(
                        jpegBytes = null,
                        inspectionNote = "BitmapFactory could not decode sticker image ($mimeType)."
                    )
                }
            }
        }
    }

    /**
     * Safely validates & inspects a gzipped Lottie (.tgs) sticker with strict memory cap.
     * Since Lottie JSON vectors require a GPU/canvas Lottie player to rasterize, unscanned .tgs
     * without a Telegram preview thumbnail is routed to UNCERTAIN (never marked SAFE).
     */
    fun inspectTgsMetadataSafe(rawBytes: ByteArray): String {
        return try {
            GZIPInputStream(ByteArrayInputStream(rawBytes)).use { gzip ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var total = 0
                while (true) {
                    val read = gzip.read(buf)
                    if (read == -1) break
                    total += read
                    if (total > MAX_TGS_UNZIPPED_BYTES) {
                        return "Animated .tgs exceeded 2MB unzipped safety cap; marked UNCERTAIN for admin review."
                    }
                    out.write(buf, 0, read)
                }
                val jsonSnippet = out.toString(Charsets.UTF_8.name())
                val looksLikeLottie = jsonSnippet.contains("\"v\"") && jsonSnippet.contains("\"layers\"")
                if (looksLikeLottie) {
                    "Valid .tgs Lottie vector (${total / 1024}KB unzipped) has no raster thumbnail; forwarded as UNCERTAIN for admin review."
                } else {
                    "Invalid .tgs Lottie payload; rejected from SAFE classification."
                }
            }
        } catch (_: Exception) {
            "Could not decompress .tgs sticker stream; rejected from SAFE classification."
        }
    }

    /**
     * Extracts a preview frame from MP4/WebM/GIF video bytes using a temporary cache file that is
     * deleted immediately in finally so no user media persists on disk.
     */
    suspend fun extractFrameFromVideoBytes(
        context: Context,
        videoBytes: ByteArray,
        extension: String = "mp4"
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (videoBytes.isEmpty()) return@withContext null
        val tempFile = File.createTempFile("yuta_frame_", ".$extension", context.cacheDir)
        val retriever = MediaMetadataRetriever()
        try {
            tempFile.writeBytes(videoBytes)
            retriever.setDataSource(tempFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val midpointUs = if (durationMs > 200L) (durationMs / 2L) * 1000L else 0L
            val frame = retriever.getFrameAtTime(midpointUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withContext null
            bitmapToCompressedJpeg(frame)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { tempFile.delete() }
        }
    }

    private fun bitmapToCompressedJpeg(decoded: Bitmap): ByteArray {
        val scaleRatio = minOf(
            1f,
            MAX_IMAGE_DIMENSION.toFloat() / max(decoded.width, decoded.height).toFloat()
        )
        val scaledBitmap = if (scaleRatio < 1f) {
            val w = (decoded.width * scaleRatio).toInt().coerceAtLeast(1)
            val h = (decoded.height * scaleRatio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, w, h, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        val composited = Bitmap.createBitmap(
            scaledBitmap.width.coerceAtLeast(1),
            scaledBitmap.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        try {
            val canvas = Canvas(composited)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
            return ByteArrayOutputStream().use { out ->
                composited.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            scaledBitmap.recycle()
            composited.recycle()
        }
    }
}
