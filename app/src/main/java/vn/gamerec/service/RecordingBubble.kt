package vn.gamerec.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import vn.gamerec.recording.Phase
import vn.gamerec.recording.RecordingState
import vn.gamerec.recording.formatTime

internal class RecordingBubble(
    private val context: Context,
    private val pause: () -> Unit,
    private val stop: () -> Unit
) {
    private val windows = requireNotNull(context.getSystemService(WindowManager::class.java))
    private var root: LinearLayout? = null
    private var timer: TextView? = null
    private var pauseButton: TextView? = null

    fun show(state: RecordingState) {
        if (state.phase !in listOf(Phase.RECORDING, Phase.PAUSED) || !Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        if (root == null) {
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xE8222430.toInt())
                    cornerRadius = dp(22).toFloat()
                }
                setPadding(dp(12), 0, dp(6), 0)
            }
            timer = label(formatTime(state.seconds)).also { row.addView(it) }
            pauseButton = label("Ⅱ", pause).also { row.addView(it) }
            row.addView(label("■", stop).apply { contentDescription = "Dừng ghi" })
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(44),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(12)
                y = dp(54)
            }
            if (runCatching { windows.addView(row, params) }.isFailure) return
            root = row
        }
        val paused = state.phase == Phase.PAUSED
        timer?.text = formatTime(state.seconds)
        pauseButton?.apply {
            text = if (paused) "▶" else "Ⅱ"
            contentDescription = if (paused) "Tiếp tục ghi" else "Tạm dừng ghi"
        }
    }

    fun hide() {
        root?.let { runCatching { windows.removeViewImmediate(it) } }
        root = null
        timer = null
        pauseButton = null
    }

    private fun label(value: String, action: (() -> Unit)? = null) = TextView(context).apply {
        text = value
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        if (action != null) {
            minimumWidth = dp(44)
            minimumHeight = dp(44)
            setOnClickListener { action() }
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
