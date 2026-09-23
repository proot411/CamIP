package com.camip.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Size
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * A second, independent Camera2 output target (YUV_420_888, low resolution)
 * used only to feed the MJPEG fallback endpoint. This runs alongside the
 * primary MediaCodec Surface target in the same capture session, but is
 * throttled to a much lower frame rate and resolution so it stays cheap and
 * never competes meaningfully with the primary H.264 pipeline for CPU time.
 *
 * The ImageReader size is negotiated against what the camera actually
 * advertises (see [chooseSize]): hardcoding 640x360 fails session
 * configuration outright on devices that don't expose that exact size,
 * which takes the primary H.264 stream down with it.
 */
class MjpegFrameSource(
    width: Int,
    height: Int,
    private val targetFps: Int,
    initialQuality: Int,
    private val onJpegFrame: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "MjpegFrameSource"

        /**
         * Picks the YUV_420_888 output size closest to (but not below) the
         * requested one, preferring matching aspect ratio, and falls back to
         * the closest available size when the camera cannot do better.
         */
        fun chooseSize(context: Context, targetWidth: Int, targetHeight: Int): Size {
            val fallback = Size(targetWidth, targetHeight)
            val sizes: List<Size> = try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager
                val cameraId = manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: manager.cameraIdList.firstOrNull()

                if (cameraId == null) {
                    emptyList()
                } else {
                    val map = manager.getCameraCharacteristics(cameraId)
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val raw = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
                    raw.map { Size(it.width, it.height) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "chooseSize: camera probe failed: ${e.message}")
                emptyList()
            }
            if (sizes.isEmpty()) return fallback

            val targetArea = targetWidth.toLong() * targetHeight
            val byArea = sizes.sortedBy { it.width.toLong() * it.height }

            // Prefer sizes sharing the target aspect ratio when any exist.
            val targetRatio = targetWidth.toDouble() / targetHeight
            val sameAspect = byArea.filter { abs(it.width.toDouble() / it.height - targetRatio) < 0.05 }
            val pool = sameAspect.ifEmpty { byArea }

            // Smallest size at or above the target (so quality never drops
            // below what was asked for), but not so large it triples the
            // per-frame JPEG cost; otherwise the best smaller one.
            return pool.firstOrNull {
                val area = it.width.toLong() * it.height
                area >= targetArea && area <= targetArea * 3
            } ?: pool.lastOrNull { it.width.toLong() * it.height < targetArea }
                ?: pool.last()
                .also { Log.w(TAG, "chooseSize: no ideal size for ${targetWidth}x$targetHeight, using $it") }
        }
    }

    private val minFrameIntervalMs = 1000L / targetFps.coerceAtLeast(1)
    private var lastEmitTimeMs = 0L

    // JPEG quality is adjustable at runtime (UI slider + /api/control) without
    // rebuilding the capture session, so it must be volatile: the camera
    // thread reads it while the HTTP/UI threads write it.
    @Volatile
    private var jpegQuality: Int = initialQuality.coerceIn(30, 100)

    /** Applies a new JPEG quality (30..100) to subsequent frames immediately. */
    fun setQuality(quality: Int) {
        jpegQuality = quality.coerceIn(30, 100)
    }

    // Reused across frames to avoid churning the allocator at 15+ fps.
    private var nv21Buffer: ByteArray? = null
    private var uRowScratch: ByteArray? = null
    private var vRowScratch: ByteArray? = null
    private var jpegOut: ByteArrayOutputStream? = null

    val imageReader: ImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)

    fun start(handler: Handler) {
        imageReader.setOnImageAvailableListener({ reader ->
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (image != null) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastEmitTimeMs >= minFrameIntervalMs) {
                        lastEmitTimeMs = now
                        val jpeg = imageToJpeg(image, jpegQuality)
                        if (jpeg != null) onJpegFrame(jpeg)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to convert frame to JPEG: ${e.message}")
                } finally {
                    image.close()
                }
            }
        }, handler)
    }

    private fun imageToJpeg(image: Image, quality: Int): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null

        val w = image.width
        val h = image.height
        val required = w * h + w * h / 2
        var nv21 = nv21Buffer
        if (nv21 == null || nv21.size != required) {
            nv21 = ByteArray(required)
            nv21Buffer = nv21
        }
        yuv420888ToNv21(image, nv21)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = jpegOut ?: ByteArrayOutputStream(64 * 1024).also { jpegOut = it }
        out.reset()
        val ok = yuvImage.compressToJpeg(Rect(0, 0, w, h), quality, out)
        return if (ok) out.toByteArray() else null
    }

    /**
     * Converts a YUV_420_888 Image's three planes into NV21 (Y plane, then
     * interleaved V,U), writing into [dst].
     *
     * The common layouts take bulk ByteBuffer reads (contiguous rows, one
     * byte per pixel) instead of a per-pixel Kotlin loop, which previously
     * cost several milliseconds per frame and ate the MJPEG frame budget.
     */
    private fun yuv420888ToNv21(image: Image, dst: ByteArray) {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var pos = 0

        val yContiguous = yPixelStride == 1 &&
            yBuffer.limit() >= (height - 1) * yRowStride + width
        if (yContiguous) {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(dst, pos, width)
                pos += width
            }
        } else {
            for (row in 0 until height) {
                val rowStart = row * yRowStride
                for (col in 0 until width) {
                    dst[pos++] = yBuffer.get(rowStart + col * yPixelStride)
                }
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        // NV21 stores chroma as interleaved V,U; both planes are typically
        // packed one-byte-per-pixel, so read each row in bulk and interleave.
        val chromaPacked = uPixelStride == 1 && vPixelStride == 1 &&
            uBuffer.limit() >= (chromaHeight - 1) * uRowStride + chromaWidth &&
            vBuffer.limit() >= (chromaHeight - 1) * vRowStride + chromaWidth

        var chromaPos = width * height
        if (chromaPacked) {
            val uRow: ByteArray = uRowScratch?.takeIf { it.size == chromaWidth }
                ?: ByteArray(chromaWidth).also { uRowScratch = it }
            val vRow: ByteArray = vRowScratch?.takeIf { it.size == chromaWidth }
                ?: ByteArray(chromaWidth).also { vRowScratch = it }
            for (row in 0 until chromaHeight) {
                uBuffer.position(row * uRowStride)
                uBuffer.get(uRow, 0, chromaWidth)
                vBuffer.position(row * vRowStride)
                vBuffer.get(vRow, 0, chromaWidth)
                for (i in 0 until chromaWidth) {
                    dst[chromaPos++] = vRow[i]
                    dst[chromaPos++] = uRow[i]
                }
            }
        } else {
            for (row in 0 until chromaHeight) {
                val uRowStart = row * uRowStride
                val vRowStart = row * vRowStride
                for (col in 0 until chromaWidth) {
                    dst[chromaPos++] = vBuffer.get(vRowStart + col * vPixelStride)
                    dst[chromaPos++] = uBuffer.get(uRowStart + col * uPixelStride)
                }
            }
        }
    }

    fun close() {
        try {
            imageReader.close()
        } catch (_: Exception) {
        }
        nv21Buffer = null
        uRowScratch = null
        vRowScratch = null
        jpegOut = null
    }
}
