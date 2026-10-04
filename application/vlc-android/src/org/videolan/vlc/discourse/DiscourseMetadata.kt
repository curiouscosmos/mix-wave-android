package org.videolan.vlc.discourse

data class DiscourseMetadataTotals(
    val durationSeconds: Double?,
    val durationComplete: Boolean,
    val fileSize: Long?,
    val sizeComplete: Boolean
)

fun discourseMetadataTotals(tracks: List<DiscourseAudio>): DiscourseMetadataTotals {
    val durations = tracks.mapNotNull { it.durationSeconds?.takeIf { value -> value.isFinite() && value > 0 } }
    val sizes = tracks.mapNotNull { it.fileSize?.takeIf { value -> value > 0 } }
    return DiscourseMetadataTotals(
        durations.takeIf { it.isNotEmpty() }?.sum(), durations.isNotEmpty() && durations.size == tracks.size,
        sizes.takeIf { it.isNotEmpty() }?.sum(), sizes.isNotEmpty() && sizes.size == tracks.size
    )
}
