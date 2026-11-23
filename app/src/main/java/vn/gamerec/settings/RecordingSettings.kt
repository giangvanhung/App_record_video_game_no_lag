package vn.gamerec.settings

import android.content.Context
import android.media.MediaFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Quality(val lines: Int, val fps: Int, val bitrate: Int) {
    STANDARD(720, 30, 8_000_000),
    FULL_HD(1080, 30, 16_000_000),
    FULL_HD_FAST(1080, 60, 24_000_000);

    val label get() = "${lines}p · $fps FPS · ${bitrate / 1_000_000} Mbps"
}

enum class VideoCodec(val label: String, val mime: String) {
    AVC("H.264", MediaFormat.MIMETYPE_VIDEO_AVC),
    HEVC("H.265", MediaFormat.MIMETYPE_VIDEO_HEVC)
}

enum class SaveFolder(val relativePath: String) {
    GAMEREC("Movies/GameRec"), GAMEPLAY("Movies/GameRec/Gameplay"), MOVIES("Movies")
}

data class RecordingSettings(
    val quality: Quality = Quality.STANDARD,
    val codec: VideoCodec = VideoCodec.AVC,
    val microphone: Boolean = false,
    val limitMinutes: Int = 0,
    val folder: SaveFolder = SaveFolder.GAMEREC,
    val autoReduce: Boolean = true,
    val landscape: Boolean = false
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun <T : Enum<T>> read(key: String, values: Array<T>) =
        values.firstOrNull { it.name == prefs.getString(key, null) } ?: values.first()

    private val mutable = MutableStateFlow(
        RecordingSettings(
            read("quality", Quality.entries.toTypedArray()), read("codec", VideoCodec.entries.toTypedArray()),
            prefs.getBoolean("microphone", false), prefs.getInt("limit", 0),
            read("folder", SaveFolder.entries.toTypedArray()), prefs.getBoolean("auto", true),
            prefs.getBoolean("landscape", false)
        )
    )
    val state = mutable.asStateFlow()

    fun update(change: (RecordingSettings) -> RecordingSettings) {
        val value = change(mutable.value)
        prefs.edit().putString("quality", value.quality.name).putString("codec", value.codec.name)
            .putBoolean("microphone", value.microphone).putInt("limit", value.limitMinutes)
            .putString("folder", value.folder.name).putBoolean("auto", value.autoReduce)
            .putBoolean("landscape", value.landscape).apply()
        mutable.value = value
    }
}
