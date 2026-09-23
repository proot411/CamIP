package com.camip.app.server

/**
 * In-process bridge between the Compose UI and the running StreamService.
 *
 * The UI and the streaming service live in the same Android process, so
 * there is no reason for the app's own controls to round-trip through
 * 127.0.0.1:8080. Talking to the pipeline directly makes zoom, flash,
 * focus, camera switch and the stream settings apply immediately (and lets
 * the UI know whether they were applied at all), instead of silently
 * doing nothing when the HTTP call cannot be made from the main thread.
 *
 * The embedded HTTP API keeps using the [ControlHandler] interface
 * directly, so remote/dashboard clients are unaffected.
 *
 * [register] is called by StreamService when the pipeline is alive and
 * [unregister] when it stops, so `isRunning` doubles as "the camera and
 * encoder stage is currently up".
 */
object PipelineControl {

    private const val DEFAULT_MAX_ZOOM = 5f

    @Volatile
    private var handler: ControlHandler? = null

    val isRunning: Boolean get() = handler != null

    fun register(controlHandler: ControlHandler) {
        handler = controlHandler
    }

    fun unregister(controlHandler: ControlHandler) {
        if (handler === controlHandler) handler = null
    }

    fun setZoom(ratio: Float): Boolean = withHandler { it.setZoom(ratio) }

    fun setFlash(enabled: Boolean): Boolean = withHandler { it.setFlash(enabled) }

    fun switchCamera(): Boolean = withHandler { it.switchCamera() }

    fun triggerFocus(): Boolean = withHandler { it.triggerFocus() }

    fun setBitrateKbps(kbps: Int): Boolean = withHandler { it.setBitrateKbps(kbps) }

    fun setResolution(width: Int, height: Int): Boolean =
        withHandler { it.setResolution(width, height) }

    fun requestKeyFrame(): Boolean = withHandler { it.requestKeyFrame() }

    fun setUdpDestination(ip: String, port: Int): Boolean =
        withHandler { it.setUdpDestination(ip, port) }

    /** Applies live MJPEG quality (30..100). */
    fun setMjpegQuality(quality: Int): Boolean = withHandler { it.setMjpegQuality(quality) }

    fun setMjpegResolution(width: Int, height: Int): Boolean =
        withHandler { it.setMjpegResolution(width, height) }

    /** Applies AE exposure compensation; false when the camera refused it. */
    fun setExposureCompensation(value: Int): Boolean =
        handler?.setExposureCompensation(value) ?: false

    /** Enables/disables video stabilization; false when unsupported. */
    fun setVideoStabilization(enabled: Boolean): Boolean =
        handler?.setVideoStabilization(enabled) ?: false

    /** Camera state + capabilities, or null when the pipeline is not running. */
    fun cameraInfo(): CameraInfo? = handler?.cameraInfo()

    /**
     * Launches the on-device screen-capture consent prompt. Returns false when the
     * app is in the background (Android blocks background activity starts) — the
     * user then has to tap "Screen share" in the app itself.
     */
    fun requestScreenShare(): Boolean = handler?.requestScreenShare() ?: false

    /** Stops an active screen share (returns to the camera). */
    fun stopScreenShare(): Boolean = handler?.stopScreenShare() ?: false

    /** Best-known maximum zoom for the active camera; 5x when nothing is running. */
    fun maxZoomRatio(): Float = handler?.maxZoomRatio() ?: DEFAULT_MAX_ZOOM

    /** Runs [block] against the live pipeline, returning false when it is not running. */
    private inline fun withHandler(block: (ControlHandler) -> Unit): Boolean {
        val h = handler ?: return false
        block(h)
        return true
    }
}
