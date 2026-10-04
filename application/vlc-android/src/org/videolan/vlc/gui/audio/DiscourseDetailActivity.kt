package org.videolan.vlc.gui.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import org.videolan.vlc.gui.ContentActivity

class DiscourseDetailActivity : ContentActivity() {
    override val displayTitle = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
        toolbar.setBackgroundColor(android.graphics.Color.parseColor("#210C10"))
        toolbar.setTitleTextColor(android.graphics.Color.WHITE)
        (toolbar.layoutParams as AppBarLayout.LayoutParams).scrollFlags = 0
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.app_name)
        if (supportFragmentManager.findFragmentById(R.id.fragment_placeholder) == null) {
            val discourse = intent.parcelable<Discourse>(ARG_DISCOURSE) ?: run { finish(); return }
            supportFragmentManager.beginTransaction().add(R.id.fragment_placeholder,
                DiscourseFragment().apply { arguments = bundleOf(ARG_DISCOURSE to discourse) }).commit()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu) = true

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val ARG_DISCOURSE = "detail_discourse"
        fun open(context: Context, discourse: Discourse) {
            context.startActivity(Intent(context, DiscourseDetailActivity::class.java).putExtra(ARG_DISCOURSE, discourse))
        }
    }
}
