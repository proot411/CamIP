package com.camip.app.service

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.camip.app.MainApplication
import com.camip.app.camera.CamCameraManager
import com.camip.app.camera.MjpegFrameSource
import com.camip.app.encoder.H264Encoder
import com.camip.app.model.AppSettings
import com.camip.app.model.EncoderStatus
import com.camip.app.model.MjpegResolutionPreset
import com.camip.app.model.ResolutionPreset
import com.camip.app.model.StreamState
import com.camip.app.server.CameraInfo
import com.camip.app.server.ControlHandler
import com.camip.app.server.HttpServer
import com.camip.app.server.PipelineControl
import com.camip.app.stream.MpegTsMuxer
import com.camip.app.stream.UdpStreamer
import com.camip.app.ui.MainActivity
import com.camip.app.ui.ScreenConsentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that owns the entire CamIP pipeline end to end:
 * Camera2 -> MediaCodec H.264 -> MpegTsMuxer -> UdpStreamer, plus the
 * independent MJPEG-over-HTTP fallback and the embedded HttpServer. It also
 * holds the partial WakeLock so streaming survives the screen turning off.
 *
 * Lifecycle rules this class enforces so the UI can trust StreamState:
 *  - STARTING is published synchronously when a start is accepted;
 *  - starting twice is a no-op instead of a BindException on port 8080;
 *  - a fatal camera/encoder failure tears down the camera stage, flips the
 *    state to ERROR and allows a clean retry from the Start button.
 */
class StreamService : Service(), ControlHandler {

    companion object {
        private const val TAG = "StreamService"
        const val ACTION_START = "com.camip.app.action.START"
        const val ACTION_STOP = "com.camip.app.action.STOP"
        const val ACTION_START_SCREEN = "com.camip.app.action.START_SCREEN"
        const val EXTRA_RESULT_CODE = "screen_result_code"
        const val EXTRA_RESULT_DATA = "screen_result_data"
        private const val NOTIFICATION_ID = 1001
        private const val FPS_WINDOW_MS = 1000L
        private const val DEFAULT_MAX_ZOOM = 5f
        private const val MJPEG_WIDTH = 640
        private const val MJPEG_HEIGHT = 360
        private const val MJPEG_FPS = 15
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var appSettings: AppSettings
    private var wakeLock: PowerManager.WakeLock? = null

    private var cameraManager: CamCameraManager? = null
    private var encoder: H264Encoder? = null
    private var muxer: MpegTsMuxer? = null
    private var udpStreamer: UdpStreamer? = null
    private var httpServer: HttpServer? = null
    private var mjpegFrameSource: MjpegFrameSource? = null
    private var mjpegHandlerThread: android.os.HandlerThread? = null

    // --- Screen share (MediaProjection) stage -------------------------
    private var screenProjection: MediaProjection? = null
    private var primaryVirtualDisplay: VirtualDisplay? = null
    private var previewVirtualDisplay: VirtualDisplay? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    /** True while a pipeline start is in flight or the pipeline is healthy. */
    @Volatile
    private var pipelineActive = false

    /**
     * Bumped every time the video stage (camera/encoder/MJPEG) is torn down.
     * Callbacks from encoder/camera capture the generation they were created
     * with and are ignored once it moves — without this, a stale error from
     * the stage being replaced lands mid-switch and tears down the *new*
     * stage, which froze the stream whenever camera <-> screen share swapped.
     */
    @Volatile
    private var stageGeneration = 0

    /** The resolution preset chosen by the user (camera mode baseline). */
    private var presetWidth = 1280
    private var presetHeight = 720

    /** Requested MJPEG preview size (camera path negotiates the closest match). */
    private var mjpegPresetWidth = MJPEG_WIDTH
    private var mjpegPresetHeight = MJPEG_HEIGHT

    /** Actual size of the encoder's input Surface for the active stage. */
    private var currentWidth = 1280
    private var currentHeight = 720
    private var currentFps = 30
    private var currentBitrateBps = AppSettings.DEFAULT_BITRATE_KBPS * 1000
    private var currentKeyFrameIntervalSeconds = AppSettings.DEFAULT_KEYFRAME_INTERVAL_SECONDS
    private var currentMjpegQuality = AppSettings.DEFAULT_MJPEG_QUALITY
    private var currentDestIp = "192.168.1.50"
    private var currentDestPort = AppSettings.DEFAULT_UDP_PORT
    private var usingBackCamera = true

    private val encodedFrameCount = AtomicLong(0)
    private var fpsWindowStart = 0L
    private var fpsWindowCount = 0

    private var batteryReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        appSettings = AppSettings(applicationContext)
        registerBatteryReceiver()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreamingInternal()
                stopForegroundAndRemoveNotification()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_SCREEN -> {
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                if (data == null) {
                    Log.w(TAG, "START_SCREEN without screen-capture result; ignoring")
                    return START_STICKY
                }
                if (screenProjection != null) {
                    Log.i(TAG, "Screen share already active; ignoring duplicate consent")
                    return START_STICKY
                }
                // Flip the source state immediately so the app/dashboard
                // buttons track the switch while the stage rebuilds (it only
                // reaches RUNNING at the very end otherwise).
                StreamState.update {
                    it.copy(
                        screenShareActive = true,
                        encoderStatus = EncoderStatus.STARTING,
                        lastError = null
                    )
                }
                // Fresh consent: tag the foreground service with the
                // mediaProjection type before any VirtualDisplay is created
                // (Android 14+ enforces exactly this ordering).
                startForegroundCompat(includeProjection = true)
                if (!pipelineActive) {
                    pipelineActive = true
                    serviceScope.launch { beginStreamingPipeline(screenFirst = resultCode to data) }
                } else {
                    serviceScope.launch {
                        try {
                            startScreenStage(resultCode, data)
                        } catch (e: Exception) {
                            onFatalPipelineError("Screen share failed: ${e.message}")
                        }
                    }
                }
            }
            else -> {
                startForegroundCompat()
                if (!pipelineActive) {
                    // Mark active synchronously so a second Start tap (or a
                    // START_STICKY redelivery) cannot race a second pipeline
                    // into a port-8080 bind failure.
                    pipelineActive = true
                    serviceScope.launch { beginStreamingPipeline() }
                }
            }
        }
        return START_STICKY
    }

    // -----------------------------------------------------------------
    // Pipeline lifecycle
    // -----------------------------------------------------------------

    private suspend fun beginStreamingPipeline(screenFirst: Pair<Int, Intent>? = null) {
        try {
            val settings = appSettings.current()
            presetWidth = settings.resolution.width
            presetHeight = settings.resolution.height
            currentWidth = presetWidth
            currentHeight = presetHeight
            currentBitrateBps = settings.bitrateKbps * 1000
            currentKeyFrameIntervalSeconds = settings.keyFrameIntervalSeconds
            currentMjpegQuality = settings.mjpegQuality
            mjpegPresetWidth = settings.mjpegResolution.width
            mjpegPresetHeight = settings.mjpegResolution.height
            currentDestIp = settings.destinationIp
            currentDestPort = settings.destinationPort

            acquireWakeLock()

            StreamState.update {
                it.copy(
                    encoderStatus = EncoderStatus.STARTING,
                    lastError = null,
                    resolutionWidth = currentWidth,
                    resolutionHeight = currentHeight,
                    targetFps = currentFps,
                    bitrateKbps = currentBitrateBps / 1000,
                    mjpegQuality = currentMjpegQuality,
                    udpDestinationIp = currentDestIp,
                    udpDestinationPort = currentDestPort,
                    httpPort = AppSettings.DEFAULT_HTTP_PORT,
                    activeCameraFacingBack = usingBackCamera,
                    flashOn = false,
                    zoomRatio = 1.0f,
                    screenShareActive = screenFirst != null,
                    phoneIpAddress = getLocalIpAddress() ?: "0.0.0.0"
                )
            }

            startHttpServer()
            startUdpAndMuxer()

            // A Stop (or a fatal error) may have landed while we were
            // starting; don't open a camera into a pipeline that is gone.
            if (!pipelineActive) return

            if (screenFirst != null) {
                try {
                    startScreenStage(screenFirst.first, screenFirst.second)
                } catch (e: Exception) {
                    throw IllegalStateException("Screen share failed: ${e.message}", e)
                }
            } else {
                startCameraAndEncoder()
            }

            // A fatal camera error may have landed while we were starting.
            if (pipelineActive) {
                PipelineControl.register(this)
                StreamState.update { it.copy(isStreaming = true, encoderStatus = EncoderStatus.RUNNING) }
                refreshNotification()
                Log.i(TAG, "CamIP pipeline RUNNING (${currentWidth}x$currentHeight @ $currentFps fps)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming pipeline", e)
            pipelineActive = false
            PipelineControl.unregister(this)
            StreamState.update {
                it.copy(
                    isStreaming = false,
                    encoderStatus = EncoderStatus.ERROR,
                    lastError = e.message ?: e.toString()
                )
            }
        }
    }

    private fun startHttpServer() {
        // Idempotent: retrying a failed start must not rebind port 8080.
        if (httpServer != null) return
        val server = HttpServer(AppSettings.DEFAULT_HTTP_PORT, appSettings, this)
        server.start()
        httpServer = server
        StreamState.update { it.copy(httpServerUp = true, httpPort = AppSettings.DEFAULT_HTTP_PORT) }
    }

    private fun startUdpAndMuxer() {
        if (udpStreamer != null && muxer != null) return

        val streamer = UdpStreamer().apply {
            onError = { msg -> StreamState.update { s -> s.copy(lastError = msg) } }
        }
        streamer.start(currentDestIp, currentDestPort)
        udpStreamer = streamer

        muxer = MpegTsMuxer { packet ->
            streamer.offer(packet)
        }
    }

    /** Tears down camera, encoder and MJPEG stage only; HTTP/UDP keep running. */
    private fun stopCameraStage() {
        // Move the generation first so any callback racing with the teardown
        // below is treated as stale from this point on.
        stageGeneration++

        val cam = cameraManager
        cameraManager = null
        try {
            cam?.close()
        } catch (e: Exception) {
            Log.w(TAG, "cameraManager.close() failed: ${e.message}")
        }

        val enc = encoder
        encoder = null
        try {
            enc?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "encoder.stop() failed: ${e.message}")
        }

        val mjpeg = mjpegFrameSource
        mjpegFrameSource = null
        try {
            mjpeg?.close()
        } catch (_: Exception) {
        }
        val thread = mjpegHandlerThread
        mjpegHandlerThread = null
        thread?.quitSafely()
    }

    /**
     * Builds the encoder + MJPEG preview stages at the requested size and
     * returns their input Surfaces (encoder first, MJPEG ImageReader second).
     * Shared by the camera path and the screen-share path; the caller must
     * have torn any previous stage down first (so [stageGeneration] already
     * identifies *this* stage).
     */
    private fun startEncoderAndMjpeg(width: Int, height: Int): Pair<android.view.Surface, MjpegFrameSource> {
        val generation = stageGeneration
        val enc = H264Encoder(
            width = width,
            height = height,
            bitrateBps = currentBitrateBps,
            frameRate = currentFps,
            keyFrameIntervalSeconds = currentKeyFrameIntervalSeconds,
            listener = object : H264Encoder.Listener {
                override fun onCodecConfig(sps: ByteArray, pps: ByteArray) {
                    if (stageGeneration != generation) return
                    muxer?.setParameterSets(sps, pps)
                }

                override fun onAccessUnit(data: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean) {
                    if (stageGeneration != generation) return
                    muxer?.writeAccessUnit(data, presentationTimeUs, isKeyFrame)
                    trackFps()
                }

                override fun onEncoderError(message: String, error: Throwable?) {
                    if (stageGeneration != generation) {
                        // Normal during a stage switch: the codec being torn
                        // down must not kill the stage that replaced it.
                        Log.i(TAG, "Ignoring error from a replaced encoder stage: $message")
                        return
                    }
                    Log.e(TAG, "Encoder error: $message", error)
                    onFatalPipelineError(message)
                }
            }
        )
        // Publish before start(): codec config can arrive on the drain thread
        // immediately, and a torn-down stage must be recognizably stale.
        encoder = enc
        val inputSurface = try {
            enc.start()
        } catch (e: Exception) {
            encoder = null
            throw e
        }

        val thread = android.os.HandlerThread("CamIP-MjpegReader").also { it.start() }
        mjpegHandlerThread = thread
        val mjpegSize =
            MjpegFrameSource.chooseSize(applicationContext, mjpegPresetWidth, mjpegPresetHeight)
        val mjpeg = MjpegFrameSource(
            width = mjpegSize.width,
            height = mjpegSize.height,
            targetFps = MJPEG_FPS,
            initialQuality = currentMjpegQuality
        ) { jpeg -> httpServer?.pushMjpegFrame(jpeg) }
        mjpeg.start(Handler(thread.looper))
        mjpegFrameSource = mjpeg
        StreamState.update { it.copy(mjpegWidth = mjpegSize.width, mjpegHeight = mjpegSize.height) }

        currentWidth = width
        currentHeight = height
        return inputSurface to mjpeg
    }

    private fun startCameraAndEncoder() {
        // Always start from a clean slate so retries and resolution changes
        // never leave a previous camera/encoder instance alive.
        stopCameraStage()

        // Camera mode always encodes at the configured preset (screen mode
        // overwrites currentWidth/Height with the screen-derived size).
        currentWidth = presetWidth
        currentHeight = presetHeight
        StreamState.update { it.copy(screenShareActive = false) }

        val (inputSurface, mjpeg) = startEncoderAndMjpeg(currentWidth, currentHeight)

        val camMgr = CamCameraManager(applicationContext)
        camMgr.start()
        cameraManager = camMgr
        val generation = stageGeneration
        val callback = object : CamCameraManager.Callback {
            override fun onCameraError(message: String) {
                if (stageGeneration != generation || cameraManager !== camMgr) {
                    // Stale: this camera belongs to a stage we already swapped
                    // away from (camera -> screen share and back).
                    Log.i(TAG, "Ignoring error from a replaced camera stage: $message")
                    return
                }
                Log.e(TAG, "Camera error: $message")
                onFatalPipelineError(message)
            }

            override fun onCameraDisconnected() {
                if (stageGeneration != generation || cameraManager !== camMgr) {
                    Log.i(TAG, "Ignoring disconnect from a replaced camera stage")
                    return
                }
                Log.w(TAG, "Camera disconnected")
                onFatalPipelineError("Camera disconnected")
            }
        }

        camMgr.open(
            useBackCamera = usingBackCamera,
            surfaces = listOf(inputSurface, mjpeg.imageReader.surface),
            fps = currentFps,
            callback = callback
        ) {
            Log.i(TAG, "Camera session configured and streaming")
        }
    }

    // -----------------------------------------------------------------
    // Screen share (MediaProjection) stage
    // -----------------------------------------------------------------

    /**
     * Screen-capture consent arrived from ScreenConsentActivity. Tearing the
     * camera down and mirroring the screen into two VirtualDisplays: one
     * feeds the H.264 encoder, the second keeps the dashboard's MJPEG
     * preview alive (it shows the screen while screen sharing runs).
     *
     * When a projection is already live ([resultCode]/[data] unused) this
     * only rebuilds the encoder+VirtualDisplays, e.g. after a resolution
     * change.
     */
    private fun startScreenStage(resultCode: Int, data: Intent?) {
        // Serialized with onProjectionStopped()'s identity check so a late
        // onStop cannot interleave with the rebuild below.
        synchronized(this) {
            val projection = screenProjection ?: run {
                if (data == null) throw IllegalStateException("screen-capture consent is missing")
                val manager = getSystemService(MediaProjectionManager::class.java)
                val created = manager.getMediaProjection(resultCode, data)
                    ?: throw IllegalStateException("could not start screen capture")
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        // Fired when the projection ends for any reason: our own
                        // stop, the system quick-settings tile, or the user
                        // revoking it. Identity-check so a replaced projection's
                        // late callback cannot tear down the current one.
                        serviceScope.launch { onProjectionStopped(created) }
                    }
                }
                created.registerCallback(callback, Handler(Looper.getMainLooper()))
                screenProjection = created
                created
            }

            // The display must be awake before mirroring starts, otherwise the
            // VirtualDisplay renders nothing and the stream looks frozen.
            acquireScreenWakeLock()

            // Drop whatever video stage is running (the projection itself stays).
            stopCameraStage()
            releaseVirtualDisplays()

            // Camera is gone now; the projection type is the one in use.
            startForegroundCompat(includeProjection = true)

            val (width, height) = computeScreenEncoderSize()
            val surfaces = try {
                startEncoderAndMjpeg(width, height)
            } catch (e: Exception) {
                // Some hardware encoders reject unusual (portrait) sizes
                // outright; a stretched stream beats a dead one.
                Log.w(TAG, "Encoder rejected screen size ${width}x$height (${e.message}); " +
                    "falling back to ${presetWidth}x${presetHeight}")
                stopCameraStage()
                startEncoderAndMjpeg(presetWidth, presetHeight)
            }
            val (inputSurface, mjpeg) = surfaces

            val displayMetrics = resources.displayMetrics
            val density = displayMetrics.densityDpi
            primaryVirtualDisplay = projection.createVirtualDisplay(
                "CamIP-Primary", currentWidth, currentHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null
            ) ?: projection.createVirtualDisplay(
                // Some devices only mirror onto a "public" display.
                "CamIP-Primary", currentWidth, currentHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                inputSurface, null, null
            ) ?: throw IllegalStateException(
                "could not create the screen-capture display (${currentWidth}x${currentHeight})"
            )

            // Best effort: a second mirror at preview resolution keeps
            // /mjpeg/live working. If the device refuses a second
            // VirtualDisplay, the H.264 stream still works; only the
            // dashboard preview goes stale.
            previewVirtualDisplay = try {
                projection.createVirtualDisplay(
                    "CamIP-Preview",
                    mjpeg.imageReader.width, mjpeg.imageReader.height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mjpeg.imageReader.surface, null, null
                )
            } catch (e: Exception) {
                Log.w(TAG, "preview VirtualDisplay failed: ${e.message}")
                null
            }

            StreamState.update {
                it.copy(
                    screenShareActive = true,
                    isStreaming = true,
                    encoderStatus = EncoderStatus.RUNNING,
                    lastError = null,
                    resolutionWidth = currentWidth,
                    resolutionHeight = currentHeight,
                    flashOn = false,
                    zoomRatio = 1.0f
                )
            }
            refreshNotification()
            Log.i(TAG, "Screen share active at ${currentWidth}x${currentHeight}")
        }
    }

    /**
     * Sizes the encoder for the real screen: the screen's aspect ratio is
     * preserved (portrait stays portrait, so text is not stretched) while
     * the longer side is capped at the chosen preset's longer side — that
     * keeps the pixel budget the user selected and never upscales.
     */
    private fun computeScreenEncoderSize(): Pair<Int, Int> {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        if (screenWidth <= 0 || screenHeight <= 0) return presetWidth to presetHeight

        val budget = maxOf(presetWidth, presetHeight)
        val nativeLongSide = maxOf(screenWidth, screenHeight)
        val longSide = minOf(nativeLongSide, budget)
        val scale = longSide.toFloat() / nativeLongSide
        // MediaCodec surface sizes want even, encoder-friendly dimensions.
        val width = ((screenWidth * scale).toInt() and 15.inv()).coerceIn(16, 4096)
        val height = ((screenHeight * scale).toInt() and 15.inv()).coerceIn(16, 4096)
        return width to height
    }

    /** The projection ended (by us, the system tile, or the user). */
    private fun onProjectionStopped(projection: MediaProjection) {
        synchronized(this) {
            if (screenProjection !== projection) return // stale/replaced
            screenProjection = null
        }
        releaseProjectionResources(projection)

        if (!pipelineActive) {
            StreamState.update { it.copy(screenShareActive = false) }
            return
        }
        // Flip the buttons back immediately, then rebuild the camera stage.
        StreamState.update {
            it.copy(screenShareActive = false, encoderStatus = EncoderStatus.STARTING, lastError = null)
        }
        try {
            startCameraAndEncoder()
            startForegroundCompat()
            if (pipelineActive) {
                StreamState.update {
                    it.copy(
                        isStreaming = true,
                        encoderStatus = EncoderStatus.RUNNING,
                        resolutionWidth = currentWidth,
                        resolutionHeight = currentHeight,
                        lastError = null
                    )
                }
                refreshNotification()
            }
            Log.i(TAG, "Screen share ended; back to the camera")
        } catch (e: Exception) {
            onFatalPipelineError("Screen share ended, and the camera could not restart: ${e.message}")
        }
    }

    private fun releaseVirtualDisplays() {
        try {
            primaryVirtualDisplay?.release()
        } catch (_: Exception) {
        }
        primaryVirtualDisplay = null
        try {
            previewVirtualDisplay?.release()
        } catch (_: Exception) {
        }
        previewVirtualDisplay = null
    }

    private fun releaseProjectionResources(projection: MediaProjection?) {
        releaseVirtualDisplays()
        releaseScreenWakeLock()
        // MediaProjection has no release() on current API levels; stop() is
        // the teardown call (it is idempotent, and our callback identity
        // check makes a duplicate onStop a no-op).
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
    }

    /** Keeps the display on while sharing: capture freezes when the screen sleeps. */
    private fun acquireScreenWakeLock() {
        if (screenWakeLock?.isHeld == true) return
        @Suppress("DEPRECATION")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // ACQUIRE_CAUSES_WAKEUP turns the display on if it went off between
        // the consent dialog and the projection start.
        screenWakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "CamIP::ScreenShare"
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // safety timeout: 12 hours
        }
    }

    private fun releaseScreenWakeLock() {
        try {
            screenWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        screenWakeLock = null
    }

    /**
     * Whether the app currently has a visible window (the only reliable
     * signal that Android will still let us launch the consent prompt;
     * background activity starts are blocked on Android 10+).
     */
    private fun isAppInForeground(): Boolean = try {
        val manager = getSystemService(ActivityManager::class.java)
        manager.runningAppProcesses
            ?.firstOrNull { it.pid == android.os.Process.myPid() }
            ?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    } catch (e: Exception) {
        false
    }

    /**
     * A camera or encoder failure that cannot recover: drop the camera
     * stage (HTTP + UDP stay up so the dashboard keeps reporting why),
     * publish ERROR and allow Start to retry.
     */
    private fun onFatalPipelineError(message: String) {
        synchronized(this) {
            if (!pipelineActive) return
            pipelineActive = false
        }
        Log.e(TAG, "Fatal pipeline error: $message")
        PipelineControl.unregister(this)
        StreamState.update {
            it.copy(
                isStreaming = false,
                screenShareActive = false,
                encoderStatus = EncoderStatus.ERROR,
                lastError = message
            )
        }
        serviceScope.launch {
            val projection = screenProjection
            screenProjection = null
            stopCameraStage()
            releaseProjectionResources(projection)
        }
    }

    private fun trackFps() {
        val now = System.currentTimeMillis()
        val total = encodedFrameCount.incrementAndGet()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsWindowCount++
        val elapsed = now - fpsWindowStart
        if (elapsed >= FPS_WINDOW_MS) {
            val fps = fpsWindowCount * 1000.0 / elapsed
            val streamer = udpStreamer
            StreamState.update {
                it.copy(
                    measuredFps = fps,
                    totalEncodedFrames = total,
                    udpPacketsSent = streamer?.packetsSent ?: it.udpPacketsSent,
                    udpSendErrors = streamer?.sendErrors ?: it.udpSendErrors
                )
            }
            fpsWindowStart = now
            fpsWindowCount = 0
            // Keep the ongoing notification in sync with the live numbers.
            refreshNotification()
        }
    }

    private fun stopStreamingInternal() {
        Log.i(TAG, "Stopping CamIP streaming pipeline")
        pipelineActive = false
        PipelineControl.unregister(this)

        // Drop the projection first so its onStop callback cannot interpret
        // the teardown below as "switch back to the camera".
        val projection = screenProjection
        screenProjection = null

        stopCameraStage()
        if (projection != null) {
            try {
                projection.stop()
            } catch (_: Exception) {
            }
        }
        releaseProjectionResources(projection)

        val streamer = udpStreamer
        udpStreamer = null
        muxer = null
        try {
            streamer?.stop()
        } catch (_: Exception) {
        }

        val server = httpServer
        httpServer = null
        try {
            server?.stop()
        } catch (_: Exception) {
        }

        releaseWakeLock()
        encodedFrameCount.set(0)
        fpsWindowStart = 0
        fpsWindowCount = 0
        StreamState.reset()
    }

    override fun onDestroy() {
        stopStreamingInternal()
        unregisterBatteryReceiver()
        serviceScope.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------
    // ControlHandler (invoked from HTTP API and, in-process, Compose UI)
    // -----------------------------------------------------------------

    override fun setZoom(ratio: Float) {
        val cam = cameraManager ?: return
        val maxZoom = maxZoomRatio()
        val clamped = ratio.coerceIn(1.0f, maxZoom)
        cam.setZoom(clamped)
        StreamState.update { it.copy(zoomRatio = cam.getZoom()) }
    }

    override fun setFlash(enabled: Boolean) {
        val cam = cameraManager ?: return
        cam.setTorch(enabled)
        StreamState.update { it.copy(flashOn = cam.isTorchOn()) }
    }

    override fun switchCamera() {
        val cam = cameraManager ?: return
        val generation = stageGeneration
        cam.switchCamera(currentFps) {
            // Ignore the reopen if the stage was replaced while the camera
            // was in the middle of cycling lenses.
            if (stageGeneration == generation && cameraManager === cam) {
                usingBackCamera = cam.usingBackCamera
                // A new session starts with torch off and no crop region.
                StreamState.update {
                    it.copy(
                        activeCameraFacingBack = cam.usingBackCamera,
                        flashOn = false,
                        zoomRatio = 1.0f
                    )
                }
            }
        }
    }

    override fun triggerFocus() {
        cameraManager?.triggerAutoFocus()
    }

    override fun setBitrateKbps(kbps: Int) {
        currentBitrateBps = kbps * 1000
        encoder?.setBitrate(currentBitrateBps)
        StreamState.update { it.copy(bitrateKbps = kbps) }
    }

    override fun setResolution(width: Int, height: Int) {
        // MediaCodec input Surface size is fixed at configure time, so a
        // resolution change requires tearing down and rebuilding the
        // camera+encoder stage. The HTTP server, UDP socket and MJPEG
        // fallback are left running throughout. In screen mode the same
        // rebuild happens with the projection kept alive.
        presetWidth = width
        presetHeight = height
        serviceScope.launch {
            try {
                appSettings.update(resolution = ResolutionPreset.entries.firstOrNull {
                    it.width == width && it.height == height
                } ?: ResolutionPreset.R_1280x720)

                if (!pipelineActive) return@launch
                rebuildActiveStage("Resolution change")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to change resolution", e)
                onFatalPipelineError("Resolution change failed: ${e.message}")
            }
        }
    }

    override fun setMjpegResolution(width: Int, height: Int) {
        val preset = MjpegResolutionPreset.entries.firstOrNull {
            it.width == width && it.height == height
        } ?: MjpegResolutionPreset.R_640x360
        mjpegPresetWidth = preset.width
        mjpegPresetHeight = preset.height
        serviceScope.launch {
            try {
                appSettings.update(mjpegResolution = preset)
                if (!pipelineActive) return@launch
                rebuildActiveStage("MJPEG resolution change")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to change MJPEG resolution", e)
                onFatalPipelineError("MJPEG resolution change failed: ${e.message}")
            }
        }
    }

    /**
     * Tears down and rebuilds whichever video stage is live (the projection
     * itself is kept in screen mode). Called from the stage-switch paths
     * above; publish STARTING/RUNNING around it so the UIs track the change.
     */
    private suspend fun rebuildActiveStage(what: String) {
        StreamState.update { it.copy(encoderStatus = EncoderStatus.STARTING, lastError = null) }
        try {
            if (screenProjection != null) startScreenStage(Activity.RESULT_OK, null)
            else startCameraAndEncoder()
        } catch (e: Exception) {
            Log.e(TAG, "$what failed", e)
            onFatalPipelineError("$what failed: ${e.message}")
            return
        }
        if (pipelineActive) {
            StreamState.update {
                it.copy(
                    resolutionWidth = currentWidth,
                    resolutionHeight = currentHeight,
                    encoderStatus = EncoderStatus.RUNNING
                )
            }
            refreshNotification()
        }
    }

    override fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    override fun setUdpDestination(ip: String, port: Int) {
        currentDestIp = ip
        currentDestPort = port
        udpStreamer?.updateDestination(ip, port)
        StreamState.update { it.copy(udpDestinationIp = ip, udpDestinationPort = port) }
        refreshNotification()
    }

    override fun maxZoomRatio(): Float =
        cameraManager?.maxZoomRatio()?.takeIf { it > 1.01f }
            ?: CamCameraManager.maxZoomOf(
                CamCameraManager.characteristicsFor(applicationContext, usingBackCamera)
            ).takeIf { it > 1.01f } ?: DEFAULT_MAX_ZOOM

    override fun setMjpegQuality(quality: Int) {
        currentMjpegQuality = quality.coerceIn(
            AppSettings.MIN_MJPEG_QUALITY,
            AppSettings.MAX_MJPEG_QUALITY
        )
        mjpegFrameSource?.setQuality(currentMjpegQuality)
        StreamState.update { it.copy(mjpegQuality = currentMjpegQuality) }
    }

    override fun setExposureCompensation(value: Int): Boolean {
        val cam = cameraManager ?: return false
        val applied = cam.setExposureCompensation(value)
        if (applied) {
            StreamState.update { it.copy(exposureCompensation = cam.getExposureCompensation()) }
        }
        return applied
    }

    override fun setVideoStabilization(enabled: Boolean): Boolean {
        val cam = cameraManager ?: return false
        val applied = cam.setVideoStabilization(enabled)
        if (applied) {
            StreamState.update { it.copy(videoStabilization = cam.isVideoStabilizationOn()) }
        }
        return applied
    }

    override fun cameraInfo(): CameraInfo {
        val available = CamCameraManager.listAvailableCameras(applicationContext)
        val cam = cameraManager
        if (cam != null) {
            val range = cam.exposureRange()
            return CameraInfo(
                active = true,
                facing = if (cam.usingBackCamera) "back" else "front",
                zoom = cam.getZoom(),
                maxZoom = cam.maxZoomRatio().coerceAtLeast(1f),
                torchOn = cam.isTorchOn(),
                exposureCompensation = cam.getExposureCompensation(),
                exposureMin = range.first,
                exposureMax = range.last,
                stabilizationSupported = cam.isStabilizationSupported(),
                stabilizationOn = cam.isVideoStabilizationOn(),
                availableCameras = available
            )
        }
        // No camera open (stream stopped, or screen sharing): report what the
        // device can do so clients can render a sensible control panel.
        val chars = CamCameraManager.characteristicsFor(applicationContext, usingBackCamera)
        val range = CamCameraManager.exposureRangeOf(chars)
        return CameraInfo(
            active = false,
            facing = if (usingBackCamera) "back" else "front",
            zoom = 1f,
            maxZoom = CamCameraManager.maxZoomOf(chars).coerceAtLeast(1f),
            torchOn = false,
            exposureCompensation = 0,
            exposureMin = range.first,
            exposureMax = range.last,
            stabilizationSupported = CamCameraManager.stabilizationSupportedOf(chars),
            stabilizationOn = false,
            availableCameras = available
        )
    }

    override fun requestScreenShare(): Boolean {
        if (screenProjection != null) return true // already sharing
        // Android 10+ blocks starting activities from the background, so a
        // dashboard-triggered prompt only appears while the app has a window.
        if (!isAppInForeground()) return false
        return try {
            startActivity(
                Intent(this, ScreenConsentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "requestScreenShare failed: ${e.message}")
            false
        }
    }

    override fun stopScreenShare(): Boolean {
        val projection = screenProjection ?: return false
        // onStop callback does the teardown and restores the camera stage.
        try {
            projection.stop()
        } catch (e: Exception) {
            Log.w(TAG, "projection.stop() failed: ${e.message}")
            onProjectionStopped(projection)
            return true
        }
        // Not every device delivers onStop promptly; if it never arrives,
        // force the teardown so the buttons and stream can't get stuck.
        serviceScope.launch {
            delay(1_500)
            if (screenProjection === projection) {
                Log.w(TAG, "MediaProjection.onStop never arrived; forcing teardown")
                onProjectionStopped(projection)
            }
        }
        return true
    }

    // -----------------------------------------------------------------
    // Notification / WakeLock / misc
    // -----------------------------------------------------------------

    private fun buildNotification(): Notification {
        val stats = StreamState.stats.value
        val stopIntent = Intent(this, StreamService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val details = buildList {
            add(if (stats.screenShareActive) "Screen" else "Camera")
            if (stats.resolutionWidth > 0) add("${stats.resolutionWidth}x${stats.resolutionHeight}")
            if (stats.isStreaming) {
                add(String.format(java.util.Locale.US, "%.1f fps", stats.measuredFps))
            }
            add("UDP ${stats.udpDestinationIp}:${stats.udpDestinationPort}")
            add("http://${stats.phoneIpAddress.ifBlank { getLocalIpAddress() ?: "<phone-ip>" }}:${AppSettings.DEFAULT_HTTP_PORT}")
        }.joinToString("  |  ")

        return NotificationCompat.Builder(this, MainApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(com.camip.app.R.string.notification_title))
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun refreshNotification() {
        if (!pipelineActive) return
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "notify() failed: ${e.message}")
        }
    }

    private fun startForegroundCompat(includeProjection: Boolean = false) {
        val notification = buildNotification()
        val cameraAllowed =
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        var types = 0
        // Type CAMERA needs the camera permission (and only exists as a
        // public constant from API 30); below that the manifest type applies.
        val wantsCamera = (cameraManager != null && cameraAllowed) || !includeProjection
        if (wantsCamera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (includeProjection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types != 0) {
                // Explicit type: the manifest declares foregroundServiceType
                // "camera|mediaProjection", and API 34+ requires the type in
                // use to be passed in as well.
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                // Below API 30 (or with no computable type) the
                // manifest-declared type applies.
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "typed startForeground failed (${e.message}); retrying untyped")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground failed: ${e2.message}")
            }
        }
    }

    private fun stopForegroundAndRemoveNotification() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamIP::StreamingWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // safety timeout: 12 hours
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        batteryReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                applyBatteryIntent(intent)
            }
        }
        // ACTION_BATTERY_CHANGED is sticky: registerReceiver returns the
        // current level immediately. Without seeding it here, the battery
        // stat only updates when the percentage actually changes.
        val sticky = try {
            registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver(battery) failed: ${e.message}")
            null
        }
        applyBatteryIntent(sticky)
    }

    private fun applyBatteryIntent(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            val pct = (level * 100) / scale
            StreamState.update { it.copy(batteryPercent = pct) }
        }
    }

    private fun unregisterBatteryReceiver() {
        try {
            batteryReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        batteryReceiver = null
    }

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String? {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val bytes = BigInteger.valueOf(ipInt.toLong()).toByteArray().reversedArray()
                if (bytes.size == 4) {
                    return "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}"
                }
            }
        } catch (_: Exception) {
        }
        // Fallback: scan network interfaces for a plausible LAN IPv4 address.
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}
