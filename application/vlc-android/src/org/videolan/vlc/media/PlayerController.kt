package org.videolan.vlc.media

import android.content.Context
import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import org.videolan.BuildConfig
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IMediaList
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.VLCInstance
import org.videolan.resources.VLCOptions
import org.videolan.tools.KEY_EQUALIZER_ENABLED
import org.videolan.tools.*
import org.videolan.vlc.*
import org.videolan.vlc.audio.NoiseOutput
import org.videolan.vlc.gui.dialogs.VideoTracksDialog
import org.videolan.vlc.gui.dialogs.adapters.VlcTrack
import org.videolan.vlc.repository.EqualizerRepository
import org.videolan.vlc.repository.SlaveRepository
import kotlin.math.absoluteValue

class PlayerController(val context: Context) : IVLCVout.Callback, MediaPlayer.EventListener, CoroutineScope {
    override val coroutineContext = Dispatchers.Main.immediate + SupervisorJob()

    //    private val exceptionHandler by lazy(LazyThreadSafetyMode.NONE) { CoroutineExceptionHandler { _, _ -> onPlayerError() } }
    private val playerContext by lazy(LazyThreadSafetyMode.NONE) { newSingleThreadContext("vlc-player") }
    private val settings by lazy(LazyThreadSafetyMode.NONE) { Settings.getInstance(context) }
    val progress by lazy(LazyThreadSafetyMode.NONE) { MutableLiveData<Progress>().apply { value = Progress() } }
    val speed by lazy(LazyThreadSafetyMode.NONE) { MutableLiveData<Float>().apply { value = 1.0F } }
    private val slaveRepository by lazy { SlaveRepository.getInstance(context) }

    private var currentEqualizer: MediaPlayer.Equalizer? = null
    var mediaplayer = newMediaPlayer()
        private set
    var switchToVideo = false
    var onNoiseReset: (() -> Unit)? = null
    private var noiseOutput: NoiseOutput? = null
    // LibVLC's callback output does not report its volume back through getVolume().
    private var noiseVolume: Int? = null
    private var eventGeneration = 0L
    private var outputRestore: Pair<Long, Boolean>? = null
    var seekable = false
    var pausable = false
    var previousMediaStats: IMedia.Stats? = null
        private set
    @Volatile var hasRenderer = false
        private set

    fun getVout(): IVLCVout? = mediaplayer.vlcVout

    fun canDoPassthrough() = mediaplayer.hasMedia() && !mediaplayer.isReleased && mediaplayer.canDoPassthrough()

    fun getMedia(): IMedia? = mediaplayer.media

    fun play() {
        outputRestore = outputRestore?.let { it.first to false }
        if (mediaplayer.hasMedia() && !mediaplayer.isReleased) mediaplayer.play()
    }

    fun pause(): Boolean {
        outputRestore = outputRestore?.let { it.first to true }
        if (isPlaying() && mediaplayer.hasMedia() && pausable) {
            mediaplayer.pause()
            return true
        }
        return false
    }

    fun stop() {
        resetNoiseCancellation()
        if (mediaplayer.hasMedia() && !mediaplayer.isReleased) mediaplayer.stop()
        setPlaybackStopped()
    }

    private fun releaseMedia() = mediaplayer.media?.let {
        it.setEventListener(null)
        it.release()
    }

    private var mediaplayerEventListener: MediaPlayerEventListener? = null
    internal suspend fun startPlayback(media: IMedia, listener: MediaPlayerEventListener, time: Long) {
        resetNoiseCancellation()
        mediaplayerEventListener = listener
        resetPlaybackState(time, media.duration)
        mediaplayer.setEventListener(null)
        withContext(Dispatchers.IO) { if (!mediaplayer.isReleased) mediaplayer.media = media.apply { if (hasRenderer) parse() } }
        mediaplayer.setEventListener(this@PlayerController)
        if (!mediaplayer.isReleased) {
            if (Settings.getInstance(context).getBoolean(KEY_EQUALIZER_ENABLED, false)) withContext(Dispatchers.IO) {
                val repository = EqualizerRepository.getInstance(context)
                setEqualizer(repository.getCurrentEqualizer(context).getEqualizer())
            }
            mediaplayer.setVideoTitleDisplay(MediaPlayer.Position.Disable, 0)
            mediaplayer.play()
        }
    }

    private fun resetPlaybackState(time: Long, duration: Long) {
        seekable = true
        pausable = true
        lastTime = time
        updateProgress(time, duration)
    }

    @MainThread
    fun restart() {
        resetNoiseCancellation()
        val mp = mediaplayer
        val volume:Int? = if (!mp.isReleased) mp.volume else null
        mediaplayer = newMediaPlayer()
        volume?.let {
            if (it > 100) {
                mediaplayer.volume = it
            }
        }
        release(mp)
    }

    fun setPosition(position: Float) {
        outputRestore = outputRestore?.let { (getLength() * position).toLong() to it.second }
        if (seekable && mediaplayer.hasMedia() && !mediaplayer.isReleased) mediaplayer.position = position
    }

    fun setTime(time: Long, fast:Boolean = false) {
        outputRestore = outputRestore?.let { time to it.second }
        if (seekable && mediaplayer.hasMedia() && !mediaplayer.isReleased) mediaplayer.setTime(time, fast)
    }

    fun isPlaying() = playbackState == PlaybackStateCompat.STATE_PLAYING

    fun isPaused() = playbackState == PlaybackStateCompat.STATE_PAUSED

    fun isVideoPlaying() = !mediaplayer.isReleased && mediaplayer.vlcVout.areViewsAttached()

    fun canSwitchToVideo() = getVideoTracksCount() > 0

    fun getVideoTracksCount() = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getVideoTracksCount() else 0

    fun getVideoTracks(): Array<out VlcTrack> = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getAllVideoTracks() else emptyArray()

    fun getVideoTrack():String = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getSelectedVideoTrack()?.getId() ?: "-1" else "-1"

    fun getCurrentVideoTrack(): VlcTrack? = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getSelectedVideoTrack() else null

    fun getAudioTracksCount() = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getAudioTracksCount() else 0

    fun getAudioTracks(): Array<out VlcTrack>? = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getAllAudioTracks() else emptyArray()

    fun getAudioTrack():String = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getSelectedAudioTrack()?.getId() ?: "-1" else "-1"

    fun setVideoTrack(index: String) = !mediaplayer.isReleased && mediaplayer.hasMedia() && mediaplayer.setVideoTrack(index)

    fun setAudioTrack(index: String) = !mediaplayer.isReleased && mediaplayer.hasMedia() && mediaplayer.setAudioTrack(index)

    fun unselectTrackType(trackType: VideoTracksDialog.TrackType) {
        val vlcTrackType = when(trackType) {
            VideoTracksDialog.TrackType.VIDEO -> 1
            VideoTracksDialog.TrackType.AUDIO -> 0
            VideoTracksDialog.TrackType.SPU -> 2
        }
        if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.unselectTrackType(vlcTrackType)
    }

    fun setAudioDigitalOutputEnabled(enabled: Boolean) = !mediaplayer.isReleased && mediaplayer.setAudioDigitalOutputEnabled(enabled)

    fun getAudioDelay() = if (mediaplayer.hasMedia() && !mediaplayer.isReleased) mediaplayer.audioDelay else 0L

    fun getSpuDelay() = if (mediaplayer.hasMedia() && !mediaplayer.isReleased) mediaplayer.spuDelay else 0L

    fun getRate() = if (mediaplayer.hasMedia() && !mediaplayer.isReleased && playbackState != PlaybackStateCompat.STATE_STOPPED) mediaplayer.rate else 1.0f

    fun setSpuDelay(delay: Long) = !mediaplayer.isReleased && mediaplayer.hasMedia() && mediaplayer.setSpuDelay(delay)

    fun setVideoTrackEnabled(enabled: Boolean) = mediaplayer.setVideoTrackEnabled(enabled)

    fun addSubtitleTrack(path: String, select: Boolean) = mediaplayer.addSlave(IMedia.Slave.Type.Subtitle, path, select)

    fun addSubtitleTrack(uri: Uri, select: Boolean) = mediaplayer.addSlave(IMedia.Slave.Type.Subtitle, uri, select)

    fun getSpuTracks(): Array<out VlcTrack>? = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getAllSpuTracks() else emptyArray()

    fun getSpuTrack() = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getSelectedSpuTrack()?.getId() ?: "-1" else "-1"

    fun setSpuTrack(index: String) = !mediaplayer.isReleased && mediaplayer.hasMedia() && mediaplayer.setSpuTrack(index)

    fun getSpuTracksCount() = if (!mediaplayer.isReleased && mediaplayer.hasMedia()) mediaplayer.getSpuTracksCount() else 0

    fun setAudioDelay(delay: Long) = mediaplayer.setAudioDelay(delay)

    fun setEqualizer(equalizer: MediaPlayer.Equalizer?): Boolean {
        currentEqualizer = equalizer
        return mediaplayer.setEqualizer(equalizer)
    }

    @MainThread
    fun setVideoScale(scale: Float) {
        mediaplayer.scale = scale
    }

    fun setVideoAspectRatio(aspect: String?) {
        mediaplayer.aspectRatio = aspect
    }

    fun setRenderer(renderer: RendererItem?) {
        if (!mediaplayer.isReleased) mediaplayer.setRenderer(renderer)
        hasRenderer = renderer !== null
    }

    fun release(player: MediaPlayer = mediaplayer) {
        if (player === mediaplayer && noiseOutput != null) {
            resetNoiseCancellation()
            release()
            return
        }
        if (player === mediaplayer) resetNoiseCancellation()
        player.setEventListener(null)
        if (isVideoPlaying()) player.vlcVout.detachViews()
        releaseMedia()
        launch(Dispatchers.IO) {
            if (BuildConfig.DEBUG) { // Warn if player release is blocking
                try {
                    withTimeout(5000) { player.release() }
                } catch (exception: TimeoutCancellationException) {
                    launch { Toast.makeText(context, "media stop has timeouted!", Toast.LENGTH_LONG).show() }
                }
            } else player.release()
        }
        setPlaybackStopped()
    }

    fun setSlaves(media: IMedia, mw: MediaWrapper) = launch {
        if (mediaplayer.isReleased) return@launch
        val slaves = mw.slaves
        slaves?.let { it.forEach { slave -> media.addSlave(slave) } }
        media.release()
        slaveRepository.getSlaves(mw.location).forEach { slave ->
            if (!slaves.contains(slave)) mediaplayer.addSlave(slave.type, slave.uri.toUri(), false)
        }
        slaves?.let { slaveRepository.saveSlaves(mw) }
    }

    private fun newMediaPlayer() : MediaPlayer {
        return MediaPlayer(VLCInstance.getInstance(context)).apply {
            currentEqualizer?.let { setEqualizer(it) }
            setAudioDigitalOutputEnabled(VLCOptions.isAudioDigitalOutputEnabled(settings))
            VLCOptions.getAout(settings)?.let { setAudioOutput(it) }
            setRenderer(PlaybackService.renderer.value)
            this.vlcVout.addCallback(this@PlayerController)
        }
    }

    /** A user track transition discards callbacks; internal output restarts bypass this hook. */
    fun resetNoiseCancellation() {
        onNoiseReset?.invoke()
        val output = noiseOutput ?: return
        val volume = getVolume()
        noiseOutput = null
        noiseVolume = null
        outputRestore = null
        eventGeneration++
        val old = mediaplayer
        val rate = old.rate
        old.setEventListener(null)
        output.cancel()
        old.stop()
        output.close()
        old.release()
        mediaplayer = newMediaPlayer()
        mediaplayer.volume = volume
        mediaplayer.rate = rate
    }

    /** Recreate the player to remove amem options completely when returning to ordinary output. */
    fun changeNoiseOutput(output: NoiseOutput?): Boolean {
        val old = mediaplayer
        val media = old.media ?: run { output?.close(); return false }
        val time = outputRestore?.first ?: old.time.coerceAtLeast(0)
        val paused = outputRestore?.second ?: isPaused()
        val volume = getVolume()
        val rate = old.rate
        eventGeneration++
        old.setEventListener(null)
        noiseOutput?.cancel()
        old.stop()
        noiseOutput?.close()
        noiseOutput = null
        noiseVolume = null
        old.release()
        mediaplayer = newMediaPlayer()
        var success = true
        try {
            if (output != null) {
                output.install(mediaplayer)
                noiseOutput = output
                noiseVolume = volume
            }
        } catch (_: Exception) {
            output?.close()
            mediaplayer.release()
            mediaplayer = newMediaPlayer()
            success = false
        } catch (_: LinkageError) {
            output?.close()
            mediaplayer.release()
            mediaplayer = newMediaPlayer()
            success = false
        }
        media.addOption(":start-time=${time / 1000.0}")
        mediaplayer.media = media
        media.release()
        mediaplayer.volume = volume
        mediaplayer.rate = rate
        outputRestore = time to paused
        mediaplayer.setEventListener(this)
        mediaplayer.play()
        return success
    }

    override fun onSurfacesCreated(vlcVout: IVLCVout?) {}

    override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {
        switchToVideo = false
    }

    fun getCurrentTime() = progress.value?.time ?: 0L

    fun getLength() = progress.value?.length ?: 0L

    fun setRate(rate: Float, save: Boolean) {
        if (mediaplayer.isReleased) return
        mediaplayer.rate = rate
        speed.postValue(rate)
    }

    /**
     * Update current media meta and return true if player needs to be updated
     *
     * @param id of the Meta event received, -1 for none
     * @return true if UI needs to be updated
     */
    internal fun updateCurrentMeta(id: Int, mw: MediaWrapper?): Boolean {
        if (id == IMedia.Meta.Publisher) return false
        mw?.updateMeta(mediaplayer)
        return id != IMedia.Meta.NowPlaying || mw?.nowPlaying !== null
    }

    /**
     * When changing current media, setPreviousStats is called to store statistics related to the
     * media. SetCurrentStats is called in the case where repeating is set to
     * PlaybackStateCompat.REPEAT_MODE_ONE, and the current media should not be released, as
     * it is still in use.
     */
    fun setCurrentStats() {
        val media = mediaplayer.media ?: return
        previousMediaStats = media.stats
    }

    fun setPreviousStats() {
        val media = mediaplayer.media ?: return
        previousMediaStats = media.stats
        media.release()
    }

    fun updateViewpoint(yaw: Float, pitch: Float, roll: Float, fov: Float, absolute: Boolean) = mediaplayer.updateViewpoint(yaw, pitch, roll, fov, absolute)

    fun navigate(where: Int) = mediaplayer.navigate(where)

    fun getChapters(title: Int): Array<out MediaPlayer.Chapter>? = if (!mediaplayer.isReleased) mediaplayer.getChapters(title) else emptyArray()

    fun getTitles(): Array<out MediaPlayer.Title>? = if (!mediaplayer.isReleased) mediaplayer.titles else emptyArray()

    fun getChapterIdx() = if (!mediaplayer.isReleased) mediaplayer.chapter else -1

    fun setChapterIdx(chapter: Int) {
        if (!mediaplayer.isReleased) mediaplayer.chapter = chapter
    }

    fun getTitleIdx() = if (!mediaplayer.isReleased) mediaplayer.title else -1

    fun setTitleIdx(title: Int) {
        if (!mediaplayer.isReleased)  mediaplayer.title = title
    }

    fun getVolume() = noiseVolume ?: if (!mediaplayer.isReleased) mediaplayer.volume else 100

    fun setVolume(volume: Int): Int {
        val result = if (!mediaplayer.isReleased) mediaplayer.setVolume(volume) else -1
        if (result == 0 && noiseOutput != null) noiseVolume = volume
        return result
    }

    suspend fun expand(): IMediaList? {
        return mediaplayer.media?.let {
            return withContext(playerContext) {
                mediaplayer.setEventListener(null)
                val items = it.subItems()
                it.release()
                mediaplayer.setEventListener(this@PlayerController)
                items
            }
        }
    }

    private var lastTime = 0L
    var lastPosition = 0F
    @OptIn(ObsoleteCoroutinesApi::class)
    private val eventActor = actor<Pair<Long, MediaPlayer.Event>>(capacity = Channel.UNLIMITED, start = CoroutineStart.UNDISPATCHED) {
        for ((generation, event) in channel) {
            if (generation != eventGeneration) continue
            if (event.type == MediaPlayer.Event.EndReached || event.type == MediaPlayer.Event.EncounteredError || event.type == MediaPlayer.Event.Stopped)
                resetNoiseCancellation()
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    playbackState = PlaybackStateCompat.STATE_PLAYING
                    outputRestore?.let { (time, paused) ->
                        outputRestore = null
                        mediaplayer.setTime(time)
                        if (paused) mediaplayer.pause()
                    }
                }
                MediaPlayer.Event.Paused -> playbackState = PlaybackStateCompat.STATE_PAUSED
                MediaPlayer.Event.EncounteredError -> setPlaybackStopped()
                MediaPlayer.Event.PausableChanged -> pausable = event.pausable
                MediaPlayer.Event.SeekableChanged -> seekable = event.seekable
                MediaPlayer.Event.LengthChanged -> updateProgress(newLength = event.lengthChanged)
                MediaPlayer.Event.TimeChanged -> {
                    val time = event.timeChanged
                    if ((time - lastTime).absoluteValue > 950L) {
                        updateProgress(newTime = time)
                        lastTime = time
                    }
                }
                MediaPlayer.Event.PositionChanged -> {
                    lastPosition = event.positionChanged

                }
            }
            mediaplayerEventListener?.onEvent(event)
        }
    }

    @JvmOverloads
    fun updateProgress(newTime: Long = progress.value?.time ?: 0L, newLength: Long = progress.value?.length ?: 0L) {
        progress.value = progress.value?.apply { time = newTime; length = newLength }
    }

    override fun onEvent(event: MediaPlayer.Event?) {
        if (event != null) eventActor.trySend(eventGeneration to event)
    }

    private fun setPlaybackStopped() {
        playbackState = PlaybackStateCompat.STATE_STOPPED
        updateProgress(0L, 0L)
        lastTime = 0L
    }

    //    private fun onPlayerError() {
//        launch(UI) {
//            restart()
//            Toast.makeText(context, context.getString(R.string.feedback_player_crashed), Toast.LENGTH_LONG).show()
//        }
//    }
    companion object {
        @Volatile var playbackState = PlaybackStateCompat.STATE_NONE
            private set
    }
}

const val NO_LENGTH_PROGRESS_MAX = 1000
class Progress(var time: Long = 0L, var length: Long = 0L)

internal interface MediaPlayerEventListener {
    suspend fun onEvent(event: MediaPlayer.Event)
}

private fun Array<IMedia.Slave>?.contains(item: IMedia.Slave) : Boolean {
    if (this == null) return false
    for (slave in this) if (slave.uri == item.uri) return true
    return false
}
