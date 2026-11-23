package vn.gamerec.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import vn.gamerec.recording.Phase
import vn.gamerec.recording.RecordingState
import vn.gamerec.recording.formatTime
import vn.gamerec.settings.BubblePosition

internal class RecordingBubble(
    private val context: Context,
    private val pause: () -> Unit,
    private val stop: () -> Unit
) {
    private val windows = requireNotNull(context.getSystemService(WindowManager::class.java))
    private var root: LinearLayout? = null
    private var compact: TextView? = null
    private var controls: LinearLayout? = null
    private var timer: TextView? = null
    private var pauseButton: TextView? = null
    private var paused = false
    private var seconds = 0L
    private val collapse = Runnable { showControls(false) }

    fun show(state: RecordingState, position: BubblePosition) {
        if (state.phase != Phase.RECORDING && state.phase != Phase.PAUSED) {
            hide()
            return
        }
        seconds = state.seconds
        if (root == null) {
            if (!Settings.canDrawOverlays(context)) return
            val overlay = LinearLayout(context)
            val icon = label("●") {
                showControls(true)
                root?.postDelayed(collapse, 5_000)
            }.apply {
                setTextColor(0xFFFF555E.toInt())
                background = InsetDrawable(backgroundShape(0xFF222430.toInt(), 14), dp(8))
                alpha = 0.55f
                contentDescription = "Mở điều khiển ghi màn hình"
            }
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = backgroundShape(0xE8222430.toInt(), 22)
                setPadding(dp(12), 0, dp(6), 0)
                visibility = View.GONE
            }
            val clock = label("").also { row.addView(it) }
            val toggle = label("Ⅱ", pause).also { row.addView(it) }
            row.addView(label("■", stop).apply { contentDescription = "Dừng ghi" })
            overlay.addView(icon)
            overlay.addView(row)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(44),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = when (position) {
                    BubblePosition.TOP_RIGHT -> Gravity.TOP or Gravity.RIGHT
                    BubblePosition.TOP_LEFT -> Gravity.TOP or Gravity.LEFT
                    BubblePosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.RIGHT
                    BubblePosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.LEFT
                    BubblePosition.HIDDEN -> return
                }
                x = dp(12)
                y = dp(if ((gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) 12 else 54)
            }
            if (runCatching { windows.addView(overlay, params) }.isFailure) return
            root = overlay
            compact = icon
            controls = row
            timer = clock
            pauseButton = toggle
            toggle.contentDescription = "Tạm dừng ghi"
        }
        val isPaused = state.phase == Phase.PAUSED
        if (paused != isPaused) {
            paused = isPaused
            root?.removeCallbacks(collapse)
            pauseButton?.apply {
                text = if (paused) "▶" else "Ⅱ"
                contentDescription = if (paused) "Tiếp tục ghi" else "Tạm dừng ghi"
            }
            showControls(paused)
        } else if (controls?.visibility == View.VISIBLE) {
            updateTimer()
        }
    }

    private fun showControls(expanded: Boolean) {
        compact?.visibility = if (expanded) View.GONE else View.VISIBLE
        controls?.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) updateTimer()
    }

    private fun updateTimer() {
        val time = formatTime(seconds)
        timer?.let { if (it.text != time) it.text = time }
    }

    fun hide() {
        root?.let {
            it.removeCallbacks(collapse)
            runCatching { windows.removeViewImmediate(it) }
        }
        root = null
        compact = null
        controls = null
        timer = null
        pauseButton = null
        paused = false
    }

    private fun backgroundShape(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
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
