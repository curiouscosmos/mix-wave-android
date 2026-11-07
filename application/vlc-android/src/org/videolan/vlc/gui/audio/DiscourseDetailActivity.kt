package org.videolan.vlc.gui.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.os.bundleOf
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import org.videolan.resources.util.parcelable
import org.videolan.vlc.R
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.discourse.ClipRange
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.playDiscourseAudios
import org.videolan.vlc.gui.ContentActivity

class DiscourseDetailActivity : ContentActivity() {
    override val displayTitle = true
    private var clipPlaybackRequested = false
    private var autoplayConsumed = false

    fun playNotificationTrack(tracks: List<DiscourseAudio>?, failed: Boolean) {
        if (!intent.getBooleanExtra(ARG_AUTOPLAY, false) || autoplayConsumed || (tracks == null && !failed)) return
        autoplayConsumed = true
        val discourse = intent.parcelable<Discourse>(ARG_DISCOURSE)
        val audioId = intent.getStringExtra(ARG_AUDIO_ID)
        val index = tracks?.indexOfFirst { it.id == audioId && it.discourseId == discourse?.id } ?: -1
        if (failed || index < 0) {
            Toast.makeText(this, R.string.discourse_load_failed, Toast.LENGTH_LONG).show()
            return
        }
        expandAudioPlayerFor(tracks!![index].id)
        playDiscourseAudios(tracks, index, startFromBeginning = true)
    }

    fun requestLinkedClipPlayback(): Boolean {
        if (!intent.hasExtra(ARG_CLIP_START) || clipPlaybackRequested) return false
        clipPlaybackRequested = true
        return true
    }

    fun takeLinkedClip(audioId: String): ClipRange? {
        if (!clipPlaybackRequested || intent.getStringExtra(ARG_AUDIO_ID) != audioId || !intent.hasExtra(ARG_CLIP_START)) return null
        val range = ClipRange(intent.getLongExtra(ARG_CLIP_START, 0), intent.getLongExtra(ARG_CLIP_END, 0))
        intent.removeExtra(ARG_CLIP_START)
        intent.removeExtra(ARG_CLIP_END)
        return range
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        clipPlaybackRequested = savedInstanceState?.getBoolean("clip_playback_requested") ?: false
        autoplayConsumed = savedInstanceState?.getBoolean("autoplay_consumed") ?: false
        setContentView(R.layout.secondary)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        initAudioPlayerContainerActivity()
        findViewById<View>(R.id.fragment_placeholder).apply {
            (layoutParams as CoordinatorLayout.LayoutParams).behavior = AppBarLayout.ScrollingViewBehavior()
        }
        val toolbarBackground = getColor(R.color.discourse_detail_background_start)
        val toolbarForeground = getColor(R.color.discourse_catalogue_text)
        toolbar.setBackgroundColor(toolbarBackground)
        toolbar.setTitleTextColor(toolbarForeground)
        toolbar.navigationIcon?.mutate()?.setTint(toolbarForeground)
        (toolbar.layoutParams as AppBarLayout.LayoutParams).scrollFlags = 0
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""
        if (supportFragmentManager.findFragmentById(R.id.fragment_placeholder) == null) {
            val discourse = intent.parcelable<Discourse>(ARG_DISCOURSE) ?: run { finish(); return }
            supportFragmentManager.beginTransaction().add(R.id.fragment_placeholder,
                DiscourseFragment().apply {
                    arguments = bundleOf(ARG_DISCOURSE to discourse, ARG_AUDIO_ID to intent.getStringExtra(ARG_AUDIO_ID))
                }).commit()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu) = true

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("clip_playback_requested", clipPlaybackRequested)
        outState.putBoolean("autoplay_consumed", autoplayConsumed)
        super.onSaveInstanceState(outState)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val ARG_DISCOURSE = "detail_discourse"
        const val ARG_AUDIO_ID = "detail_audio_id"
        private const val ARG_CLIP_START = "detail_clip_start"
        private const val ARG_CLIP_END = "detail_clip_end"
        private const val ARG_AUTOPLAY = "detail_autoplay"
        fun open(context: Context, discourse: Discourse, audioId: String? = null, clipRange: ClipRange? = null, autoplay: Boolean = false) {
            context.startActivity(Intent(context, DiscourseDetailActivity::class.java)
                .putExtra(ARG_DISCOURSE, discourse)
                .putExtra(ARG_AUDIO_ID, audioId).apply {
                    putExtra(ARG_AUTOPLAY, autoplay)
                    clipRange?.let {
                        putExtra(ARG_CLIP_START, it.startMs)
                        putExtra(ARG_CLIP_END, it.endMs)
                    }
                })
        }
    }
}
