package vn.gamerec.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import vn.gamerec.settings.Quality
import vn.gamerec.settings.VideoCodec

object HardwareVideo {
    private val encoders by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && if (Build.VERSION.SDK_INT >= 29) {
                info.isHardwareAccelerated && !info.isSoftwareOnly
            } else {
                val name = info.name.lowercase()
                legacyHardwarePrefixes.any(name::startsWith) && "software" !in name && ".sw." !in name
            }
        }
    }
    private val legacyHardwarePrefixes = listOf(
        "omx.qcom.video.encoder.", "omx.exynos.", "omx.sec.avc.enc",
        "omx.mtk.video.encoder.", "omx.hisi.video.encoder.", "omx.nvidia.",
        "omx.qcom.audio.encoder.aac", "omx.mtk.audio.encoder.aac"
    )

    data class Profile(val name: String, val width: Int, val height: Int, val quality: Quality, val codec: VideoCodec) {
        fun format() = MediaFormat.createVideoFormat(codec.mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, quality.fps)
            setInteger("max-fps-to-encoder", quality.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
    }

    private fun capabilities(codec: VideoCodec) = encoders.asSequence().mapNotNull { info ->
        val capabilities = runCatching { info.getCapabilitiesForType(codec.mime) }.getOrNull()
        capabilities?.takeIf {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in it.colorFormats
        }?.let { info.name to it.videoCapabilities }
    }

    fun audioEncoder(): String? = encoders.firstOrNull { info ->
        if (Build.VERSION.SDK_INT < 29 && !info.name.contains(".audio.encoder.", ignoreCase = true)) return@firstOrNull false
        val audio = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC).audioCapabilities }.getOrNull()
        audio != null && audio.isSampleRateSupported(48_000) && audio.bitrateRange.contains(96_000)
    }?.name

    fun supported(codec: VideoCodec): Boolean = capabilities(codec).any()

    fun profiles(codec: VideoCodec, quality: Quality, landscape: Boolean): List<Profile> =
        capabilities(codec).mapNotNull { (name, capabilities) ->
            val long = quality.lines * 16 / 9
            val width = align(if (landscape) long else quality.lines, capabilities.widthAlignment)
            val height = align(if (landscape) quality.lines else long, capabilities.heightAlignment)
            if (capabilities.areSizeAndRateSupported(width, height, quality.fps.toDouble()) &&
                capabilities.bitrateRange.contains(quality.bitrate)
            ) Profile(name, width, height, quality, codec) else null
        }.toList()

    private fun align(value: Int, alignment: Int) = value / alignment * alignment
}
