package vn.gamerec.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import vn.gamerec.R
import vn.gamerec.recording.DeviceHealth
import vn.gamerec.recording.Phase
import vn.gamerec.recording.ProjectionRecorder
import vn.gamerec.recording.RecordingSession
import vn.gamerec.recording.RecordingState
import vn.gamerec.settings.BubblePosition
import vn.gamerec.settings.SettingsRepository
import vn.gamerec.storage.VideoStore
import vn.gamerec.ui.MainActivity

@AndroidEntryPoint
class RecordingService : Service() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var session: RecordingSession
    @Inject lateinit var store: VideoStore
    @Inject lateinit var health: DeviceHealth

    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private val main by lazy { Handler(mainLooper) }
    private lateinit var bubble: RecordingBubble
    private var bubblePosition = BubblePosition.TOP_RIGHT
    private lateinit var notifications: NotificationManager
    private var recorder: ProjectionRecorder? = null // Accessed only on worker.
    private var started = false
    private var destroyed = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) worker.post {
                recorder?.stop("Màn hình đã khóa hoặc tắt.")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("GameRec-Encoder").apply { start() }
        worker = Handler(thread.looper)
        notifications = requireNotNull(getSystemService(NotificationManager::class.java))
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Phiên ghi màn hình", NotificationManager.IMPORTANCE_LOW))
        bubble = RecordingBubble(this, { worker.post { recorder?.togglePause() } }, { worker.post { recorder?.stop() } })
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (!started) startCapture(intent)
            STOP -> if (started) worker.post { recorder?.stop() } else stopSelf()
            PAUSE -> if (started) worker.post { recorder?.togglePause() } else stopSelf()
            else -> if (!started) stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startCapture(intent: Intent) {
        started = true
        val config = settings.state.value
        bubblePosition = config.bubblePosition
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(CONSENT, Intent::class.java)
        else intent.getParcelableExtra<Intent>(CONSENT)
        if (data == null) {
            finish("Thiếu quyền chia sẻ màn hình.")
            return
        }
        try {
            val opening = RecordingState(Phase.STARTING)
            session.publish(opening)
            val note = notification(opening)
            if (Build.VERSION.SDK_INT >= 29) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    (if (config.microphone && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
                startForeground(NOTIFICATION, note, type)
            } else {
                startForeground(NOTIFICATION, note)
            }
        } catch (failure: Exception) {
            Log.w("GameRec", "Foreground service failed", failure)
            finish("Không thể bắt đầu phiên ghi. Hãy kiểm tra quyền màn hình và micro.")
            return
        }
        val landscape = intent.getBooleanExtra(LANDSCAPE, resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        val density = resources.displayMetrics.densityDpi
        worker.post {
            val projection = runCatching {
                getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(Activity.RESULT_OK, data)
            }.onFailure { Log.w("GameRec", "Projection unavailable", it) }.getOrNull()
            if (projection == null) {
                mainExecutorCompat { finish("Không lấy được quyền chia sẻ màn hình.") }
                return@post
            }
            recorder = ProjectionRecorder(projection, worker, store, health, config, landscape, density,
                state = { value -> mainExecutorCompat { update(value) } },
                complete = { value ->
                    recorder = null
                    mainExecutorCompat { finish(value) }
                }
            )
            recorder?.start()
        }
    }

    private fun mainExecutorCompat(action: () -> Unit) {
        main.post { if (!destroyed) action() }
    }

    private fun update(value: RecordingState) {
        session.publish(value)
        if (bubblePosition != BubblePosition.HIDDEN) bubble.show(value, bubblePosition)
        val pausedChanged = value.phase != lastPhase
        if (pausedChanged) notifications.notify(NOTIFICATION, notification(value))
        lastPhase = value.phase
    }

    private var lastPhase = Phase.STARTING

    private fun finish(message: String) {
        bubble.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < 33
        ) {
            notifications.notify(RESULT_NOTIFICATION, Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle("GameRec")
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build())
        }
        session.publish(RecordingState(message = message))
        stopSelf()
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun notification(state: RecordingState): Notification {
        val title = when (state.phase) {
            Phase.STARTING -> "Đang chuẩn bị ghi"
            Phase.PAUSED -> "Đã tạm dừng"
            Phase.STOPPING -> "Đang lưu video"
            else -> "Đang ghi màn hình"
        }
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_record)
            .setContentTitle(title).setContentIntent(openApp()).setOngoing(true)
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
        if (state.phase != Phase.STOPPING) {
            builder.addAction(Notification.Action.Builder(null, "Dừng ghi", command(STOP, 1)).build())
        }
        if (state.phase == Phase.RECORDING || state.phase == Phase.PAUSED) {
            val label = if (state.phase == Phase.PAUSED) "Tiếp tục" else "Tạm dừng"
            builder.addAction(Notification.Action.Builder(null, label, command(PAUSE, 2)).build())
        }
        return builder.build()
    }

    private fun command(action: String, request: Int) = PendingIntent.getService(
        this, request, Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    override fun onDestroy() {
        destroyed = true
        unregisterReceiver(screenOff)
        bubble.hide()
        worker.post {
            recorder?.stop("Dịch vụ ghi đã dừng.")
            thread.quitSafely()
        }
        if (session.state.value.phase != Phase.IDLE) {
            session.publish(RecordingState(message = "Dịch vụ ghi đã dừng."))
        }
        super.onDestroy()
    }

    companion object {
        const val START = "vn.gamerec.START"
        const val STOP = "vn.gamerec.STOP"
        const val PAUSE = "vn.gamerec.PAUSE"
        const val CONSENT = "consent"
        const val LANDSCAPE = "landscape"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION = 1
        private const val RESULT_NOTIFICATION = 2
    }
}
