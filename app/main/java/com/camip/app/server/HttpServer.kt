package com.camip.app.server

import android.util.Log
import com.camip.app.model.AppSettings
import com.camip.app.model.StreamState
import com.camip.app.model.isValidIPv4
import com.camip.app.model.isValidPort
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handler used by HttpServer to apply /api/control actions to the actual
 * camera/encoder pipeline. Implemented by StreamService.
 */
interface ControlHandler {
    fun setZoom(ratio: Float)
    fun setFlash(enabled: Boolean)
    fun switchCamera()
    fun triggerFocus()
    fun setBitrateKbps(kbps: Int)
    fun setResolution(width: Int, height: Int)
    fun requestKeyFrame()
    fun setUdpDestination(ip: String, port: Int)

    /** Live MJPEG preview quality (JPEG 30..100). Applied immediately when streaming. */
    fun setMjpegQuality(quality: Int)

    /**
     * Sets the MJPEG preview size (target; the camera path negotiates the
     * closest advertised size) and rebuilds the live stage if one is running.
     */
    fun setMjpegResolution(width: Int, height: Int)

    /**
     * Applies AE exposure compensation (stops are allowed to be refused, e.g. when
     * the camera stage is down or the range is unsupported). Returns true when the
     * camera accepted the value.
     */
    fun setExposureCompensation(value: Int): Boolean

    /** Enables/disables video stabilization. Returns true when applied. */
    fun setVideoStabilization(enabled: Boolean): Boolean

    /** Snapshot of the camera state + capabilities for /api/camera. */
    fun cameraInfo(): CameraInfo

    /**
     * Asks the user (on the phone) for screen-capture consent. Returns true when
     * the consent prompt could actually be launched in the foreground; false when
     * the app is in the background and Android will not allow it.
     */
    fun requestScreenShare(): Boolean

    /** Stops an active screen share (falls back to the camera). True when one was active. */
    fun stopScreenShare(): Boolean

    /** Maximum zoom ratio the active camera supports (5.0 when unknown). */
    fun maxZoomRatio(): Float
}

/** Camera state + capability snapshot returned by /api/camera. */
data class CameraInfo(
    /** True while the camera stage (and thus camera controls) is live. */
    val active: Boolean,
    val facing: String,
    val zoom: Float,
    val maxZoom: Float,
    val torchOn: Boolean,
    val exposureCompensation: Int,
    val exposureMin: Int,
    val exposureMax: Int,
    val stabilizationSupported: Boolean,
    val stabilizationOn: Boolean,
    val availableCameras: List<String>
)

/**
 * A small, dependency-free HTTP/1.1 server implemented directly on top of
 * ServerSocket. It intentionally does not pull in a third-party HTTP
 * framework: the surface area needed here (a handful of fixed routes, one
 * multipart streaming endpoint) is small enough to implement directly and
 * keeps the whole HTTP+MJPEG path auditable in one file.
 */
class HttpServer(
    private val port: Int,
    private val appSettings: AppSettings,
    private val controlHandler: ControlHandler
) {
    companion object {
        private const val TAG = "HttpServer"
        private const val BOUNDARY = "frame"
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val clientExecutor = Executors.newCachedThreadPool()
    private var acceptThread: Thread? = null

    // Each connected /mjpeg/live client gets its own bounded queue; pushMjpegFrame()
    // fans the latest JPEG out to all of them without blocking the camera thread.
    private val mjpegClients = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()

    fun start() {
        if (running.get()) return
        running.set(true)
        serverSocket = ServerSocket(port)
        acceptThread = Thread({ acceptLoop() }, "CamIP-HttpAccept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "HTTP server listening on port $port")
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        mjpegClients.clear()
        clientExecutor.shutdownNow()
    }

    /** Called by the camera pipeline whenever a new MJPEG-fallback JPEG frame is ready. */
    fun pushMjpegFrame(jpeg: ByteArray) {
        for (queue in mjpegClients) {
            if (!queue.offer(jpeg)) {
                queue.poll()
                queue.offer(jpeg)
            }
        }
    }

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "accept() failed: ${e.message}")
                break
            }
            clientExecutor.submit { handleClient(client) }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            // Drain remaining request headers (we don't need them, but must
            // read them off the socket before responding on keep-alive-less
            // connections).
            var line: String?
            var contentLength = 0
            while (true) {
                line = reader.readLine()
                if (line.isNullOrEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                reader.read(buf, 0, contentLength)
                body = String(buf)
            }

            val out = BufferedOutputStream(socket.getOutputStream())
            routeRequest(method, path, body, out, socket)
        } catch (e: Exception) {
            Log.w(TAG, "Client handling error: ${e.message}")
        } finally {
            // /mjpeg/live intentionally keeps the socket open until the
            // client disconnects; everything else closes immediately after
            // responding.
        }
    }

    private fun routeRequest(method: String, rawPath: String, body: String, out: OutputStream, socket: Socket) {
        val path = rawPath.substringBefore("?")
        when {
            path == "/" && method == "GET" -> {
                val html = DashboardHtml.render(port)
                writeHttpResponse(out, 200, "text/html; charset=utf-8", html.toByteArray())
                out.flush()
                socket.close()
            }
            path == "/mjpeg/live" && method == "GET" -> {
                serveMjpegStream(out, socket)
            }
            path == "/api/status" && method == "GET" -> {
                val json = buildStatusJson()
                writeHttpResponse(out, 200, "application/json", json.toByteArray())
                out.flush()
                socket.close()
            }
            path == "/api/control" && (method == "GET" || method == "POST") -> {
                val json = handleControl(rawPath, body, method)
                writeHttpResponse(out, 200, "application/json", json.toByteArray())
                out.flush()
                socket.close()
            }
            path.startsWith("/api/camera") && (method == "GET" || method == "POST") -> {
                val json = handleCameraApi(path, rawPath, body, method)
                writeHttpResponse(out, 200, "application/json", json.toByteArray())
                out.flush()
                socket.close()
            }
            else -> {
                val msg = "{\"error\":\"not found\"}"
                writeHttpResponse(out, 404, "application/json", msg.toByteArray())
                out.flush()
                socket.close()
            }
        }
    }

    private fun serveMjpegStream(out: OutputStream, socket: Socket) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
            "Cache-Control: no-cache, private\r\n" +
            "Pragma: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.flush()

        val queue = LinkedBlockingQueue<ByteArray>(4)
        mjpegClients.add(queue)
        try {
            while (running.get() && !socket.isClosed) {
                val frame = queue.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                val partHeader = "--$BOUNDARY\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${frame.size}\r\n\r\n"
                out.write(partHeader.toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (e: Exception) {
            // Client disconnected; this is the normal way an MJPEG stream ends.
        } finally {
            mjpegClients.remove(queue)
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun buildStatusJson(): String {
        val s = StreamState.stats.value
        val json = JSONObject()
        json.put("streaming", s.isStreaming)
        json.put("encoder_status", s.encoderStatus.name)
        json.put("resolution", "${s.resolutionWidth}x${s.resolutionHeight}")
        json.put("target_fps", s.targetFps)
        json.put("measured_fps", s.measuredFps)
        json.put("bitrate_kbps", s.bitrateKbps)
        json.put("active_camera", if (s.activeCameraFacingBack) "back" else "front")
        json.put("flash", s.flashOn)
        json.put("zoom", s.zoomRatio)
        json.put("battery_percent", s.batteryPercent)
        json.put("udp_destination_ip", s.udpDestinationIp)
        json.put("udp_destination_port", s.udpDestinationPort)
        json.put("http_port", s.httpPort)
        json.put("http_server_up", s.httpServerUp)
        json.put("max_zoom", controlHandler.maxZoomRatio())
        json.put("source", if (s.screenShareActive) "screen" else "camera")
        json.put("screen_share", s.screenShareActive)
        json.put("mjpeg_quality", s.mjpegQuality)
        json.put("mjpeg_resolution", "${s.mjpegWidth}x${s.mjpegHeight}")
        json.put("exposure_compensation", s.exposureCompensation)
        json.put("video_stabilization", s.videoStabilization)
        json.put("total_encoded_frames", s.totalEncodedFrames)
        json.put("dropped_frames", s.droppedFrames)
        json.put("udp_packets_sent", s.udpPacketsSent)
        json.put("udp_send_errors", s.udpSendErrors)
        json.put("last_error", s.lastError)
        json.put("phone_ip", s.phoneIpAddress)
        return json.toString()
    }

    private fun handleControl(rawPath: String, body: String, method: String): String {
        val query = if (method == "GET" && rawPath.contains("?")) rawPath.substringAfter("?") else body
        val params = parseParams(query)
        val action = params["action"] ?: return errorJson("missing 'action' parameter")

        return try {
            when (action) {
                "zoom" -> {
                    val ratio = params["value"]?.toFloatOrNull()
                        ?: return errorJson("zoom requires numeric 'value'")
                    controlHandler.setZoom(ratio)
                    okJson()
                }
                "flash" -> {
                    val enabled = params["value"]?.let { it == "1" || it.equals("true", true) } ?: false
                    controlHandler.setFlash(enabled)
                    okJson()
                }
                "switch_camera" -> {
                    controlHandler.switchCamera()
                    okJson()
                }
                "focus" -> {
                    controlHandler.triggerFocus()
                    okJson()
                }
                "bitrate" -> {
                    val kbps = params["value"]?.toIntOrNull()
                        ?: return errorJson("bitrate requires numeric 'value' (kbps)")
                    if (kbps < AppSettings.MIN_BITRATE_KBPS || kbps > AppSettings.MAX_BITRATE_KBPS) {
                        return errorJson("bitrate must be between ${AppSettings.MIN_BITRATE_KBPS} and ${AppSettings.MAX_BITRATE_KBPS} kbps")
                    }
                    controlHandler.setBitrateKbps(kbps)
                    runBlocking { appSettings.update(bitrateKbps = kbps) }
                    okJson()
                }
                "resolution" -> {
                    val value = params["value"] ?: return errorJson("resolution requires 'value' as WIDTHxHEIGHT")
                    val dims = value.split("x", "X")
                    if (dims.size != 2) return errorJson("resolution must look like 1280x720")
                    val w = dims[0].toIntOrNull()
                    val h = dims[1].toIntOrNull()
                    if (w == null || h == null) return errorJson("resolution must look like 1280x720")
                    controlHandler.setResolution(w, h)
                    okJson()
                }
                "keyframe" -> {
                    controlHandler.requestKeyFrame()
                    okJson()
                }
                "udp_destination" -> {
                    val ip = params["ip"] ?: return errorJson("udp_destination requires 'ip'")
                    val portValue = params["port"]?.toIntOrNull() ?: return errorJson("udp_destination requires numeric 'port'")
                    if (!isValidIPv4(ip)) return errorJson("invalid IPv4 address")
                    if (!isValidPort(portValue)) return errorJson("invalid port")
                    controlHandler.setUdpDestination(ip, portValue)
                    runBlocking { appSettings.update(destinationIp = ip, destinationPort = portValue) }
                    okJson()
                }
                "mjpeg_quality" -> {
                    val quality = params["value"]?.toIntOrNull()
                        ?: return errorJson("mjpeg_quality requires numeric 'value' (30..100)")
                    if (quality < AppSettings.MIN_MJPEG_QUALITY || quality > AppSettings.MAX_MJPEG_QUALITY) {
                        return errorJson(
                            "mjpeg_quality must be between ${AppSettings.MIN_MJPEG_QUALITY} and ${AppSettings.MAX_MJPEG_QUALITY}"
                        )
                    }
                    controlHandler.setMjpegQuality(quality)
                    runBlocking { appSettings.update(mjpegQuality = quality) }
                    okJson()
                }
                "mjpeg_resolution" -> {
                    val value = params["value"] ?: return errorJson("mjpeg_resolution requires 'value' as WIDTHxHEIGHT")
                    val dims = value.split("x", "X")
                    if (dims.size != 2) return errorJson("mjpeg_resolution must look like 640x360")
                    val w = dims[0].toIntOrNull()
                    val h = dims[1].toIntOrNull()
                    if (w == null || h == null) return errorJson("mjpeg_resolution must look like 640x360")
                    controlHandler.setMjpegResolution(w, h)
                    okJson()
                }
                "exposure" -> {
                    val value = params["value"]?.toIntOrNull()
                        ?: return errorJson("exposure requires numeric 'value'")
                    if (!controlHandler.setExposureCompensation(value)) {
                        return errorJson("exposure could not be applied (camera not ready or unsupported)")
                    }
                    okJson()
                }
                "stabilization" -> {
                    val enabled = parseBool(params["value"])
                    if (!controlHandler.setVideoStabilization(enabled)) {
                        return errorJson("stabilization could not be applied (camera not ready or unsupported)")
                    }
                    okJson()
                }
                "screen_share" -> {
                    if (parseBool(params["value"])) {
                        if (controlHandler.requestScreenShare()) okJson()
                        else errorJson(
                            "Could not open the screen-capture prompt. Open the CamIP app on the phone and tap \"Screen share\"."
                        )
                    } else {
                        controlHandler.stopScreenShare()
                        okJson()
                    }
                }
                else -> errorJson("unknown action '$action'")
            }
        } catch (e: Exception) {
            errorJson("control action failed: ${e.message}")
        }
    }

    /**
     * Camera endpoints:
     *
     *   GET  /api/camera                   -> camera state + capabilities (JSON)
     *   GET|POST /api/camera/zoom?value=2  -> digital zoom ratio
     *   GET|POST /api/camera/torch?value=1 -> torch/flashlight on/off
     *   GET|POST /api/camera/switch        -> flip front/back
     *   GET|POST /api/camera/focus         -> trigger a one-shot AF scan
     *   GET|POST /api/camera/exposure?value=-2 -> AE exposure compensation
     *   GET|POST /api/camera/stabilization?value=1 -> video stabilization on/off
     */
    private fun handleCameraApi(path: String, rawPath: String, body: String, method: String): String {
        val params = if (method == "GET" && rawPath.contains("?")) {
            parseParams(rawPath.substringAfter("?"))
        } else {
            parseParams(body)
        }
        val endpoint = path.removePrefix("/api/camera").trim('/')

        return try {
            if (endpoint.isNotEmpty() && !controlHandler.cameraInfo().active) {
                return errorJson("camera is not active — start the stream first")
            }
            when (endpoint) {
                "" -> cameraInfoJson()
                "zoom" -> {
                    val ratio = params["value"]?.toFloatOrNull()
                        ?: return errorJson("zoom requires numeric 'value'")
                    controlHandler.setZoom(ratio)
                    val info = controlHandler.cameraInfo()
                    JSONObject()
                        .put("ok", true)
                        .put("zoom", info.zoom)
                        .put("max_zoom", info.maxZoom)
                        .toString()
                }
                "torch" -> {
                    val enabled = parseBool(params["value"])
                    controlHandler.setFlash(enabled)
                    JSONObject().put("ok", true).put("torch", controlHandler.cameraInfo().torchOn).toString()
                }
                "switch" -> {
                    controlHandler.switchCamera()
                    JSONObject().put("ok", true).put("facing", controlHandler.cameraInfo().facing).toString()
                }
                "focus" -> {
                    controlHandler.triggerFocus()
                    okJson()
                }
                "exposure" -> {
                    val value = params["value"]?.toIntOrNull()
                        ?: return errorJson("exposure requires numeric 'value'")
                    if (!controlHandler.setExposureCompensation(value)) {
                        return errorJson("exposure could not be applied (camera not ready or unsupported)")
                    }
                    val info = controlHandler.cameraInfo()
                    JSONObject()
                        .put("ok", true)
                        .put("exposure_compensation", info.exposureCompensation)
                        .put("exposure_range", JSONArray(listOf(info.exposureMin, info.exposureMax)))
                        .toString()
                }
                "stabilization" -> {
                    val enabled = parseBool(params["value"])
                    if (!controlHandler.setVideoStabilization(enabled)) {
                        return errorJson("stabilization could not be applied (camera not ready or unsupported)")
                    }
                    val info = controlHandler.cameraInfo()
                    JSONObject()
                        .put("ok", true)
                        .put("stabilization", info.stabilizationOn)
                        .put("supported", info.stabilizationSupported)
                        .toString()
                }
                else -> errorJson("unknown camera endpoint '/api/camera/$endpoint'")
            }
        } catch (e: Exception) {
            errorJson("camera command failed: ${e.message}")
        }
    }

    private fun cameraInfoJson(): String {
        val info = controlHandler.cameraInfo()
        val s = StreamState.stats.value
        return JSONObject()
            .put("active", info.active)
            .put("source", if (s.screenShareActive) "screen" else "camera")
            .put("screen_share", s.screenShareActive)
            .put("facing", info.facing)
            .put("zoom", info.zoom)
            .put("max_zoom", info.maxZoom)
            .put("torch", info.torchOn)
            .put("exposure_compensation", info.exposureCompensation)
            .put("exposure_range", JSONArray(listOf(info.exposureMin, info.exposureMax)))
            .put("stabilization_supported", info.stabilizationSupported)
            .put("stabilization", info.stabilizationOn)
            .put("available_cameras", JSONArray(info.availableCameras))
            .toString()
    }

    private fun parseParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf("=")
            if (idx < 0) return@mapNotNull null
            val key = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val value = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            key to value
        }.toMap()
    }

    private fun parseBool(value: String?): Boolean =
        value != null && (value == "1" || value.equals("true", true) || value.equals("on", true))

    private fun okJson() = "{\"ok\":true}"
    private fun errorJson(message: String) = JSONObject().put("ok", false).put("error", message).toString()

    private fun writeHttpResponse(out: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val statusText = if (status == 200) "OK" else if (status == 404) "Not Found" else "Error"
        val header = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(body)
    }
}
