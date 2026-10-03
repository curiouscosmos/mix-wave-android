package org.videolan.vlc.gui.audio

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.appcompat.view.ActionMode
import kotlinx.coroutines.launch
import org.videolan.tools.HttpImageLoader
import org.videolan.vlc.R
import org.videolan.vlc.discourse.Discourse
import org.videolan.vlc.discourse.DiscourseDownloadStore
import org.videolan.vlc.gui.BaseFragment
import org.videolan.vlc.gui.MainActivity
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.viewmodels.SearchViewModel

class SearchFragment : BaseFragment() {
    private val model: SearchViewModel by viewModels { SearchViewModel.Factory(requireContext()) }
    private lateinit var input: EditText
    private lateinit var results: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var downloads: DiscourseDownloadStore

    override fun getTitle() = getString(R.string.search)

    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
    override fun onDestroyActionMode(mode: ActionMode) = Unit

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.search_fragment, container, false)

    override fun onResume() {
        super.onResume()
        results.adapter?.notifyDataSetChanged()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        input = view.findViewById(R.id.search_input)
        results = view.findViewById(R.id.search_results)
        progress = view.findViewById(R.id.search_progress)
        message = view.findViewById(R.id.search_message)
        downloads = DiscourseDownloadStore(requireContext())
        results.layoutManager = LinearLayoutManager(requireContext())
        results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) model.loadMore()
            }
        })
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                model.search(input.text.toString())
                true
            } else false
        }
        model.state.observe(viewLifecycleOwner, ::render)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    results.adapter?.notifyDataSetChanged()
                    delay(500)
                }
            }
        }
    }

    private fun render(state: SearchViewModel.State) {
        progress.isVisible = state is SearchViewModel.State.Loading
        message.isVisible = state !is SearchViewModel.State.Results && state !is SearchViewModel.State.Loading
        results.isVisible = state is SearchViewModel.State.Results
        when (state) {
            SearchViewModel.State.Idle -> message.text = getString(R.string.discourse_search_prompt)
            SearchViewModel.State.Empty -> message.text = getString(R.string.discourse_empty)
            SearchViewModel.State.Error -> message.text = getString(R.string.discourse_search_failed)
            SearchViewModel.State.Loading -> Unit
            is SearchViewModel.State.Results -> {
                (results.adapter as? SearchAdapter)?.update(state.discourses)
                    ?: run { results.adapter = SearchAdapter(state.discourses, ::openDiscourse) }
            }
        }
    }

    private fun openDiscourse(discourse: Discourse) {
        (activity as? MainActivity)?.openDiscourse(discourse)
    }

    private inner class SearchAdapter(
        private var items: List<Discourse>,
        private val click: (Discourse) -> Unit
    ) : RecyclerView.Adapter<SearchAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.discourse_image)
            val title: TextView = view.findViewById(R.id.discourse_title)
            val language: TextView = view.findViewById(R.id.discourse_language)
            val counts: TextView = view.findViewById(R.id.discourse_counts)
            val downloaded: ProgressBar = view.findViewById(R.id.discourse_download_progress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            layoutInflater.inflate(R.layout.discourse_card, parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.language.text = item.language.replaceFirstChar(Char::uppercase)
            holder.counts.text = getString(R.string.discourse_counts, item.totalTracks, item.totalLikes)
            val completed = downloads.downloadedCount(item.id)
            val active = downloads.activeDownloadCount(item.id)
            holder.downloaded.isVisible = active > 0 && item.totalTracks > 0
            holder.downloaded.progress = (completed * 100 / item.totalTracks.coerceAtLeast(1)).coerceAtMost(100)
            holder.itemView.contentDescription = listOf(item.title, holder.language.text, holder.counts.text)
                .filter(CharSequence::isNotBlank).joinToString(". ")
            holder.itemView.setOnClickListener { click(item) }
            loadImage(holder.image, item.thumbnailUrl)
        }

        override fun getItemCount() = items.size

        fun update(value: List<Discourse>) {
            items = value
            notifyDataSetChanged()
        }
    }

    private fun loadImage(image: ImageView, url: String?) {
        image.setImageDrawable(UiTools.getDefaultAudioDrawable(requireContext()))
        val imageUrl = org.videolan.vlc.discourse.resolveDiscourseUrl(url)
        image.tag = imageUrl
        if (imageUrl.isNullOrBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = HttpImageLoader.downloadBitmap(imageUrl)
            if (image.tag == imageUrl && bitmap != null) image.setImageBitmap(bitmap)
        }
    }
}
