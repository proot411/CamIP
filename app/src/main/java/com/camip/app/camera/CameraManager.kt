package com.camip.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Thin, focused wrapper around Camera2 that opens a camera, starts a
 * repeating capture session targeting the encoder's input Surface (plus an
 * optional local preview Surface), and exposes simple runtime controls
 * (zoom, flash, focus, camera switch) needed by the HTTP API and Compose UI.
 */
class CamCameraManager(private val context: Context) {

    interface Callback {
        fun onCameraError(message: String)
        fun onCameraDisconnected()
    }

    companion object {
        private const val TAG = "CamCameraManager"

        /** Logical camera names present on this device, e.g. ["back", "front"]. */
        fun listAvailableCameras(context: Context): List<String> = try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager
            manager.cameraIdList.mapNotNull { id ->
                when (manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    else -> null
                }
            }.distinct()
        } catch (e: Exception) {
            Log.w(TAG, "listAvailableCameras failed: ${e.message}")
            emptyList()
        }

        /**
         * Characteristics for the requested facing, usable before any camera
         * is open (capability queries from the HTTP API).
         */
        fun characteristicsFor(context: Context, back: Boolean): CameraCharacteristics? = try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager
            val id = manager.cameraIdList.firstOrNull { id ->
                val facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                (back && facing == CameraCharacteristics.LENS_FACING_BACK) ||
                    (!back && facing == CameraCharacteristics.LENS_FACING_FRONT)
            } ?: manager.cameraIdList.firstOrNull()
            id?.let { manager.getCameraCharacteristics(it) }
        } catch (e: Exception) {
            Log.w(TAG, "characteristicsFor failed: ${e.message}")
            null
        }

        /** AE exposure compensation range, or 0..0 when unsupported/unknown. */
        fun exposureRangeOf(chars: CameraCharacteristics?): IntRange {
            if (chars == null) return 0..0
            return try {
                val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                if (range == null) 0..0 else range.lower..range.upper
            } catch (e: Exception) {
                0..0
            }
        }

        /** Max digital zoom, or 5.0 when characteristics are unavailable. */
        fun maxZoomOf(chars: CameraCharacteristics?): Float {
            if (chars == null) return 5.0f
            return try {
                (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f)
                    .coerceAtLeast(1.0f)
            } catch (e: Exception) {
                1.0f
            }
        }

        /** Whether the camera advertises ON-device video stabilization. */
        fun stabilizationSupportedOf(chars: CameraCharacteristics?): Boolean {
            if (chars == null) return false
            return try {
                val modes = chars.get(
                    CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
                )
                modes != null &&
                    modes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            } catch (e: Exception) {
                false
            }
        }
    }

    private val systemCameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenExecutor: Executor = Executors.newSingleThreadExecutor()

    var usingBackCamera: Boolean = true
        private set
    private var currentCameraId: String? = null
    private var currentZoomRatio: Float = 1.0f
    private var currentTorchOn: Boolean = false
    private var currentExposureCompensation: Int = 0
    private var videoStabilizationOn: Boolean = false
    private var targetSurfaces: List<Surface> = emptyList()
    private var callback: Callback? = null

    private var currentCaptureRequestBuilder: CaptureRequest.Builder? = null

    fun start() {
        backgroundThread = HandlerThread("CamIP-CameraThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun maxOpticalZoom(cameraId: String): Float {
        return try {
            val chars = systemCameraManager.getCameraCharacteristics(cameraId)
            chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        } catch (e: Exception) {
            1.0f
        }
    }

    private fun findCameraId(wantBack: Boolean): String? {
        for (id in systemCameraManager.cameraIdList) {
            val chars = systemCameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (wantBack && facing == CameraCharacteristics.LENS_FACING_BACK) return id
            if (!wantBack && facing == CameraCharacteristics.LENS_FACING_FRONT) return id
        }
        return systemCameraManager.cameraIdList.firstOrNull()
    }

    /**
     * Picks an AE target FPS range the device actually advertises.
     *
     * Blindly forcing Range(fps, fps) makes setRepeatingRequest() throw
     * IllegalArgumentException on devices that only expose ranges like
     * (15, 30) or (5, 30), which kills the camera session before it ever
     * starts. Preference order: an exact fixed range, then the narrowest
     * range that still contains the desired rate, then whatever is closest.
     */
    private fun chooseAeFpsRange(cameraId: String, desiredFps: Int): Range<Int> {
        val fallback = Range(desiredFps, desiredFps)
        return try {
            val chars = systemCameraManager.getCameraCharacteristics(cameraId)
            val available =
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            if (available.isNullOrEmpty()) return fallback

            available.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }
                ?: available.filter { it.lower <= desiredFps && it.upper >= desiredFps }
                    .minByOrNull { it.upper - it.lower }
                ?: available.minByOrNull {
                    kotlin.math.abs(it.upper - desiredFps) + kotlin.math.abs(it.lower - desiredFps)
                }
                ?: fallback
        } catch (e: Exception) {
            Log.w(TAG, "chooseAeFpsRange failed: ${e.message}")
            fallback
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun open(
        useBackCamera: Boolean,
        surfaces: List<Surface>,
        fps: Int,
        callback: Callback,
        onOpened: () -> Unit
    ) {
        this.callback = callback
        this.targetSurfaces = surfaces
        this.usingBackCamera = useBackCamera
        // A freshly opened session starts from a clean slate: no torch, no
        // crop region, default exposure compensation and stabilization off,
        // so the UI/status must not keep stale values.
        this.currentTorchOn = false
        this.currentZoomRatio = 1.0f
        this.currentExposureCompensation = 0
        this.videoStabilizationOn = false
        this.currentCaptureRequestBuilder = null
        val cameraId = findCameraId(useBackCamera)
        if (cameraId == null) {
            callback.onCameraError("No camera available on this device")
            return
        }
        currentCameraId = cameraId

        try {
            systemCameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createSession(device, surfaces, fps, onOpened)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                    callback.onCameraDisconnected()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                    callback.onCameraError("Camera error code $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            callback.onCameraError("Failed to open camera: ${e.message}")
        }
    }

    private fun createSession(device: CameraDevice, surfaces: List<Surface>, fps: Int, onOpened: () -> Unit) {
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            surfaces.forEach { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                chooseAeFpsRange(device.id, fps)
            )
            currentCaptureRequestBuilder = builder

            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            onOpened()
                        } catch (e: Exception) {
                            callback?.onCameraError("Failed to start repeating request: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        callback?.onCameraError("Camera session configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            callback?.onCameraError("Failed to create capture session: ${e.message}")
        }
    }

    /** Switches between front/back camera, tearing down and rebuilding the session on the new device. */
    @Synchronized
    fun switchCamera(fps: Int, onOpened: () -> Unit) {
        val cb = callback ?: return
        val surfaces = targetSurfaces
        closeCameraOnly()
        open(!usingBackCamera, surfaces, fps, cb, onOpened)
    }

    @Synchronized
    fun setZoom(ratio: Float) {
        val builder = currentCaptureRequestBuilder ?: return
        val cameraId = currentCameraId ?: return
        val max = maxOpticalZoom(cameraId)
        val clamped = ratio.coerceIn(1.0f, max)
        try {
            val chars = systemCameraManager.getCameraCharacteristics(cameraId)
            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (sensorRect != null) {
                val cropW = (sensorRect.width() / clamped).roundToInt()
                val cropH = (sensorRect.height() / clamped).roundToInt()
                val left = (sensorRect.width() - cropW) / 2
                val top = (sensorRect.height() - cropH) / 2
                val zoomRect = android.graphics.Rect(left, top, left + cropW, top + cropH)
                builder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect)
                applyRepeatingRequest(builder)
                currentZoomRatio = clamped
            }
        } catch (e: Exception) {
            Log.w(TAG, "setZoom failed: ${e.message}")
        }
    }

    fun getZoom(): Float = currentZoomRatio

    /** Maximum digital zoom the active camera reports; 1.0 when no camera is open. */
    fun maxZoomRatio(): Float {
        val cameraId = currentCameraId ?: return 1.0f
        return maxOpticalZoom(cameraId).coerceAtLeast(1.0f)
    }

    @Synchronized
    fun setTorch(enabled: Boolean) {
        val builder = currentCaptureRequestBuilder ?: return
        try {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
            applyRepeatingRequest(builder)
            currentTorchOn = enabled
        } catch (e: Exception) {
            Log.w(TAG, "setTorch failed: ${e.message}")
        }
    }

    fun isTorchOn(): Boolean = currentTorchOn

    /** Characteristics of the currently open camera, or null when closed. */
    private fun currentCharacteristics(): CameraCharacteristics? =
        currentCameraId?.let { systemCameraManager.getCameraCharacteristics(it) }

    /** AE exposure compensation range of the open camera; 0..0 when closed. */
    fun exposureRange(): IntRange = exposureRangeOf(currentCharacteristics())

    fun getExposureCompensation(): Int = currentExposureCompensation

    /**
     * Applies AE exposure compensation (clamped to the camera's range) to the
     * repeating request. Returns false when there is no live capture session
     * or the camera does not expose a usable range.
     */
    @Synchronized
    fun setExposureCompensation(value: Int): Boolean {
        val builder = currentCaptureRequestBuilder ?: return false
        val range = exposureRange()
        if (range.isEmpty()) return false
        val clamped = value.coerceIn(range.first, range.last)
        return try {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamped)
            applyRepeatingRequest(builder)
            currentExposureCompensation = clamped
            true
        } catch (e: Exception) {
            Log.w(TAG, "setExposureCompensation failed: ${e.message}")
            false
        }
    }

    /** Whether the open camera advertises ON-device video stabilization. */
    fun isStabilizationSupported(): Boolean =
        stabilizationSupportedOf(currentCharacteristics())

    fun isVideoStabilizationOn(): Boolean = videoStabilizationOn

    /**
     * Turns video stabilization on/off. Returns false when there is no live
     * session or the camera does not support stabilization.
     */
    @Synchronized
    fun setVideoStabilization(enabled: Boolean): Boolean {
        val builder = currentCaptureRequestBuilder ?: return false
        if (enabled && !isStabilizationSupported()) return false
        return try {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (enabled) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
            applyRepeatingRequest(builder)
            videoStabilizationOn = enabled
            true
        } catch (e: Exception) {
            Log.w(TAG, "setVideoStabilization failed: ${e.message}")
            false
        }
    }

    @Synchronized
    fun triggerAutoFocus() {
        val builder = currentCaptureRequestBuilder ?: return
        try {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            captureSession?.capture(builder.build(), null, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            applyRepeatingRequest(builder)
        } catch (e: Exception) {
            Log.w(TAG, "triggerAutoFocus failed: ${e.message}")
        }
    }

    private fun applyRepeatingRequest(builder: CaptureRequest.Builder) {
        try {
            captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update repeating request: ${e.message}")
        }
    }

    private fun closeCameraOnly() {
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null
        currentTorchOn = false
        currentZoomRatio = 1.0f
        currentExposureCompensation = 0
        videoStabilizationOn = false
        currentCaptureRequestBuilder = null
    }

    @Synchronized
    fun close() {
        closeCameraOnly()
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(300)
        } catch (_: InterruptedException) {
        }
        backgroundThread = null
        backgroundHandler = null
        currentCaptureRequestBuilder = null
    }
}
