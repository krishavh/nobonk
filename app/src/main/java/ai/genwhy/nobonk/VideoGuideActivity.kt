package ai.genwhy.nobonk

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Optional bundled guide. No network access, camera use, or automatic playback. */
class VideoGuideActivity : Activity() {
    internal lateinit var guideVideo: VideoView
    private var position = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        position = savedInstanceState?.getInt("position") ?: 0
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 43, 34))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left + 16, bars.top, bars.right + 16, bars.bottom)
            insets
        }
        root.addView(Button(this).apply {
            text = "Back to NoBonk"
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "NoBonk video guide · 3:24\nScanning is stopped. Tap Play to watch with sound."
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(16, 10, 16, 16)
        })
        val frame = FrameLayout(this)
        guideVideo = VideoView(this).apply {
            contentDescription = "NoBonk step-by-step video guide"
            setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.nobonk_guide}"))
            setMediaController(MediaController(this@VideoGuideActivity).also { it.setAnchorView(this) })
            setOnPreparedListener {
                seekTo(position.coerceAtLeast(1))
                // Playback starts only through the Play button or player controls.
            }
            setOnErrorListener { _, _, _ ->
                android.widget.Toast.makeText(this@VideoGuideActivity, "Could not play the guide. You can watch it on nobonk.com.", android.widget.Toast.LENGTH_LONG).show()
                true
            }
        }
        frame.addView(guideVideo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply {
            text = "Play / pause guide"
            setOnClickListener { if (guideVideo.isPlaying) guideVideo.pause() else guideVideo.start() }
        })
        root.addView(Button(this).apply {
            text = "Guide and support on nobonk.com"
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://nobonk.com/guide/"))) }
                catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(this@VideoGuideActivity, "Open nobonk.com/guide/ in your browser.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        })
        setContentView(root)
    }

    override fun onPause() {
        position = guideVideo.currentPosition
        guideVideo.pause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("position", guideVideo.currentPosition)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        guideVideo.stopPlayback()
        super.onDestroy()
    }
}
