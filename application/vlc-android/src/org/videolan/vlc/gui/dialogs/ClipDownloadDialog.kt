package org.videolan.vlc.gui.dialogs

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import org.videolan.vlc.R
import org.videolan.vlc.discourse.ClipRange
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.DiscourseClipService
import org.videolan.vlc.discourse.clipTimestamp
import org.videolan.vlc.gui.helpers.UiTools

class ClipDownloadDialog : VLCBottomSheetDialogFragment() {
    private lateinit var download: Button
    private lateinit var status: TextView
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Build.VERSION.SDK_INT >= 29 || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            export()
        else UiTools.snacker(requireActivity(), R.string.clip_permission)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val padding = (24 * resources.displayMetrics.density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            val args = requireArguments()
            val audio = DiscourseClipService.audioAdapter.fromJson(args.getString("audio").orEmpty())!!
            addView(TextView(context).apply {
                text = audio.title
                textSize = 20f
                setPadding(0, 0, 0, padding / 2)
            })
            addView(TextView(context).apply {
                text = "${clipTimestamp(args.getLong("start"))} — ${clipTimestamp(args.getLong("end"))}"
                setPadding(0, 0, 0, padding / 2)
            })
            addView(TextView(context).apply { setText(R.string.clip_frame_note); setPadding(0, 0, 0, padding) })
            status = TextView(context)
            addView(status)
            addView(MaterialButton(context).apply { setText(R.string.clip_share_in_app); isEnabled = false },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            download = MaterialButton(context).apply {
                setText(R.string.clip_download)
                setOnClickListener {
                    val needed = mutableListOf<String>()
                    if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                        needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        needed.add(Manifest.permission.POST_NOTIFICATIONS)
                    if (needed.isEmpty()) export() else permissions.launch(needed.toTypedArray())
                }
            }
            addView(download, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DiscourseClipService.status.collect { state ->
                    status.text = state?.let { getString(it.message) + (it.percent?.let { percent -> " $percent%" } ?: "") }.orEmpty()
                    download.isEnabled = state?.running != true
                }
            }
        }
    }

    private fun export() {
        try {
            val args = requireArguments()
            val audio = DiscourseClipService.audioAdapter.fromJson(args.getString("audio").orEmpty()) ?: return
            val started = DiscourseClipService.start(requireContext(), audio, ClipRange(args.getLong("start"), args.getLong("end")))
            UiTools.snacker(requireActivity(), if (started) R.string.clip_started else R.string.clip_busy)
            dismiss()
        } catch (_: Exception) {
            UiTools.snacker(requireActivity(), R.string.clip_failed)
        }
    }

    override fun getDefaultState() = BottomSheetBehavior.STATE_EXPANDED
    override fun needToManageOrientation() = false
    override fun initialFocusedView(): View = download

    companion object {
        fun newInstance(audio: DiscourseAudio, range: ClipRange) = ClipDownloadDialog().apply {
            arguments = Bundle().apply {
                putString("audio", DiscourseClipService.audioAdapter.toJson(audio))
                putLong("start", range.startMs)
                putLong("end", range.endMs)
            }
        }
    }
}
