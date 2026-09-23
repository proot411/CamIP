package com.camip.app.model

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "camip_settings")

/**
 * Resolution presets CamIP supports. 720p is the preferred/default target;
 * lower presets are offered as a graceful-degradation path for weaker devices.
 */
enum class ResolutionPreset(val width: Int, val height: Int, val label: String) {
    R_1920x1080(1920, 1080, "1920x1080 (Full HD)"),
    R_1280x720(1280, 720, "1280x720"),
    R_960x540(960, 540, "960x540"),
    R_640x480(640, 480, "640x480");

    companion object {
        fun fromLabelOrDefault(name: String?): ResolutionPreset =
            entries.firstOrNull { it.name == name } ?: R_1280x720
    }
}

/**
 * Size target for the MJPEG preview/fallback stream. The camera path
 * negotiates the closest advertised YUV size (see MjpegFrameSource.chooseSize),
 * the screen-share path uses it verbatim.
 */
enum class MjpegResolutionPreset(val width: Int, val height: Int, val label: String) {
    R_320x180(320, 180, "320x180"),
    R_480x270(480, 270, "480x270"),
    R_640x360(640, 360, "640x360"),
    R_960x540(960, 540, "960x540"),
    R_1280x720(1280, 720, "1280x720");

    companion object {
        fun fromNameOrDefault(name: String?): MjpegResolutionPreset =
            entries.firstOrNull { it.name == name } ?: R_640x360
    }
}

data class StreamSettings(
    val destinationIp: String,
    val destinationPort: Int,
    val bitrateKbps: Int,
    val resolution: ResolutionPreset,
    val keyFrameIntervalSeconds: Int,
    val mjpegQuality: Int,
    val mjpegResolution: MjpegResolutionPreset
)

/**
 * Thin wrapper around Jetpack DataStore that persists the user-configurable
 * streaming settings described in the spec: destination IP, UDP port,
 * bitrate, resolution and keyframe interval.
 */
class AppSettings(private val context: Context) {

    private object Keys {
        val DEST_IP = stringPreferencesKey("dest_ip")
        val DEST_PORT = intPreferencesKey("dest_port")
        val BITRATE_KBPS = intPreferencesKey("bitrate_kbps")
        val RESOLUTION = stringPreferencesKey("resolution")
        val KEYFRAME_INTERVAL = intPreferencesKey("keyframe_interval")
        val MJPEG_QUALITY = intPreferencesKey("mjpeg_quality")
        val MJPEG_RESOLUTION = stringPreferencesKey("mjpeg_resolution")
    }

    companion object {
        const val DEFAULT_UDP_PORT = 5000
        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_BITRATE_KBPS = 3000
        const val MIN_BITRATE_KBPS = 1500
        // 1080p needs the headroom; 8 Mbps is a bit tight for Full HD @30.
        const val MAX_BITRATE_KBPS = 12000
        const val DEFAULT_KEYFRAME_INTERVAL_SECONDS = 1
        const val DEFAULT_MJPEG_QUALITY = 80
        const val MIN_MJPEG_QUALITY = 30
        const val MAX_MJPEG_QUALITY = 100
    }

    val settingsFlow: Flow<StreamSettings> = context.dataStore.data.map { prefs ->
        StreamSettings(
            destinationIp = prefs[Keys.DEST_IP] ?: "192.168.1.50",
            destinationPort = prefs[Keys.DEST_PORT] ?: DEFAULT_UDP_PORT,
            bitrateKbps = prefs[Keys.BITRATE_KBPS] ?: DEFAULT_BITRATE_KBPS,
            resolution = ResolutionPreset.fromLabelOrDefault(prefs[Keys.RESOLUTION]),
            keyFrameIntervalSeconds = prefs[Keys.KEYFRAME_INTERVAL] ?: DEFAULT_KEYFRAME_INTERVAL_SECONDS,
            mjpegQuality = prefs[Keys.MJPEG_QUALITY] ?: DEFAULT_MJPEG_QUALITY,
            mjpegResolution = MjpegResolutionPreset.fromNameOrDefault(prefs[Keys.MJPEG_RESOLUTION])
        )
    }

    suspend fun current(): StreamSettings = settingsFlow.first()

    suspend fun update(
        destinationIp: String? = null,
        destinationPort: Int? = null,
        bitrateKbps: Int? = null,
        resolution: ResolutionPreset? = null,
        keyFrameIntervalSeconds: Int? = null,
        mjpegQuality: Int? = null,
        mjpegResolution: MjpegResolutionPreset? = null
    ) {
        context.dataStore.edit { prefs ->
            destinationIp?.let { prefs[Keys.DEST_IP] = it }
            destinationPort?.let { prefs[Keys.DEST_PORT] = it }
            bitrateKbps?.let { prefs[Keys.BITRATE_KBPS] = it.coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS) }
            resolution?.let { prefs[Keys.RESOLUTION] = it.name }
            keyFrameIntervalSeconds?.let { prefs[Keys.KEYFRAME_INTERVAL] = it.coerceIn(1, 5) }
            mjpegQuality?.let { prefs[Keys.MJPEG_QUALITY] = it.coerceIn(MIN_MJPEG_QUALITY, MAX_MJPEG_QUALITY) }
            mjpegResolution?.let { prefs[Keys.MJPEG_RESOLUTION] = it.name }
        }
    }
}

/** Simple IPv4 dotted-quad validator used by both the UI and the HTTP API. */
fun isValidIPv4(candidate: String): Boolean {
    val parts = candidate.trim().split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        val n = part.toIntOrNull()
        n != null && n in 0..255 && part == n.toString()
    }
}

fun isValidPort(candidate: Int): Boolean = candidate in 1..65535
