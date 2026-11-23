package vn.gamerec.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.gamerec.recording.DeviceHealth
import vn.gamerec.recording.HardwareVideo
import vn.gamerec.recording.RecordingSession
import vn.gamerec.settings.RecordingSettings
import vn.gamerec.settings.SettingsRepository
import vn.gamerec.settings.VideoCodec
import vn.gamerec.storage.Video
import vn.gamerec.storage.VideoStore
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GameRecViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    session: RecordingSession,
    health: DeviceHealth,
    private val store: VideoStore
) : ViewModel() {
    val settings = settingsRepository.state
    val recording = session.state
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()
    private val mutableHevc = MutableStateFlow(false)
    val hevc = mutableHevc.asStateFlow()
    private val mutableMicrophoneSupported = MutableStateFlow<Boolean?>(null)
    val microphoneSupported = mutableMicrophoneSupported.asStateFlow()

    val device = flow {
        while (true) {
            emit(withContext(Dispatchers.IO) { health.read() })
            delay(2500)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val videos = store.changes.mapLatest {
        withContext(Dispatchers.IO) {
            runCatching { store.list() }.getOrElse {
                tell("Không đọc được thư viện video.")
                emptyList()
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.recover() }
            mutableHevc.value = runCatching { HardwareVideo.supported(VideoCodec.HEVC) }.getOrDefault(false)
            mutableMicrophoneSupported.value = runCatching { HardwareVideo.audioEncoder() != null }.getOrDefault(false)
        }
    }

    fun update(change: (RecordingSettings) -> RecordingSettings) = settingsRepository.update(change)
    fun tell(message: String?) { mutableNotice.value = message }

    suspend fun thumbnail(video: Video): Bitmap? = withContext(Dispatchers.IO) { store.thumbnail(video) }

    fun rename(video: Video, name: String) = perform("Không đổi được tên video.") { store.rename(video, name) }
    fun delete(video: Video) = perform("Không xóa được video.") { store.delete(video) }

    private fun perform(message: String, action: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                action()
            } catch (failure: IllegalArgumentException) {
                tell(failure.message?.takeIf { it == "Tên video không hợp lệ." } ?: message)
            } catch (failure: Exception) {
                tell(message)
            }
        }
    }
}
