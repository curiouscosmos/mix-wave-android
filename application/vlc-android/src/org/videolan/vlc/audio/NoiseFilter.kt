package org.videolan.vlc.audio

import android.content.Context
import com.kaleyra.noise_filter.dispatcher.DispatcherProvider
import com.kaleyra.noise_filter.model_loader.DeepFilterModelLoader
import com.rikorose.deepfilternet.NativeDeepFilterNet
import kotlinx.coroutines.Dispatchers

/** Synchronous construction on the caller's IO thread, with an independently owned filter. */
internal fun createNoiseFilter(context: Context, level: NoiseCancellationLevel, modelBytes: ByteArray =
    context.resources.openRawResource(com.kaleyra.noise_filter.R.raw.deep_filter_mobile_model).use { it.readBytes() }): NativeDeepFilterNet {
    require(level != NoiseCancellationLevel.OFF)
    return NativeDeepFilterNet(context, attenuationLimit = level.attenuationDb, dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val mainImmediate = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }, modelLoader = object : DeepFilterModelLoader {
        override suspend fun load(context: Context) = modelBytes
    }).also {
        if (it.frameLength !in 2..96000 || it.frameLength % 2 != 0L) {
            it.release()
            error("Noise model initialization failed")
        }
    }
}
