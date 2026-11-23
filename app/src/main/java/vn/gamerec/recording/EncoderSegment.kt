package vn.gamerec.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer

/** Owns one MP4 and all codec resources. Every method runs on the recording worker. */
internal class EncoderSegment(private val muxer: MediaMuxer) {
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var microphone: AudioRecord? = null
    private var inputSurface: Surface? = null
    val surface get() = checkNotNull(inputSurface)
    private val pcm = ByteBuffer.allocateDirect(2048)
    private var pcmSize = 0
    private var pcmTime = 0L
    private var lastPcmEnd = 0L
    private val info = MediaCodec.BufferInfo()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    private var videoSamples = 0
    private var lastVideoTime = -1L
    private var lastAudioTime = -1L
    private var startUs = 0L
    private var pauseStarted = 0L
    private val pauses = mutableListOf<LongRange>()

    @SuppressLint("MissingPermission")
    fun start(profile: HardwareVideo.Profile, withMicrophone: Boolean) {
        MediaCodec.createByCodecName(profile.name).also { codec ->
            video = codec
            codec.configure(profile.format(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
        }
        startUs = nowUs()
        if (!withMicrophone) return
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Thiết bị không hỗ trợ cấu hình micro." }
        microphone = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, pcm.capacity() * 2)
        )
        check(microphone?.state == AudioRecord.STATE_INITIALIZED) { "Không mở được micro." }
        val audioName = checkNotNull(HardwareVideo.audioEncoder()) { "Thiết bị không có encoder AAC phần cứng cho micro." }
        MediaCodec.createByCodecName(audioName).also { codec ->
            audio = codec
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcm.capacity())
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }
        microphone?.startRecording()
        check(microphone?.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Không bắt đầu được micro." }
    }

    fun pause() {
        pauseStarted = nowUs()
        microphone?.stop()
        pcmSize = 0
    }

    fun resume() {
        microphone?.startRecording()
        val resumeTime = nowUs()
        pauses += pauseStarted until resumeTime
        pauseStarted = 0
        video?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    fun pump() {
        if (pauseStarted == 0L) feedAudio()
        drainVideoAndAudio()
    }

    private fun feedAudio() {
        val recorder = microphone ?: return
        val codec = audio ?: return
        if (pcmSize == 0) {
            pcm.clear()
            val read = recorder.read(pcm, pcm.capacity(), AudioRecord.READ_NON_BLOCKING)
            check(read >= 0) { "Không đọc được micro." }
            if (read == 0) return
            val duration = read.toLong() * 1_000_000 / (SAMPLE_RATE * 2)
            pcmTime = maxOf(startUs, lastPcmEnd, nowUs() - duration)
            lastPcmEnd = pcmTime + duration
            pcmSize = read
        }
        val index = codec.dequeueInputBuffer(0)
        if (index >= 0) {
            pcm.position(0)
            pcm.limit(pcmSize)
            checkNotNull(codec.getInputBuffer(index)).apply { clear(); put(pcm) }
            codec.queueInputBuffer(index, 0, pcmSize, pcmTime, 0)
            pcmSize = 0
        }
    }

    private var videoEnded = false
    private var audioEnded = false

    private fun drainVideoAndAudio() {
        val videoCodec = checkNotNull(video)
        val audioCodec = audio
        if (!videoEnded && (videoTrack == -1 || muxing || audioCodec == null || audioEnded)) {
            videoEnded = drain(videoCodec, true)
        }
        if (audioCodec != null && !audioEnded) audioEnded = drain(audioCodec, false)
    }

    private fun drain(codec: MediaCodec, isVideo: Boolean): Boolean {
        repeat(16) {
            when (val index = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val track = muxer.addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = track else audioTrack = track
                    if (videoTrack >= 0 && (audioTrack >= 0 || audio == null)) {
                        muxer.start()
                        muxing = true
                    }
                    if (!muxing && isVideo) return false
                }
                else -> if (index >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    try {
                        if (muxing && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            writeSample(codec, index, isVideo)
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (eos) return true
                }
            }
        }
        return false
    }

    private fun writeSample(codec: MediaCodec, index: Int, isVideo: Boolean) {
        val raw = info.presentationTimeUs
        if (raw < startUs && !isVideo) return
        if ((pauseStarted != 0L && raw >= pauseStarted) || pauses.any { raw in it }) return
        if (isVideo && videoSamples == 0 && info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return
        val pausedUs = pauses.sumOf { if (raw > it.last) it.last - it.first + 1 else 0L }
        val last = if (isVideo) lastVideoTime else lastAudioTime
        info.presentationTimeUs = maxOf(last + 1, raw - startUs - pausedUs)
        val buffer = checkNotNull(codec.getOutputBuffer(index))
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, buffer, info)
        if (isVideo) {
            lastVideoTime = info.presentationTimeUs
            videoSamples++
        } else {
            lastAudioTime = info.presentationTimeUs
        }
    }

    fun end(onDrainError: (Exception) -> Unit = {}): Boolean {
        runCatching {
            microphone?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            video?.signalEndOfInputStream()
            val deadline = nowUs() + 1_500_000
            var audioEosQueued = audio == null
            while (nowUs() < deadline && (!videoEnded || !(audio == null || audioEnded))) {
                if (!audioEosQueued) {
                    val codec = checkNotNull(audio)
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        codec.queueInputBuffer(index, 0, 0, maxOf(lastPcmEnd, nowUs()), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioEosQueued = true
                    }
                }
                drainVideoAndAudio()
                if (!videoEnded || (audio != null && !audioEnded)) Thread.sleep(5)
            }
        }.onFailure { if (it is Exception) onDrainError(it) }
        val finalized = muxing && runCatching { muxer.stop() }.onFailure {
            if (it is Exception) onDrainError(it)
        }.isSuccess
        release()
        return finalized && videoSamples > 0
    }

    fun release() {
        runCatching { microphone?.release() }
        runCatching { audio?.release() }
        runCatching { video?.release() }
        runCatching { inputSurface?.release() }
        runCatching { muxer.release() }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private fun nowUs() = System.nanoTime() / 1000
    }
}
