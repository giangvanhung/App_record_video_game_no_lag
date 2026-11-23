package vn.gamerec.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.gamerec.recording.DeviceStatus
import vn.gamerec.recording.Phase
import vn.gamerec.recording.RecordingState
import vn.gamerec.recording.formatTime
import vn.gamerec.settings.BubblePosition
import vn.gamerec.settings.Quality
import vn.gamerec.settings.RecordingSettings
import vn.gamerec.settings.SaveFolder
import vn.gamerec.settings.VideoCodec
import vn.gamerec.storage.Video
import java.text.DateFormat
import java.util.Date

private val colors = darkColorScheme(
    primary = Color(0xFFFF928E), onPrimary = Color(0xFF4A1216),
    secondaryContainer = Color(0xFF3D2931), onSecondaryContainer = Color(0xFFFFDADB),
    background = Color(0xFF111219), surface = Color(0xFF171922),
    surfaceVariant = Color(0xFF252836), onSurfaceVariant = Color(0xFFCCC5D2)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameRecScreen(
    model: GameRecViewModel, start: () -> Unit, pause: () -> Unit, stop: () -> Unit,
    overlay: () -> Unit, play: (Video) -> Unit, share: (Video) -> Unit
) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val recording by model.recording.collectAsStateWithLifecycle()
    val device by model.device.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val hevc by model.hevc.collectAsStateWithLifecycle()
    val microphoneSupported by model.microphoneSupported.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            model.tell(null)
        }
    }

    MaterialTheme(colorScheme = colors) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                Text("GameRec", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(20.dp))
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf("Ghi màn hình", "Thư viện", "Cài đặt").forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                    }
                }
                val enabled = recording.phase == Phase.IDLE
                when (tab) {
                    0 -> RecordingPage(settings, recording, device, microphoneSupported, model::update, start, pause, stop)
                    1 -> LibraryPage(model, play, share)
                    2 -> SettingsPage(settings, hevc, enabled, model::update, overlay)
                }
            }
        }
    }
}

@Composable
private fun RecordingPage(
    settings: RecordingSettings, recording: RecordingState, status: DeviceStatus?, microphoneSupported: Boolean?,
    update: ((RecordingSettings) -> RecordingSettings) -> Unit,
    start: () -> Unit, pause: () -> Unit, stop: () -> Unit
) {
    val active = recording.phase != Phase.IDLE
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tình trạng thiết bị", style = MaterialTheme.typography.titleMedium)
                Text("Pin: ${status?.battery?.let { "$it%" } ?: "—"}")
                Text("Nhiệt độ pin ước lượng: ${status?.temperature?.let { "$it °C" } ?: "—"}")
                Text("Dung lượng trống: ${status?.freeBytes?.let { displayBytes(it) } ?: "—"}")
            }
        }
        if (!active) {
            Button(onClick = start, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                Text("Bắt đầu ghi", style = MaterialTheme.typography.titleLarge)
            }
        } else {
            val label = when (recording.phase) {
                Phase.STARTING -> "Đang chuẩn bị"
                Phase.PAUSED -> "Đã tạm dừng"
                Phase.STOPPING -> "Đang lưu video"
                else -> "Đang ghi"
            }
            Text("$label · ${formatTime(recording.seconds)}", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canPause = recording.phase == Phase.RECORDING || recording.phase == Phase.PAUSED
                OutlinedButton(onClick = pause, enabled = canPause) {
                    Text(if (recording.phase == Phase.PAUSED) "Tiếp tục" else "Tạm dừng")
                }
                Button(onClick = stop, enabled = recording.phase != Phase.STOPPING) { Text("Dừng ghi") }
            }
        }
        recording.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Choice("Chất lượng ghi", settings.quality, Quality.entries, !active, { it.label }) {
            update { config -> config.copy(quality = it) }
        }
        SettingSwitch("Ghi micro", settings.microphone, !active && (settings.microphone || microphoneSupported == true)) {
            update { config -> config.copy(microphone = it) }
        }
        if (microphoneSupported == false) {
            Text("Ghi micro cần encoder AAC phần cứng.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsPage(
    settings: RecordingSettings, hevc: Boolean, enabled: Boolean,
    update: ((RecordingSettings) -> RecordingSettings) -> Unit, overlay: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Choice("Codec", settings.codec, if (hevc) VideoCodec.entries else listOf(VideoCodec.AVC), enabled, { it.label }) {
            update { config -> config.copy(codec = it) }
        }
        if (!hevc) Text("H.265 cần encoder phần cứng tương thích.", style = MaterialTheme.typography.bodySmall)
        Choice("Giới hạn thời lượng", settings.limitMinutes, listOf(5, 10, 30, 0), enabled,
            { if (it == 0) "Không giới hạn" else "$it phút" }) {
            update { config -> config.copy(limitMinutes = it) }
        }
        Choice("Thư mục lưu", settings.folder, SaveFolder.entries, enabled, { it.relativePath }) {
            update { config -> config.copy(folder = it) }
        }
        SettingSwitch("Tự giảm chất lượng khi máy nóng hoặc pin yếu", settings.autoReduce, enabled) {
            update { config -> config.copy(autoReduce = it) }
        }
        SettingSwitch("Khung hình ngang cho game", settings.landscape, enabled) {
            update { config -> config.copy(landscape = it) }
        }
        Choice("Vị trí chấm quay", settings.bubblePosition, BubblePosition.entries, enabled, { it.label }) {
            update { config -> config.copy(bubblePosition = it) }
        }
        if (settings.bubblePosition == BubblePosition.HIDDEN) {
            Text(
                "Tạm dừng hoặc dừng quay trong GameRec hoặc từ thông báo nếu đã cấp quyền.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            OutlinedButton(onClick = overlay) { Text("Cài đặt quyền cửa sổ nổi") }
        }
    }
}

@Composable
private fun <T> Choice(label: String, selected: T, options: List<T>, enabled: Boolean, name: (T) -> String, select: (T) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { opened = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(name(selected))
            }
            DropdownMenu(expanded = opened && enabled, onDismissRequest = { opened = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(name(option)) }, onClick = {
                        opened = false
                        select(option)
                    })
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = change, enabled = enabled)
    }
}

@Composable
private fun LibraryPage(model: GameRecViewModel, play: (Video) -> Unit, share: (Video) -> Unit) {
    val videos by model.videos.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Video?>(null) }
    var deleting by remember { mutableStateOf<Video?>(null) }
    if (videos.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Chưa có video đã ghi.") }
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(videos, key = { it.uri.toString() }) { video ->
                VideoCard(video, model, { play(video) }, { share(video) }, { editing = video }, { deleting = video })
            }
        }
    }
    editing?.let { video ->
        var name by remember(video.uri) { mutableStateOf(video.name.removeSuffix(".mp4")) }
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text("Đổi tên video") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Tên video") }) },
            confirmButton = { TextButton(onClick = { model.rename(video, name); editing = null }, enabled = name.isNotBlank()) { Text("Lưu") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Hủy") } })
    }
    deleting?.let { video ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("Xóa video?") }, text = { Text(video.name) },
            confirmButton = { TextButton(onClick = { model.delete(video); deleting = null }) { Text("Xóa") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Hủy") } })
    }
}

@Composable
private fun VideoCard(video: Video, model: GameRecViewModel, play: () -> Unit, share: () -> Unit, rename: () -> Unit, delete: () -> Unit) {
    val thumbnail by produceState<Bitmap?>(null, video.uri) { value = model.thumbnail(video) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(88.dp, 58.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = thumbnail
                    if (bitmap != null) {
                        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text("MP4", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(video.name, style = MaterialTheme.typography.titleSmall)
                    val date = remember(video.created) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, java.util.Locale.forLanguageTag("vi-VN")).format(Date(video.created)) }
                    Text("${formatTime(video.duration / 1000)} · ${displayBytes(video.bytes)}", style = MaterialTheme.typography.bodySmall)
                    Text(date, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = play) { Text("Phát") }
                TextButton(onClick = share) { Text("Chia sẻ") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = rename) { Text("Đổi tên") }
                TextButton(onClick = delete) { Text("Xóa") }
            }
        }
    }
}

@Composable
private fun displayBytes(bytes: Long): String = android.text.format.Formatter.formatFileSize(LocalContext.current, bytes)
