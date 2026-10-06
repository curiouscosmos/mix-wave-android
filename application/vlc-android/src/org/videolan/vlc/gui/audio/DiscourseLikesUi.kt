package org.videolan.vlc.gui.audio

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.videolan.vlc.R
import org.videolan.vlc.discourse.DiscourseLikesStore
import org.videolan.vlc.discourse.DiscourseRepository
import org.videolan.vlc.discourse.formatDiscourseLikes
import java.util.WeakHashMap

/** One binding path for catalogue cards, home rows and the detail header. */
internal class DiscourseLikesUi(private val context: Context, private val owner: LifecycleOwner) {
    private val repository = DiscourseRepository(context)
    private data class Binding(val target: DiscourseLikesStore.Target, val title: String, val count: Int)
    private val bindings = WeakHashMap<TextView, Binding>()

    init {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    try {
                        repository.refreshLikes()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // A failed likes refresh must not block catalogue loading or erase saved likes.
                    }
                }
                repository.likes.collect { state ->
                    bindings.entries.toList().forEach { (view, binding) -> render(view, binding, state) }
                }
            }
        }
    }

    fun bind(root: View, id: String, audio: Boolean, title: String, count: Int = 0) {
        val view = root.findViewById<TextView>(R.id.discourse_like)
        val binding = Binding(DiscourseLikesStore.Target(id, audio), title, count)
        bindings[view] = binding
        view.isVisible = true
        render(view, binding, repository.likes.value)
        view.setOnClickListener {
            owner.lifecycleScope.launch {
                try {
                    if (audio) repository.toggleDiscourseAudioLike(id, count)
                    else repository.toggleDiscourseLike(id, count)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.discourse_like_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun unbind(root: View) {
        root.findViewById<TextView>(R.id.discourse_like)?.let {
            bindings.remove(it)
            it.setOnClickListener(null)
            it.isVisible = false
        }
    }

    private fun render(view: TextView, binding: Binding, state: DiscourseLikesStore.State) {
        val liked = state.liked(binding.target)
        val count = (state.counts[binding.target] ?: binding.count).coerceAtLeast(0)
        val color = Color.parseColor(if (liked) "#FF5269" else "#BEA8AA")
        val drawable = AppCompatResources.getDrawable(context,
            if (liked) R.drawable.ic_header_media_favorite else R.drawable.ic_header_media_favorite_outline)!!.mutate()
        DrawableCompat.setTint(drawable, color)
        val size = (24 * context.resources.displayMetrics.density).toInt()
        drawable.setBounds(0, 0, size, size)
        view.setCompoundDrawablesRelative(drawable, null, null, null)
        view.text = formatDiscourseLikes(count)
        view.setTextColor(color)
        view.isSelected = liked
        view.isEnabled = binding.target !in state.pending
        view.contentDescription = context.getString(
            if (liked) R.string.discourse_unlike_description else R.string.discourse_like_description,
            binding.title, count
        )
    }
}
