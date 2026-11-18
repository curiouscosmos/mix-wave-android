package org.videolan.vlc.gui.audio

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.videolan.tools.HttpImageLoader
import org.videolan.vlc.R
import org.videolan.vlc.discourse.DiscourseRepository
import org.videolan.vlc.discourse.resolveDiscourseUrl
import org.videolan.vlc.gui.ContentActivity
import org.videolan.vlc.gui.helpers.UiTools

class CategoriesFragment : Fragment(R.layout.categories_fragment) {
    override fun onStart() {
        super.onStart()
        (activity as? ContentActivity)?.setTabLayoutVisibility(false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<LinearLayout>(R.id.categories_list)
        val status = view.findViewById<TextView>(R.id.categories_status)
        val progress = view.findViewById<View>(R.id.categories_progress)
        val repository = DiscourseRepository(requireContext())
        val scope = viewLifecycleOwner.lifecycleScope

        fun loadCategories() {
            status.setOnClickListener(null)
            status.isClickable = false
            status.isVisible = false
            progress.isVisible = true
            scope.launch {
                val categories = try {
                    repository.getDiscourseCategories().data
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    status.text = getString(R.string.load_error_retry, getString(R.string.categories_load_failed), getString(R.string.retry))
                    status.isVisible = true
                    status.setOnClickListener { loadCategories() }
                    return@launch
                } finally {
                    progress.isVisible = false
                }
                status.isVisible = categories.isEmpty()
                status.setText(R.string.categories_empty)
                for (categoriesInRow in categories.chunked(2)) {
                    val row = LinearLayout(list.context)
                    for (category in categoriesInRow) {
                        val card = layoutInflater.inflate(R.layout.discourse_category_card, row, false)
                        card.clipToOutline = true
                        card.findViewById<TextView>(R.id.category_label).text = category.label
                        scope.loadCategoryArtwork(card.findViewById(R.id.category_image), category.thumbnailUrl)
                        card.setOnClickListener {
                            CategoryDiscoursesActivity.open(requireContext(), category)
                        }
                        row.addView(card)
                    }
                    if (row.childCount == 1) {
                        row.addView(Space(row.context), LinearLayout.LayoutParams(
                            row.getChildAt(0).layoutParams as LinearLayout.LayoutParams
                        ))
                    }
                    list.addView(row, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                }
            }
        }

        loadCategories()
    }
}

internal fun CoroutineScope.loadCategoryArtwork(image: ImageView, thumbnail: String?) {
    image.setImageDrawable(UiTools.getDefaultAudioDrawable(image.context))
    val url = resolveDiscourseUrl(thumbnail)
    image.tag = url
    if (url == null) return
    launch {
        val bitmap = HttpImageLoader.downloadBitmap(url)
        if (image.tag == url && bitmap != null) image.setImageBitmap(bitmap)
    }
}
