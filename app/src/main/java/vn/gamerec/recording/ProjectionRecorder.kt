package vn.gamerec.recording

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import vn.gamerec.settings.Quality
import vn.gamerec.settings.RecordingSettings
import vn.gamerec.storage.VideoStore
import java.io.IOException

/** One consent token, one VirtualDisplay, and one or more independently finalized MP4 files. */
class ProjectionRecorder(
    private val projection: MediaProjection,
    private val worker: Handler,
    private val store: VideoStore,
    private val health: DeviceHealth,
    private val settings: RecordingSettings,
    private val landscape: Boolean,
    private val densityDpi: Int,
    private val state: (RecordingState) -> Unit,
    private val complete: (String) -> Unit
) {
    private var display: VirtualDisplay? = null
    private var output: VideoStore.Output? = null
    private var segment: EncoderSegment? = null
    private var quality = settings.quality
    private var pausedAt: Long? = null
    private var pausedMillis = 0L
    private var startedAt = 0L
    private var nextCheck = 0L
    private var lastPublished = -1L
    private var message: String? = null
    private var saved = 0
    private var closed = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = stop("Android đã dừng chia sẻ màn hình.")
    }
    private val tick = Runnable { guarded { step() } }

    fun start() = guarded {
        val status = health.read()
        verifyStorage(status)
        if (settings.autoReduce && status.reduceQuality && quality != Quality.STANDARD) {
            quality = Quality.STANDARD
            message = "Đã chọn 720p do pin yếu hoặc máy nóng."
        }
        projection.registerCallback(projectionCallback, worker)
        openSegment()
        startedAt = SystemClock.elapsedRealtime()
        publish()
        worker.post(tick)
    }

    private fun openSegment() {
        check(!settings.microphone || HardwareVideo.audioEncoder() != null) {
            "Thiết bị không có encoder AAC phần cứng. Hãy tắt Ghi micro để tiếp tục."
        }
        val profiles = HardwareVideo.profiles(settings.codec, quality, landscape)
        check(profiles.isNotEmpty()) { "Thiết bị không có encoder phần cứng hỗ trợ ${settings.codec.label} ở ${quality.label}." }
        val destination = store.open(settings.folder.relativePath)
        output = destination
        var selected: HardwareVideo.Profile? = null
        for (profile in profiles) {
            val candidate = EncoderSegment(MediaMuxer(destination.descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4))
            try {
                candidate.start(profile, settings.microphone)
                segment = candidate
                selected = profile
                break
            } catch (failure: Exception) {
                candidate.release()
                Log.w("GameRec", "Codec startup failed: ${profile.name}", failure)
            }
        }
        val profile = checkNotNull(selected) {
            if (settings.microphone) "Không khởi động được encoder hoặc micro." else "Không khởi động được encoder phần cứng."
        }
        val surface = checkNotNull(segment).surface
        val existing = display
        if (existing == null) {
            display = checkNotNull(projection.createVirtualDisplay(
                "GameRec", profile.width, profile.height, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )) { "Không tạo được màn hình ghi." }
        } else {
            existing.resize(profile.width, profile.height, densityDpi)
            existing.surface = surface
        }
    }

    private fun verifyStorage(status: DeviceStatus) {
        if (status.freeBytes?.let { it < DeviceHealth.MIN_FREE_BYTES } == true) {
            throw IOException("Dung lượng lưu trữ còn dưới 64 MB.")
        }
    }

    fun togglePause() = guarded {
        if (closed || startedAt == 0L) return@guarded
        val current = checkNotNull(segment)
        val virtualDisplay = checkNotNull(display)
        pausedAt?.let { since ->
            pausedMillis += SystemClock.elapsedRealtime() - since
            pausedAt = null
            current.resume()
            virtualDisplay.surface = current.surface
        } ?: run {
            virtualDisplay.surface = null
            current.pause()
            pausedAt = SystemClock.elapsedRealtime()
        }
        publish()
    }

    private fun elapsed() = if (startedAt == 0L) 0 else
        ((pausedAt ?: SystemClock.elapsedRealtime()) - startedAt - pausedMillis).coerceAtLeast(0) / 1000

    private fun step() {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        if (now >= nextCheck) {
            nextCheck = now + 2000
            val status = health.read()
            verifyStorage(status)
            if (settings.autoReduce && status.reduceQuality && quality != Quality.STANDARD && pausedAt == null) {
                display?.surface = null
                val switchAt = SystemClock.elapsedRealtime()
                finalizeSegment()
                quality = Quality.STANDARD
                message = "Đã giảm xuống 720p. Bản ghi tiếp tục trong tệp mới."
                openSegment()
                pausedMillis += SystemClock.elapsedRealtime() - switchAt
                publish()
            }
        }
        if (settings.limitMinutes > 0 && elapsed() >= settings.limitMinutes * 60L) {
            stop("Đã đạt giới hạn thời lượng.")
            return
        }
        checkNotNull(segment).pump()
        if (elapsed() != lastPublished) publish()
        worker.postDelayed(tick, if (pausedAt == null) 10 else 100)
    }

    private fun publish() {
        lastPublished = elapsed()
        state(RecordingState(if (pausedAt == null) Phase.RECORDING else Phase.PAUSED, lastPublished, message))
    }

    private fun finalizeSegment(): Boolean {
        val current = segment
        val destination = output
        segment = null
        output = null
        val keep = current?.end { Log.w("GameRec", "Codec drain failed", it) } == true
        if (destination != null) {
            store.finish(destination, keep)
            if (keep) saved++
        }
        return keep
    }

    fun stop(reason: String? = null) {
        if (closed) return
        closed = true
        worker.removeCallbacks(tick)
        state(RecordingState(Phase.STOPPING, elapsed(), reason))
        runCatching { display?.release() }
        display = null
        val completed = runCatching { finalizeSegment() }
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
        val result = when {
            completed.isFailure -> "Không hoàn tất được tệp video."
            saved > 0 -> "Đã lưu $saved video."
            else -> "Không có video hoàn chỉnh để lưu."
        }
        complete(listOfNotNull(reason, result).joinToString(" "))
    }

    private inline fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            Log.e("GameRec", "Recording failed", failure)
            val reason = when (failure) {
                is SecurityException -> "Quyền ghi màn hình hoặc micro đã bị thu hồi."
                is IOException -> failure.message?.takeIf { it.startsWith("Dung lượng") } ?: "Không ghi được tệp video."
                is IllegalStateException -> failure.message?.takeIf { it.startsWith("Không") || it.startsWith("Thiết bị") }
                    ?: "Encoder gặp lỗi. Phiên ghi đã dừng."
                else -> "Phiên ghi bị gián đoạn."
            }
            stop(reason)
        }
    }
}
