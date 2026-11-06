package org.videolan.vlc.gui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.videolan.vlc.R
import org.videolan.vlc.discourse.ClipHistoryStore
import org.videolan.vlc.discourse.clipTimestamp
import java.text.DateFormat
import java.util.Date

class ClipHistoryDialog : BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val padding = (24 * resources.displayMetrics.density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                setText(R.string.clip_history)
                textSize = 20f
                setPadding(0, 0, 0, padding)
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            })
            addView(TextView(context).apply { id = android.R.id.empty; setText(R.string.loading) })
            addView(ListView(context).apply { id = android.R.id.list })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val status = view.findViewById<TextView>(android.R.id.empty)
        val list = view.findViewById<ListView>(android.R.id.list)
        val audioId = requireArguments().getString("audioId") ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val entries = ClipHistoryStore(requireContext()).getForAudio(audioId)
                status.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                status.setText(R.string.clip_history_empty)
                val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                list.adapter = object : ArrayAdapter<org.videolan.vlc.mediadb.models.ClipHistory>(requireContext(), android.R.layout.simple_list_item_2, android.R.id.text1, entries) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        return super.getView(position, convertView, parent).apply {
                            val entry = entries[position]
                            findViewById<TextView>(android.R.id.text1).text = getString(R.string.clip_range, clipTimestamp(entry.startMs), clipTimestamp(entry.endMs))
                            findViewById<TextView>(android.R.id.text2).text = dateFormat.format(Date(entry.createdAt))
                        }
                    }
                }
                list.setOnItemClickListener { _, _, position, _ ->
                    val entry = entries[position]
                    parentFragmentManager.setFragmentResult(RESULT, bundleOf("audioId" to audioId, "start" to entry.startMs, "end" to entry.endMs))
                    dismiss()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status.setText(R.string.clip_history_load_failed)
            }
        }
    }

    companion object {
        const val RESULT = "clip_history_selection"
        fun newInstance(audioId: String) = ClipHistoryDialog().apply { arguments = bundleOf("audioId" to audioId) }
    }
}
