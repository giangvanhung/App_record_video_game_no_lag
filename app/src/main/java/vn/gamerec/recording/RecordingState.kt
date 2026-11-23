package vn.gamerec.recording

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Phase { IDLE, STARTING, RECORDING, PAUSED, STOPPING }

data class RecordingState(val phase: Phase = Phase.IDLE, val seconds: Long = 0, val message: String? = null)

@Singleton
class RecordingSession @Inject constructor() {
    private val mutable = MutableStateFlow(RecordingState())
    val state = mutable.asStateFlow()

    fun publish(value: RecordingState) {
        mutable.value = value
    }
}

fun formatTime(seconds: Long): String = java.lang.String.format(
    java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60
)
