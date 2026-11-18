package org.videolan.vlc.gui.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import org.videolan.vlc.R
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.discourse.DiscourseCategory
import org.videolan.vlc.discourse.DiscourseRepository
import org.videolan.vlc.viewmodels.DiscourseViewModel

class CategoryDiscoursesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID) ?: run { finish(); return }
        val model: DiscourseViewModel by viewModels {
            viewModelFactory {
                initializer {
                    val repository = DiscourseRepository(applicationContext)
                    DiscourseViewModel(
                        pageLoader = { page, refresh, _, _ -> repository.getDiscoursesByCategory(categoryId, page, refresh) },
                        trackLoader = { id, refresh -> repository.getDiscourseAudios(id, refresh).data }
                    )
                }
            }
        }

        setContentView(R.layout.activity_favourite_discourses)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.favourite_discourses_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        findViewById<MaterialToolbar>(R.id.favourite_discourses_toolbar).apply {
            title = intent.getStringExtra(EXTRA_CATEGORY_LABEL)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
        val status = findViewById<TextView>(R.id.favourite_discourses_empty)
        val list = findViewById<RecyclerView>(R.id.favourite_discourses_list)
        val layout = LinearLayoutManager(this)
        val adapter = CardAdapter()
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        list.layoutManager = layout
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && layout.findLastVisibleItemPosition() >= adapter.itemCount - 4) model.loadMore()
            }
        })
        model.state.observe(this) { state ->
            val discourses = (state as? DiscourseViewModel.State.Catalogue)?.discourses.orEmpty()
            adapter.submitList(discourses)
            status.isVisible = discourses.isEmpty()
            status.setOnClickListener(null)
            status.isClickable = false
            when (state) {
                DiscourseViewModel.State.Error -> {
                    status.text = getString(R.string.load_error_retry, getString(R.string.discourse_load_failed), getString(R.string.retry))
                    status.setOnClickListener { model.load() }
                }
                is DiscourseViewModel.State.Catalogue -> status.setText(R.string.discourse_empty)
                else -> status.setText(R.string.loading)
            }
        }
        model.load()
    }

    private inner class CardAdapter : ListAdapter<Discourse, CardHolder>(object : DiffUtil.ItemCallback<Discourse>() {
        override fun areItemsTheSame(oldItem: Discourse, newItem: Discourse) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Discourse, newItem: Discourse) = oldItem == newItem
    }) {
        private val likesUi = DiscourseLikesUi(this@CategoryDiscoursesActivity, this@CategoryDiscoursesActivity)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CardHolder(LayoutInflater.from(parent.context).inflate(R.layout.discourse_card_content, parent, false))

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val discourse = getItem(position)
            val card = holder.itemView
            card.findViewById<TextView>(R.id.discourse_title).text = discourse.title
            card.bindDiscourseCardMetadata(discourse, getString(R.string.discourse_track_count, discourse.totalTracks))
            lifecycleScope.loadCategoryArtwork(card.findViewById(R.id.discourse_image), discourse.thumbnailUrl)
            likesUi.bind(card, discourse.id, false, discourse.title, discourse.totalLikes, discourse = discourse)
            card.findViewById<View>(R.id.discourse_catalogue_card).setOnClickListener {
                DiscourseDetailActivity.open(this@CategoryDiscoursesActivity, discourse)
            }
        }
    }

    private class CardHolder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        private const val EXTRA_CATEGORY_ID = "category_id"
        private const val EXTRA_CATEGORY_LABEL = "category_label"

        fun open(context: Context, category: DiscourseCategory) {
            context.startActivity(Intent(context, CategoryDiscoursesActivity::class.java)
                .putExtra(EXTRA_CATEGORY_ID, category.id)
                .putExtra(EXTRA_CATEGORY_LABEL, category.label))
        }
    }
}
