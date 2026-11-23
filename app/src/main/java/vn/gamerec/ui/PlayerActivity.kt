package vn.gamerec.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class PlayerActivity : ComponentActivity() {
    private lateinit var video: VideoView
    private var position = 0
    private var shouldPlay = true
    private var prepared = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        position = savedInstanceState?.getInt("position") ?: 0
        shouldPlay = savedInstanceState?.getBoolean("playing") ?: true
        val container = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        video = VideoView(this)
        container.addView(video, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(container)
        val controller = MediaController(this)
        video.setMediaController(controller)
        video.setOnPreparedListener {
            prepared = true
            if (position > 0) video.seekTo(position)
            if (shouldPlay) video.start()
            controller.show(2500)
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Không phát được video trên thiết bị này.", Toast.LENGTH_LONG).show()
            finish()
            true
        }
        video.setVideoURI(uri)
    }

    override fun onResume() {
        super.onResume()
        if (prepared && shouldPlay && ::video.isInitialized) video.start()
    }

    override fun onPause() {
        if (prepared && ::video.isInitialized) {
            position = video.currentPosition
            shouldPlay = video.isPlaying
            video.pause()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("position", if (prepared && ::video.isInitialized) maxOf(position, video.currentPosition) else position)
        outState.putBoolean("playing", if (prepared && ::video.isInitialized && video.isPlaying) true else shouldPlay)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::video.isInitialized) video.stopPlayback()
        super.onDestroy()
    }
}
