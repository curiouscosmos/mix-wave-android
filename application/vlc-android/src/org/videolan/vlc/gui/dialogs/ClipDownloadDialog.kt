package org.videolan.vlc.gui.dialogs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.videolan.tools.HttpImageLoader
import org.videolan.vlc.R
import org.videolan.vlc.audio.NoiseCancellationLevel
import org.videolan.vlc.databinding.DialogClipExportBinding
import org.videolan.vlc.discourse.ClipExportOptions
import org.videolan.vlc.discourse.ClipExportFolder
import org.videolan.vlc.discourse.ClipExportStatus
import org.videolan.vlc.discourse.ClipOutput
import org.videolan.vlc.discourse.ClipVideoRatio
import org.videolan.vlc.discourse.ClipRange
import org.videolan.vlc.discourse.DiscourseAudio
import org.videolan.vlc.discourse.DiscourseClipService
import org.videolan.vlc.discourse.clipTimestamp
import org.videolan.vlc.discourse.resolveDiscourseUrl
import org.videolan.vlc.discourse.webUrl
import org.videolan.vlc.gui.helpers.UiTools

class ClipDownloadDialog : VLCBottomSheetDialogFragment() {
    private lateinit var binding: DialogClipExportBinding
    private lateinit var audio: DiscourseAudio
    private var output = ClipOutput.AUDIO
    private var visualUri: String? = null
    private var videoRatio = ClipVideoRatio.ORIGINAL
    private var exporting = false
    private var exportCaptions = false
    private var fadeIn = false
    private var fadeOut = false
    private var exportFolderUri: String? = null
    private var exportFolderName: String? = null
    private var sheetBehavior: BottomSheetBehavior<FrameLayout>? = null
    private var lastPercent: Int? = null

    override fun getDefaultState() = STATE_EXPANDED

    override fun needToManageOrientation() = false

    override fun initialFocusedView(): View = binding.clipExportClose

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exporting = savedInstanceState?.getBoolean("exporting") == true
        exportCaptions = savedInstanceState?.getBoolean("captions") == true
        fadeIn = savedInstanceState?.getBoolean("fade_in") == true
        fadeOut = savedInstanceState?.getBoolean("fade_out") == true
        exportFolderUri = if (savedInstanceState != null) savedInstanceState.getString("export_folder_uri") else ClipExportFolder.uri(requireContext())
        exportFolderName = if (savedInstanceState != null) savedInstanceState.getString("export_folder_name") else ClipExportFolder.name(requireContext())
        output = ClipOutput.valueOf(savedInstanceState?.getString("output") ?: "AUDIO")
        visualUri = savedInstanceState?.getString("visual_uri")
        videoRatio = ClipVideoRatio.valueOf(savedInstanceState?.getString("video_ratio") ?: "ORIGINAL")
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.setBackgroundColor(Color.TRANSPARENT)
        sheetBehavior = sheet?.let { BottomSheetBehavior.from(it) }
        renderStatus(DiscourseClipService.status.value)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("exporting", exporting)
        outState.putBoolean("captions", exportCaptions)
        outState.putBoolean("fade_in", fadeIn)
        outState.putBoolean("fade_out", fadeOut)
        outState.putString("export_folder_uri", exportFolderUri)
        outState.putString("export_folder_name", exportFolderName)
        outState.putString("output", output.name)
        outState.putString("visual_uri", visualUri)
        outState.putString("video_ratio", videoRatio.name)
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                try {
                    val name = withContext(Dispatchers.IO) {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        val directory = ClipExportFolder.directory(context, uri.toString())
                        val name = directory.name ?: uri.lastPathSegment.orEmpty()
                        ClipExportFolder.save(context, uri.toString(), name)
                        name
                    }
                    exportFolderUri = uri.toString()
                    exportFolderName = name
                    if (view != null) renderExportFolder()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (isAdded) UiTools.snacker(requireActivity(), R.string.clip_export_folder_unavailable)
                }
            }
        }
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
        if (exportFolderUri != null || Build.VERSION.SDK_INT >= 29 || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            export()
        else UiTools.snacker(requireActivity(), R.string.clip_permission)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DialogClipExportBinding.inflate(inflater, container, false)
        val args = requireArguments()
        audio = DiscourseClipService.audioAdapter.fromJson(args.getString("audio").orEmpty())
            ?: throw IllegalArgumentException("Missing audio")
        binding.clipExportTitle.text = audio.title.ifBlank { audio.discourseName }
        binding.clipExportRange.text = getString(
            R.string.clip_range,
            clipTimestamp(args.getLong("start")),
            clipTimestamp(args.getLong("end"))
        )
        val options = exportOptions()
        binding.clipExportNoiseSwitch.isChecked = options.noiseLevel != NoiseCancellationLevel.OFF
        binding.clipExportMusicSwitch.isChecked = options.mixer != null
        binding.clipExportFadeIn.isChecked = fadeIn
        binding.clipExportFadeOut.isChecked = fadeOut
        binding.clipExportFadeIn.setOnCheckedChangeListener { _, checked -> fadeIn = checked }
        binding.clipExportFadeOut.setOnCheckedChangeListener { _, checked -> fadeOut = checked }
        renderExportFolder()
        binding.clipExportFolderChange.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.clip_export_folder)
                .setItems(arrayOf(getString(R.string.clip_export_folder_default), getString(R.string.clip_export_folder_choose))) { _, which ->
                    if (which == 0) {
                        val context = requireContext().applicationContext
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) { ClipExportFolder.save(context, null, null) }
                                exportFolderUri = null
                                exportFolderName = null
                                renderExportFolder()
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                UiTools.snacker(requireActivity(), R.string.clip_export_folder_save_failed)
                            }
                        }
                    } else folderPicker.launch(exportFolderUri?.let(android.net.Uri::parse))
                }.show()
        }
        val captionsAvailable = audio.hasCc == true && !audio.ccPath.isNullOrBlank()
        binding.clipExportCaptionsRow.isEnabled = captionsAvailable
        binding.clipExportCaptionsRow.isFocusable = captionsAvailable
        binding.clipExportCaptionsRow.isClickable = captionsAvailable
        binding.clipExportCaptionsRow.alpha = if (captionsAvailable) 1f else 0.5f
        binding.clipExportCaptionsStatus.isVisible = !captionsAvailable
        binding.clipExportCaptions.isEnabled = captionsAvailable
        binding.clipExportCaptions.isChecked = captionsAvailable && exportCaptions
        binding.clipExportCaptions.setOnCheckedChangeListener { _, checked ->
            exportCaptions = captionsAvailable && checked
        }
        binding.clipExportCaptionsRow.setOnClickListener {
            if (captionsAvailable) binding.clipExportCaptions.toggle()
        }
        binding.clipExportClose.setOnClickListener { dismiss() }
        binding.clipExportShare.setOnClickListener { saveHistoryThen { shareClip() } }
        binding.clipExportDownload.setOnClickListener {
            saveHistoryThen {
                output = ClipOutput.AUDIO
                visualUri = null
                requestExport()
            }
        }
        binding.clipExportVideo.setOnClickListener {
            saveHistoryThen {
                showVideoRatioPicker()
            }
        }
        binding.clipExportCancel.setOnClickListener {
            if (exporting && DiscourseClipService.status.value?.running == true) {
                DiscourseClipService.cancel(requireContext())
                binding.clipExportCancel.isEnabled = false
            } else dismiss()
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadArtwork()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DiscourseClipService.status.collect { renderStatus(it) }
            }
        }
    }

    private fun loadArtwork() {
        binding.clipExportArtwork.setImageDrawable(UiTools.getDefaultAudioDrawable(requireContext()))
        val url = resolveDiscourseUrl(audio.discourseThumbnailUrl) ?: return
        binding.clipExportArtwork.tag = url
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = HttpImageLoader.downloadBitmap(url)
            if (binding.clipExportArtwork.tag == url && bitmap != null) binding.clipExportArtwork.setImageBitmap(bitmap)
        }
    }

    private var savingHistory = false

    private fun saveHistoryThen(action: () -> Unit) {
        if (savingHistory) return
        val args = requireArguments()
        val entry = org.videolan.vlc.mediadb.models.ClipHistory(audio.id, args.getLong("start"), args.getLong("end"), System.currentTimeMillis())
        val history = org.videolan.vlc.discourse.ClipHistoryStore(requireContext())
        savingHistory = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                history.save(entry)
                if (isResumed) action()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                UiTools.snacker(requireActivity(), R.string.clip_history_save_failed)
            } finally {
                savingHistory = false
            }
        }
    }

    private fun shareClip() {
        val args = requireArguments()
        val range = ClipRange(args.getLong("start"), args.getLong("end"))
        val text = getString(
            R.string.clip_share_text,
            audio.title.ifBlank { audio.discourseName },
            "${audio.webUrl()}?start=${range.startMs / 1_000}&end=${range.endMs / 1_000}",
            getString(R.string.clip_range, clipTimestamp(range.startMs), clipTimestamp(range.endMs))
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, audio.title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.clip_share_link)))
    }

    private fun renderStatus(state: ClipExportStatus?) {
        val running = state?.running == true
        if (running) exporting = true
        if (running) state?.percent?.let { lastPercent = it }
        isCancelable = !running
        sheetBehavior?.isDraggable = !running
        sheetBehavior?.isHideable = !running
        binding.clipExportForm.isVisible = !exporting
        binding.clipExportProgressScreen.isVisible = exporting
        if (!exporting) return
        if (state == null) {
            dismiss()
            return
        }

        val video = output == ClipOutput.VIDEO
        val success = !running && (state.message == R.string.clip_saved || state.message == R.string.clip_saved_no_captions)
        val failure = !running && !success
        binding.clipExportProgressTitle.setText(when {
            success -> if (video) R.string.clip_created_video else R.string.clip_created_audio
            failure -> if (video) R.string.clip_create_failed_video else R.string.clip_create_failed_audio
            else -> if (video) R.string.clip_creating_video else R.string.clip_creating_audio
        })
        binding.clipExportProgressSubtitle.text = when {
            success -> if (state.message == R.string.clip_saved_no_captions) getString(state.message)
                else getString(R.string.clip_export_saved_message)
            failure -> getString(state.message)
            else -> getString(R.string.clip_export_wait)
        }
        if (video) {
            binding.clipExportProgressIcon.setImageResource(R.drawable.ic_clip_video_creation)
            binding.clipExportProgressIcon.imageTintList = null
        } else {
            binding.clipExportProgressIcon.setImageResource(R.drawable.ic_song)
            binding.clipExportProgressIcon.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.clip_export_ring_icon))
        }

        val percent = when {
            success -> 100
            failure -> null
            else -> state.percent
        }
        val indeterminate = percent == null && !failure
        binding.clipExportProgressRing.isIndeterminate = indeterminate
        binding.clipExportProgressBar.isIndeterminate = indeterminate
        binding.clipExportProgressBar.isVisible = !failure
        if (percent != null) {
            binding.clipExportProgressPercent.isVisible = true
            binding.clipExportProgressPercent.text = getString(R.string.clip_export_percent, percent)
            binding.clipExportProgressRing.setProgressCompat(percent, true)
            binding.clipExportProgressBar.setProgressCompat(percent, true)
        } else {
            binding.clipExportProgressPercent.isVisible = false
            if (failure) {
                binding.clipExportProgressRing.isIndeterminate = false
                binding.clipExportProgressRing.setProgressCompat(lastPercent ?: 0, false)
            }
        }

        binding.clipExportCancel.setText(when {
            running -> R.string.cancel
            success -> R.string.clip_done
            else -> R.string.close
        })
        if (!running) binding.clipExportCancel.isEnabled = true
    }

    private fun requestExport() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val context = requireContext().applicationContext
                val folder = exportFolderUri
                if (folder != null) withContext(Dispatchers.IO) { ClipExportFolder.directory(context, folder) }
                requestExportPermissions()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                UiTools.snacker(requireActivity(), R.string.clip_export_folder_unavailable)
            }
        }
    }

    private fun renderExportFolder() {
        binding.clipExportFolderName.text = if (exportFolderUri == null) getString(R.string.clip_export_folder_default)
            else exportFolderName ?: getString(R.string.clip_export_folder_selected)
    }

    private fun showVideoRatioPicker() {
        videoRatio = ClipVideoRatio.ORIGINAL
        val ratios = ClipVideoRatio.values()
        val labels = arrayOf(getString(R.string.clip_ratio_original), getString(R.string.clip_ratio_portrait), getString(R.string.clip_ratio_landscape))
        val icons = intArrayOf(R.drawable.ic_clip_ratio_original, R.drawable.ic_clip_ratio_portrait, R.drawable.ic_clip_ratio_landscape)
        val adapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_single_choice, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    val icon = ContextCompat.getDrawable(context, icons[position])!!.mutate()
                    val size = (24 * resources.displayMetrics.density).toInt()
                    icon.setBounds(0, 0, size, size)
                    icon.setTint(currentTextColor)
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = (16 * resources.displayMetrics.density).toInt()
                }
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clip_ratio_title)
            .setSingleChoiceItems(adapter, 0) { dialog, position ->
                videoRatio = ratios[position]
                output = ClipOutput.VIDEO
                dialog.dismiss()
                visualPicker.launch(arrayOf("image/*", "video/*"))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestExportPermissions() {
        val context = requireContext()
        val needed = mutableListOf<String>()
        if (exportFolderUri == null && Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isEmpty()) export() else permissions.launch(needed.toTypedArray())
    }

    private fun export() {
        try {
            val args = requireArguments()
            val started = DiscourseClipService.start(requireContext(), audio, ClipRange(args.getLong("start"), args.getLong("end")),
                exportOptions().copy(
                    output = output,
                    visualUri = visualUri,
                    videoRatio = videoRatio,
                    fadeIn = fadeIn,
                    fadeOut = fadeOut,
                    exportFolderUri = exportFolderUri,
                    captionPath = if (binding.clipExportCaptions.isChecked) audio.ccPath else null
                ))
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
