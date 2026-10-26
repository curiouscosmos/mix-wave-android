package org.videolan.vlc.gui.audio

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.videolan.tools.HttpImageLoader
import org.videolan.vlc.R
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.discourse.DiscourseFavouritesStore
import org.videolan.vlc.discourse.resolveDiscourseUrl
import org.videolan.vlc.gui.helpers.UiTools

/** Read-only list backed by the locally stored liked discourses. */
class FavouriteDiscoursesActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: DiscourseAdapter
    private lateinit var likesUi: DiscourseLikesUi
    private val store by lazy { DiscourseFavouritesStore.shared(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favourite_discourses)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.favourite_discourses_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(findViewById(R.id.favourite_discourses_toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.favourite_discourses)
        }
        list = findViewById(R.id.favourite_discourses_list)
        empty = findViewById(R.id.favourite_discourses_empty)
        list.layoutManager = LinearLayoutManager(this)
        adapter = DiscourseAdapter()
        list.adapter = adapter
        likesUi = DiscourseLikesUi(this, this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.state.collect { state -> adapter.submit(state.discourses.map { it.discourse }) }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadImage(image: ImageView, discourse: Discourse) {
        image.setImageDrawable(UiTools.getDefaultAudioDrawable(this))
        val imageUrl = resolveDiscourseUrl(discourse.thumbnailUrl)
        image.tag = imageUrl
        if (imageUrl.isNullOrBlank()) return
        lifecycleScope.launch {
            val bitmap = HttpImageLoader.downloadBitmap(imageUrl)
            if (image.tag == imageUrl && bitmap != null) image.setImageBitmap(bitmap)
        }
    }

    private inner class DiscourseAdapter : RecyclerView.Adapter<DiscourseAdapter.Holder>() {
        private var items: List<Discourse> = emptyList()

        fun submit(discourses: List<Discourse>) {
            items = discourses
            notifyDataSetChanged()
            empty.isVisible = discourses.isEmpty()
            list.isVisible = discourses.isNotEmpty()
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val card: View = view.findViewById(R.id.discourse_catalogue_card)
            val heading: View = view.findViewById(R.id.discourse_letter_heading)
            val image: ImageView = view.findViewById(R.id.discourse_image)
            val title: TextView = view.findViewById(R.id.discourse_title)
            val language: TextView = view.findViewById(R.id.discourse_language)
            val counts: TextView = view.findViewById(R.id.discourse_counts)
            val downloadIcon: View = view.findViewById(R.id.discourse_download_icon)
            val downloadedCount: View = view.findViewById(R.id.discourse_downloaded_count)
            val downloadProgress: ProgressBar = view.findViewById(R.id.discourse_download_progress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(layoutInflater.inflate(R.layout.discourse_catalogue_card, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.heading.isVisible = false
            holder.title.text = item.title
            holder.language.text = item.language.replaceFirstChar(Char::uppercase)
            holder.counts.text = getString(R.string.discourse_track_count, item.totalTracks)
            holder.downloadIcon.isVisible = false
            holder.downloadedCount.isVisible = false
            holder.downloadProgress.isVisible = false
            holder.card.setOnClickListener { DiscourseDetailActivity.open(this@FavouriteDiscoursesActivity, item) }
            loadImage(holder.image, item)
            likesUi.bind(holder.itemView, item.id, false, item.title, item.totalLikes, discourse = item)
        }
    }
}
