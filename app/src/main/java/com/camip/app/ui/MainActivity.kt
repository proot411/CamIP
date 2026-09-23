package com.camip.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camip.app.model.AppSettings
import com.camip.app.model.EncoderStatus
import com.camip.app.model.MjpegResolutionPreset
import com.camip.app.model.ResolutionPreset
import com.camip.app.model.StreamState
import com.camip.app.model.isValidIPv4
import com.camip.app.model.isValidPort
import com.camip.app.server.PipelineControl
import com.camip.app.service.StreamService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigInteger

class MainActivity : ComponentActivity() {

    private lateinit var appSettings: AppSettings

    /** Bumped whenever the permission dialog resolves, to re-read grant state. */
    private var permissionEpoch by mutableIntStateOf(0)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionEpoch++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appSettings = AppSettings(applicationContext)

        val required = requiredPermissions()
        val missing = required.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions.launch(missing.toTypedArray())
        }

        setContent {
            MaterialTheme(colorScheme = CamIpColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val cameraGranted = remember(permissionEpoch) {
                        checkSelfPermission(Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    CamIpScreen(
                        appSettings = appSettings,
                        localIp = getLocalIpAddress() ?: "0.0.0.0",
                        cameraGranted = cameraGranted,
                        onRequestCameraPermission = {
                            requestPermissions.launch(arrayOf(Manifest.permission.CAMERA))
                        },
                        onStart = {
                            startService(
                                Intent(this, StreamService::class.java)
                                    .apply { action = StreamService.ACTION_START }
                            )
                        },
                        onStop = {
                            startService(
                                Intent(this, StreamService::class.java)
                                    .apply { action = StreamService.ACTION_STOP }
                            )
                        }
                    )
                }
            }
        }
    }

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms
    }

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) return null
            val bytes = BigInteger.valueOf(ipInt.toLong()).toByteArray().reversedArray()
            if (bytes.size != 4) return null
            "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}"
        } catch (e: Exception) {
            null
        }
    }
}

private val CamIpColorScheme = darkColorScheme(
    primary = Color(0xFF6FA8FF),
    onPrimary = Color(0xFF00112E),
    secondary = Color(0xFF00E5A0),
    onSecondary = Color(0xFF003326),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C2430),
    onSurfaceVariant = Color(0xFF8B949E),
    outline = Color(0xFF30363D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0000)
)

private const val DEFAULT_MAX_ZOOM = 5f
private const val START_TIMEOUT_MS = 8_000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CamIpScreen(
    appSettings: AppSettings,
    localIp: String,
    cameraGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Live pipeline state, observed in-process: the UI and StreamService
    // share a process, so every status change lands here immediately.
    val stats by StreamState.stats.collectAsState()
    val settings by appSettings.settingsFlow.collectAsState(initial = null)

    var seeded by remember { mutableStateOf(false) }
    var destIp by remember { mutableStateOf("") }
    var destPort by remember { mutableStateOf("") }
    var bitrate by remember { mutableIntStateOf(AppSettings.DEFAULT_BITRATE_KBPS) }
    var resolution by remember { mutableStateOf(ResolutionPreset.R_1280x720) }
    var keyFrameInterval by remember { mutableIntStateOf(AppSettings.DEFAULT_KEYFRAME_INTERVAL_SECONDS) }

    var startPending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var dismissedError by remember { mutableStateOf<String?>(null) }
    var zoomDrag by remember { mutableStateOf<Float?>(null) }
    var maxZoom by remember { mutableFloatStateOf(DEFAULT_MAX_ZOOM) }
    var mjpegQuality by remember { mutableIntStateOf(AppSettings.DEFAULT_MJPEG_QUALITY) }
    var mjpegResolution by remember { mutableStateOf(MjpegResolutionPreset.R_640x360) }

    // Seed the editable fields once from persisted settings; never re-seed
    // mid-edit (DataStore re-emits whenever anything is saved).
    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!seeded) {
            seeded = true
            destIp = s.destinationIp
            destPort = s.destinationPort.toString()
            bitrate = s.bitrateKbps
            resolution = s.resolution
            keyFrameInterval = s.keyFrameIntervalSeconds
            mjpegQuality = s.mjpegQuality
            mjpegResolution = s.mjpegResolution
        }
    }

    val isLive = stats.isStreaming && stats.encoderStatus == EncoderStatus.RUNNING
    val isStarting = startPending || stats.encoderStatus == EncoderStatus.STARTING
    val isError = stats.encoderStatus == EncoderStatus.ERROR
    val serviceUp = isLive || isStarting || isError || stats.httpServerUp

    // The service answers STARTING/RUNNING, or fails outright: either way
    // the pending Start tap can be cleared as soon as there is an answer.
    LaunchedEffect(stats.encoderStatus, stats.isStreaming) {
        if (stats.encoderStatus == EncoderStatus.STARTING || stats.isStreaming) {
            startPending = false
        }
    }

    // Watchdog: if nothing answers within the timeout, tell the user why.
    LaunchedEffect(startPending) {
        if (!startPending) return@LaunchedEffect
        delay(START_TIMEOUT_MS)
        if (startPending) {
            startPending = false
            notice = "The streaming service didn't respond. " +
                "Check the camera permission and try again."
        }
    }

    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(6_000)
        notice = null
    }

    LaunchedEffect(isLive) {
        if (isLive) maxZoom = PipelineControl.maxZoomRatio().coerceAtLeast(1f)
    }

    fun publishNotice(message: String) {
        notice = message
    }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        publishNotice("Copied: $text")
    }

    val phoneIp = stats.phoneIpAddress.ifBlank { localIp }
    val httpUrl = "http://$phoneIp:${AppSettings.DEFAULT_HTTP_PORT}/"
    val mjpegUrl = "http://$phoneIp:${AppSettings.DEFAULT_HTTP_PORT}/mjpeg/live"
    val udpPort = stats.udpDestinationPort.takeIf { it != 0 }
        ?: destPort.toIntOrNull()
        ?: AppSettings.DEFAULT_UDP_PORT
    val obsUrl = "udp://0.0.0.0:$udpPort"
    val ffplayCommand = "ffplay -f mpegts -fflags nobuffer -flags low_delay -sync ext $obsUrl"

    val errorText = stats.lastError?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // -------------------------------------------------------------
        // Header
        // -------------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("CamIP", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            StatusPill(
                label = when {
                    isLive -> "LIVE"
                    isStarting -> "STARTING"
                    isError -> "ERROR"
                    else -> "STOPPED"
                },
                container = when {
                    isLive -> MaterialTheme.colorScheme.secondary
                    isStarting -> MaterialTheme.colorScheme.primary
                    isError -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                content = when {
                    isLive -> Color(0xFF05201A)
                    isStarting -> MaterialTheme.colorScheme.onPrimary
                    isError -> MaterialTheme.colorScheme.onError
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        Text(
            text = when {
                isLive ->
                    "Streaming — encoder ${stats.encoderStatus.name.lowercase()} at " +
                        "${stats.resolutionWidth}x${stats.resolutionHeight}, " +
                        "%.1f fps".format(stats.measuredFps) +
                        if (stats.screenShareActive) ", screen share" else ""
                isStarting -> "Starting the camera and encoder…"
                isError -> "Not streaming — ${errorText ?: "unknown error"}"
                stats.httpServerUp -> "Service running, stream stopped"
                else -> "Stopped"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                isLive -> MaterialTheme.colorScheme.secondary
                isError -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        notice?.let { message ->
            Banner(
                message = message,
                container = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                content = MaterialTheme.colorScheme.onBackground
            )
        }

        if (errorText != null && errorText != dismissedError) {
            Banner(
                message = "Error: $errorText",
                container = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                content = MaterialTheme.colorScheme.error,
                actionLabel = "Dismiss",
                onAction = { dismissedError = errorText }
            )
        }

        // -------------------------------------------------------------
        // Stream control
        // -------------------------------------------------------------
        SectionCard(title = "Stream") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (!cameraGranted) {
                            publishNotice("Camera permission is required to start streaming.")
                            onRequestCameraPermission()
                        } else {
                            startPending = true
                            onStart()
                        }
                    },
                    enabled = !isLive && !isStarting,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    if (isStarting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Starting…")
                    } else {
                        Text(if (isError) "Retry streaming" else "Start streaming")
                    }
                }
                OutlinedButton(
                    onClick = {
                        startPending = false
                        onStop()
                    },
                    enabled = serviceUp,
                    modifier = Modifier.height(48.dp)
                ) { Text("Stop") }
            }

            Spacer(Modifier.height(10.dp))
            InfoRow("Camera & encoder", stats.encoderStatus.name)
            InfoRow(
                "Source",
                if (stats.screenShareActive) "Screen share" else "Camera"
            )
            InfoRow(
                "HTTP server",
                if (stats.httpServerUp) "Up — port ${stats.httpPort}" else "Down"
            )
            InfoRow(
                "Sending to",
                "${stats.udpDestinationIp.ifBlank { "-" }}:${stats.udpDestinationPort.takeIf { it != 0 } ?: "-"}"
            )
        }

        // -------------------------------------------------------------
        // Live stats
        // -------------------------------------------------------------
        SectionCard(title = "Live stats") {
            val statItems = listOf(
                "Status" to if (isLive) "Streaming" else stats.encoderStatus.name,
                "Source" to if (stats.screenShareActive) "Screen" else "Camera",
                "Resolution" to "${stats.resolutionWidth}x${stats.resolutionHeight}",
                "FPS" to "%.1f / %d".format(stats.measuredFps, stats.targetFps),
                "Bitrate" to "${stats.bitrateKbps} kbps",
                "MJPEG" to "${stats.mjpegWidth}x${stats.mjpegHeight} q${stats.mjpegQuality}",
                "Frames" to stats.totalEncodedFrames.toString(),
                "UDP packets" to stats.udpPacketsSent.toString(),
                "UDP errors" to stats.udpSendErrors.toString(),
                "Battery" to if (stats.batteryPercent >= 0) "${stats.batteryPercent}%" else "—",
                "Camera" to if (stats.screenShareActive) "—" else if (stats.activeCameraFacingBack) "Back" else "Front",
                "Zoom" to if (stats.screenShareActive) "—" else "%.1fx".format(stats.zoomRatio),
                "Flash" to if (stats.flashOn) "On" else "Off",
                "Service" to if (serviceUp) "Running" else "Stopped"
            )
            for (row in statItems.chunked(2)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for ((label, value) in row) {
                        StatCell(label = label, value = value, modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // -------------------------------------------------------------
        // Source: camera or the phone's screen (screen share)
        // -------------------------------------------------------------
        SectionCard(title = "Source") {
            val screenActive = stats.screenShareActive
            // Filled = the source that is currently live (and therefore not
            // tappable again); outlined = the switch that is available.
            val scheme = MaterialTheme.colorScheme
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        // "Stop screen share" while sharing falls back to the
                        // camera; the button is disabled when camera is live.
                        if (screenActive) PipelineControl.stopScreenShare()
                    },
                    enabled = screenActive && !isStarting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!screenActive) scheme.primary else scheme.surfaceVariant,
                        contentColor = if (!screenActive) scheme.onPrimary else scheme.onSurfaceVariant,
                        disabledContainerColor = if (!screenActive) scheme.primary else scheme.surfaceVariant,
                        disabledContentColor = if (!screenActive) scheme.onPrimary else scheme.onSurfaceVariant
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text(if (screenActive) "Stop screen share" else "Camera") }
                Button(
                    onClick = {
                        // Handled by the same consent trampoline the API uses.
                        context.startActivity(Intent(context, ScreenConsentActivity::class.java))
                    },
                    enabled = !screenActive && !isStarting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (screenActive) scheme.primary else scheme.surfaceVariant,
                        contentColor = if (screenActive) scheme.onPrimary else scheme.onSurfaceVariant,
                        disabledContainerColor = if (screenActive) scheme.primary else scheme.surfaceVariant,
                        disabledContentColor = if (screenActive) scheme.onPrimary else scheme.onSurfaceVariant
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when {
                            screenActive && isStarting -> "Starting…"
                            screenActive -> "Sharing screen"
                            else -> "Screen share"
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    screenActive -> "Sharing the phone's screen. Tap Stop screen share " +
                        "(or revoke screen capture) to go back to the camera."
                    isStarting && stats.encoderStatus == EncoderStatus.STARTING ->
                        "Switching sources…"
                    isLive -> "Streaming the live camera. Screen share replaces the " +
                        "camera until you stop it."
                    else -> "Camera mode. Screen share asks Android for screen-capture " +
                        "permission, then streams your screen instead."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // -------------------------------------------------------------
        // Camera controls
        // -------------------------------------------------------------
        SectionCard(title = "Camera controls") {
            if (stats.screenShareActive) {
                Text(
                    "Camera controls are unavailable while screen sharing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            if (!isLive) {
                Text(
                    "Start the stream to use camera controls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            val cameraReady = isLive && !stats.screenShareActive
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { PipelineControl.switchCamera() }, enabled = cameraReady) {
                    Text("Switch camera")
                }
                OutlinedButton(onClick = { PipelineControl.triggerFocus() }, enabled = cameraReady) {
                    Text("Focus")
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { PipelineControl.setFlash(!stats.flashOn) },
                enabled = cameraReady,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (stats.flashOn) Color(0xFFFFB300)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (stats.flashOn) Color(0xFF2B1A00)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(if (stats.flashOn) "Flash: On" else "Flash: Off")
            }
            Spacer(Modifier.height(12.dp))
            Text("Zoom: %.1fx".format(zoomDrag ?: stats.zoomRatio))
            Slider(
                value = (zoomDrag ?: stats.zoomRatio).coerceIn(1f, maxZoom),
                onValueChange = { zoomDrag = it },
                onValueChangeFinished = {
                    zoomDrag?.let { value -> PipelineControl.setZoom(value) }
                    zoomDrag = null
                },
                enabled = cameraReady,
                valueRange = 1f..maxZoom
            )
        }

        // -------------------------------------------------------------
        // Stream settings
        // -------------------------------------------------------------
        SectionCard(title = "Stream settings") {
            Text("Resolution", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            // Four presets no longer fit in one row — FlowRow wraps them.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResolutionPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = resolution == preset,
                        onClick = {
                            resolution = preset
                            scope.launch { appSettings.update(resolution = preset) }
                            PipelineControl.setResolution(preset.width, preset.height)
                        },
                        label = { Text(preset.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Bitrate: $bitrate kbps", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = bitrate.toFloat(),
                onValueChange = { bitrate = it.toInt() },
                onValueChangeFinished = {
                    scope.launch { appSettings.update(bitrateKbps = bitrate) }
                    PipelineControl.setBitrateKbps(bitrate)
                },
                valueRange = AppSettings.MIN_BITRATE_KBPS.toFloat()..AppSettings.MAX_BITRATE_KBPS.toFloat()
            )
            Spacer(Modifier.height(12.dp))
            Text("MJPEG preview resolution", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            // The camera negotiates the closest advertised YUV size, so this
            // is the target; the active size is shown in the stats grid.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MjpegResolutionPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = mjpegResolution == preset,
                        onClick = {
                            mjpegResolution = preset
                            scope.launch { appSettings.update(mjpegResolution = preset) }
                            PipelineControl.setMjpegResolution(preset.width, preset.height)
                        },
                        label = { Text(preset.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "MJPEG preview quality: $mjpegQuality" +
                    if (mjpegQuality < 60) " (smaller files, blockier preview)" else "",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = mjpegQuality.toFloat(),
                onValueChange = { mjpegQuality = it.toInt() },
                onValueChangeFinished = {
                    scope.launch {
                        appSettings.update(mjpegQuality = mjpegQuality)
                    }
                    PipelineControl.setMjpegQuality(mjpegQuality)
                },
                valueRange = AppSettings.MIN_MJPEG_QUALITY.toFloat()..AppSettings.MAX_MJPEG_QUALITY.toFloat(),
                steps = (AppSettings.MAX_MJPEG_QUALITY - AppSettings.MIN_MJPEG_QUALITY - 1)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Keyframe interval: $keyFrameInterval s" +
                    if (isLive) " (applies on next Start)" else "",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = keyFrameInterval.toFloat(),
                onValueChange = { keyFrameInterval = it.toInt() },
                onValueChangeFinished = {
                    scope.launch { appSettings.update(keyFrameIntervalSeconds = keyFrameInterval) }
                },
                valueRange = 1f..5f,
                steps = 3
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { PipelineControl.requestKeyFrame() },
                    enabled = isLive
                ) { Text("Force keyframe now") }
                Button(onClick = {
                    scope.launch { appSettings.update(bitrateKbps = bitrate) }
                    publishNotice("Settings saved")
                }) { Text("Save") }
            }
        }

        // -------------------------------------------------------------
        // UDP destination
        // -------------------------------------------------------------
        SectionCard(title = "UDP destination (the PC running OBS)") {
            OutlinedTextField(
                value = destIp,
                onValueChange = { destIp = it },
                label = { Text("Destination IP (e.g. 192.168.1.50)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = destPort,
                onValueChange = { destPort = it.filter(Char::isDigit) },
                label = { Text("Destination UDP port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                val port = destPort.toIntOrNull()
                if (!isValidIPv4(destIp)) {
                    publishNotice("Enter a valid IPv4 address.")
                    return@Button
                }
                if (port == null || !isValidPort(port)) {
                    publishNotice("Enter a valid UDP port (1-65535).")
                    return@Button
                }
                scope.launch { appSettings.update(destinationIp = destIp, destinationPort = port) }
                if (PipelineControl.setUdpDestination(destIp, port)) {
                    publishNotice("Destination updated: $destIp:$port")
                } else {
                    publishNotice("Saved — applies when you start the stream.")
                }
            }) { Text("Save destination") }
        }

        // -------------------------------------------------------------
        // Addresses / receiver setup
        // -------------------------------------------------------------
        SectionCard(title = "Addresses & receiver setup") {
            CopyableRow("Phone IP", phoneIp, onCopy = { copyToClipboard("Phone IP", phoneIp) })
            CopyableRow("Dashboard", httpUrl, onCopy = { copyToClipboard("Dashboard", httpUrl) })
            CopyableRow("MJPEG fallback", mjpegUrl, onCopy = { copyToClipboard("MJPEG", mjpegUrl) })
            CopyableRow("OBS / UDP URL", obsUrl, onCopy = { copyToClipboard("OBS URL", obsUrl) })
            Spacer(Modifier.height(10.dp))
            Text("Low-latency test on the PC", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    ffplayCommand,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { copyToClipboard("ffplay command", ffplayCommand) }) {
                Text("Copy ffplay command")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "OBS: Sources → Add → Media Source → uncheck \"Local File\" → " +
                    "Input: $obsUrl → Input Format: mpegts → in Advanced, set " +
                    "FFmpeg Options to fflags=nobuffer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusPill(label: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(content, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Banner(
    message: String,
    container: Color,
    content: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(color = container, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                modifier = Modifier.weight(1f)
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = content, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CopyableRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onCopy, contentPadding = PaddingValues(start = 8.dp, end = 0.dp)) {
                Text("Copy", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
