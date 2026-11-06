package org.videolan.vlc.gui.audio

import kotlinx.coroutines.*
import org.videolan.vlc.discourse.CaptionCue
import org.videolan.vlc.discourse.DiscourseAudio

/** One request per selection; cancellation also guards against non-cooperative loaders. */
internal class CaptionLoader(
    private val scope: CoroutineScope,
    private val refresh: suspend (DiscourseAudio) -> DiscourseAudio?,
    private val transcript: suspend (String) -> List<CaptionCue>,
    private val publish: (DiscourseAudio, List<CaptionCue>?, Boolean) -> Unit
) {
    private var audio: DiscourseAudio? = null
    private var job: Job? = null
    private var loaded = false
    private var selection = 0
    val isLoading get() = job?.isActive == true

    fun select(value: DiscourseAudio?) {
        job?.cancel()
        selection++
        audio = value
        loaded = false
    }

    fun request(retry: Boolean = false): Boolean {
        val original = audio ?: return false
        if (!retry && (loaded || job?.isActive == true)) return false
        job?.cancel()
        val generation = ++selection
        job = scope.launch {
            var current = original
            try {
                current = try { refresh(original)?.takeIf { it.id == original.id } ?: original }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { original }
                ensureActive()
                if (generation != selection) return@launch
                val cues = if (current.hasCc == true && !current.ccPath.isNullOrBlank())
                    transcript(current.ccPath!!) else emptyList()
                ensureActive()
                if (generation != selection) return@launch
                audio = current
                loaded = true
                publish(current, cues, false)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                ensureActive()
                if (generation != selection) return@launch
                audio = current
                loaded = true
                publish(current, null, true)
            }
        }
        return true
    }

    fun cancel() { job?.cancel(); selection++ }
}
