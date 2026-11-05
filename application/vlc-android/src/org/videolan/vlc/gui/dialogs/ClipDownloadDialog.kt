package org.videolan.vlc.gui.dialogs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import androidx.fragment.app.DialogFragment
import androidx.core.view.isVisible
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import org.videolan.vlc.R
import org.videolan.vlc.discourse.ClipRange
import org.videolan.vlc.discourse.ClipExportOptions
import org.videolan.vlc.discourse.ClipOutput
import org.videolan.vlc.audio.NoiseCancellationLevel
import org.json.JSONObject
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.DiscourseClipService
import org.videolan.vlc.discourse.clipTimestamp
import org.videolan.vlc.gui.helpers.UiTools

class ClipDownloadDialog : DialogFragment() {
    private lateinit var download: Button
    private lateinit var video: Button
    private var output = ClipOutput.AUDIO
    private var visualUri: String? = null
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var cancel: Button
    private lateinit var details: LinearLayout
    private var exporting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_VLC_Black)
        exporting = savedInstanceState?.getBoolean("exporting") == true
        output = ClipOutput.valueOf(savedInstanceState?.getString("output") ?: "AUDIO")
        visualUri = savedInstanceState?.getString("visual_uri")
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("exporting", exporting)
        outState.putString("output", output.name)
        outState.putString("visual_uri", visualUri)
    }
    private val visualPicker = registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>) = super.createIntent(context, input)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }) { uri ->
        if (uri != null) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                visualUri = uri.toString()
                requestExport()
            } catch (_: Exception) {
                UiTools.snacker(requireActivity(), R.string.clip_visual_failed)
            }
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Build.VERSION.SDK_INT >= 29 || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            export()
        else UiTools.snacker(requireActivity(), R.string.clip_permission)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            details = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(details)
            val args = requireArguments()
            val audio = DiscourseClipService.audioAdapter.fromJson(args.getString("audio").orEmpty())!!
            details.addView(TextView(context).apply {
                text = audio.title
                textSize = 20f
                setPadding(0, 0, 0, padding / 2)
            })
            details.addView(TextView(context).apply {
                text = "${clipTimestamp(args.getLong("start"))} — ${clipTimestamp(args.getLong("end"))}"
                setPadding(0, 0, 0, padding / 2)
            })
            val options = exportOptions()
            details.addView(TextView(context).apply {
                val level = getString(when (options.noiseLevel) {
                    NoiseCancellationLevel.OFF -> R.string.noise_cancellation_off
                    NoiseCancellationLevel.LOW -> R.string.noise_cancellation_low
                    NoiseCancellationLevel.MEDIUM -> R.string.noise_cancellation_medium
                    NoiseCancellationLevel.HIGH -> R.string.noise_cancellation_high
                })
                text = getString(R.string.noise_cancellation_state, level) + "\n" + (options.mixer?.let {
                    getString(R.string.clip_mixer_summary, it.title, it.volume,
                        getString(if (it.loop) R.string.clip_loop_on else R.string.clip_loop_off))
                } ?: getString(R.string.clip_mixer_off))
                setPadding(0, 0, 0, padding / 2)
            })
            details.addView(TextView(context).apply {
                setText(if (options.processed) R.string.clip_processed_note else R.string.clip_frame_note)
                setPadding(0, 0, 0, padding)
            })
            details.addView(TextView(context).apply {
                setText(R.string.clip_video_note)
                setPadding(0, 0, 0, padding)
            })
            status = TextView(context)
            status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(status)
            progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isVisible = false
            }
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            details.addView(MaterialButton(context).apply { setText(R.string.clip_share_in_app); isEnabled = false },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            download = MaterialButton(context).apply {
                setText(R.string.clip_download)
                setOnClickListener {
                    output = ClipOutput.AUDIO
                    visualUri = null
                    requestExport()
                }
            }
            addView(download, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            video = MaterialButton(context).apply {
                setText(R.string.clip_create_video)
                setOnClickListener {
                    output = ClipOutput.VIDEO
                    visualPicker.launch(arrayOf("image/*", "video/*"))
                }
            }
            addView(video, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            cancel = MaterialButton(context).apply {
                setText(R.string.cancel)
                setOnClickListener {
                    if (exporting && DiscourseClipService.status.value?.running == true) {
                        DiscourseClipService.cancel(context)
                        isEnabled = false
                    } else dismiss()
                }
            }
            addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return NestedScrollView(context).apply { isFillViewport = true; addView(content) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DiscourseClipService.status.collect { state ->
                    status.text = state?.let { getString(it.message) + (it.percent?.let { percent -> " $percent%" } ?: "") }.orEmpty()
                    download.isEnabled = state?.running != true
                    video.isEnabled = state?.running != true
                    val running = state?.running == true
                    if (running) exporting = true
                    isCancelable = !running
                    details.isVisible = !exporting
                    download.isVisible = !exporting
                    video.isVisible = !exporting
                    progress.isVisible = running
                    progress.isIndeterminate = state?.percent == null
                    progress.progress = state?.percent ?: 0
                    cancel.setText(if (running || !exporting) R.string.cancel else android.R.string.ok)
                    if (!running) cancel.isEnabled = true
                    if (exporting && state == null) dismiss()
                }
            }
        }
    }

    private fun requestExport() {
        val context = requireContext()
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isEmpty()) export() else permissions.launch(needed.toTypedArray())
    }

    private fun export() {
        try {
            val args = requireArguments()
            val audio = DiscourseClipService.audioAdapter.fromJson(args.getString("audio").orEmpty()) ?: return
            val started = DiscourseClipService.start(requireContext(), audio, ClipRange(args.getLong("start"), args.getLong("end")),
                exportOptions().copy(output = output, visualUri = visualUri))
            UiTools.snacker(requireActivity(), if (started) R.string.clip_started else R.string.clip_busy)
            exporting = true
        } catch (_: Exception) {
            UiTools.snacker(requireActivity(), R.string.clip_failed)
        }
    }

    private fun exportOptions() = ClipExportOptions.fromJson(requireArguments().getString("options")?.let(::JSONObject))

    companion object {
        internal fun newInstance(audio: DiscourseAudio, range: ClipRange, options: ClipExportOptions) = ClipDownloadDialog().apply {
            arguments = Bundle().apply {
                putString("audio", DiscourseClipService.audioAdapter.toJson(audio))
                putLong("start", range.startMs)
                putLong("end", range.endMs)
                putString("options", options.toJson().toString())
            }
        }
    }
}
