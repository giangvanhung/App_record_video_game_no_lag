package vn.gamerec.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import vn.gamerec.recording.Phase
import vn.gamerec.service.RecordingService
import vn.gamerec.storage.Video

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: GameRecViewModel by viewModels()
    private val projectionManager by lazy { requireNotNull(getSystemService(MediaProjectionManager::class.java)) }
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            model.tell("Bạn chưa cấp quyền chia sẻ màn hình.")
            return@registerForActivityResult
        }
        try {
            val gameLandscape = model.settings.value.landscape ||
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            startForegroundService(Intent(this, RecordingService::class.java).setAction(RecordingService.START)
                .putExtra(RecordingService.CONSENT, data).putExtra(RecordingService.LANDSCAPE, gameLandscape))
        } catch (failure: Exception) {
            model.tell("Không khởi động được dịch vụ ghi màn hình.")
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { values ->
        when {
            values[Manifest.permission.WRITE_EXTERNAL_STORAGE] == false -> model.tell("Cần quyền lưu trữ để lưu video.")
            values[Manifest.permission.RECORD_AUDIO] == false -> model.tell("Hãy cấp quyền micro hoặc tắt Ghi micro.")
            else -> requestProjection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GameRecScreen(model, ::requestStart,
                pause = { command(RecordingService.PAUSE) },
                stop = { command(RecordingService.STOP) },
                overlay = ::requestOverlay,
                play = { openVideo(it, false) },
                share = { openVideo(it, true) })
        }
    }

    private fun requestStart() {
        if (model.recording.value.phase != Phase.IDLE) return
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            model.tell("Bộ nhớ dùng chung hiện không khả dụng.")
            return
        }
        val needed = buildList {
            if (Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (model.settings.value.microphone && !granted(Manifest.permission.RECORD_AUDIO)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) &&
                !getPreferences(MODE_PRIVATE).getBoolean("asked_notification", false)
            ) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("asked_notification", true).apply()
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) requestProjection() else permissions.launch(needed.toTypedArray())
    }

    private fun requestProjection() {
        runCatching { consent.launch(projectionManager.createScreenCaptureIntent()) }
            .onFailure { model.tell("Thiết bị không mở được hộp thoại chia sẻ màn hình.") }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun command(action: String) {
        runCatching { startService(Intent(this, RecordingService::class.java).setAction(action)) }
            .onFailure { model.tell("Không gửi được lệnh cho dịch vụ ghi.") }
    }

    private fun requestOverlay() {
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }.onFailure { model.tell("Không mở được trang quyền cửa sổ nổi.") }
    }

    private fun openVideo(video: Video, share: Boolean) {
        val intent = if (share) {
            Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, video.uri)
                .apply { clipData = android.content.ClipData.newRawUri(video.name, video.uri) }
        } else {
            Intent(this, PlayerActivity::class.java).setData(video.uri)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(if (share) Intent.createChooser(intent, "Chia sẻ video") else intent)
        } catch (failure: ActivityNotFoundException) {
            Toast.makeText(this, "Không có ứng dụng để mở video.", Toast.LENGTH_SHORT).show()
        } catch (failure: SecurityException) {
            model.tell("Không thể truy cập video này.")
        }
    }
}
