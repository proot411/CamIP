package com.camip.app.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class EncoderStatus { IDLE, STARTING, RUNNING, ERROR, STOPPED }

data class LiveStats(
    val isStreaming: Boolean = false,
    val encoderStatus: EncoderStatus = EncoderStatus.IDLE,
    val resolutionWidth: Int = 0,
    val resolutionHeight: Int = 0,
    val targetFps: Int = 30,
    val measuredFps: Double = 0.0,
    val bitrateKbps: Int = 0,
    val activeCameraFacingBack: Boolean = true,
    val flashOn: Boolean = false,
    val zoomRatio: Float = 1.0f,
    val batteryPercent: Int = -1,
    val udpDestinationIp: String = "",
    val udpDestinationPort: Int = 0,
    val httpPort: Int = 8080,
    val httpServerUp: Boolean = false,
    /** True while the phone's screen (MediaProjection) is the video source. */
    val screenShareActive: Boolean = false,
    val mjpegQuality: Int = 80,
    /** Actual (possibly camera-negotiated) MJPEG preview stream size. */
    val mjpegWidth: Int = 640,
    val mjpegHeight: Int = 360,
    val exposureCompensation: Int = 0,
    val videoStabilization: Boolean = false,
    val totalEncodedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val udpPacketsSent: Long = 0,
    val udpSendErrors: Long = 0,
    val lastError: String? = null,
    val phoneIpAddress: String = ""
)

/**
 * Process-wide observable state describing what the camera/encoder/network
 * pipeline is currently doing. StreamService owns writes; the Compose UI and
 * the embedded HTTP server (GET /api/status) both read from this.
 */
object StreamState {
    private val _stats = MutableStateFlow(LiveStats())
    val stats: StateFlow<LiveStats> = _stats

    fun update(block: (LiveStats) -> LiveStats) {
        _stats.update(block)
    }

    fun reset() {
        _stats.value = LiveStats()
    }
}
