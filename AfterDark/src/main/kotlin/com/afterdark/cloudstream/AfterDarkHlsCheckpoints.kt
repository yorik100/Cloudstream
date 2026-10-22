package com.afterdark.cloudstream

/**
 * Pure HLS VOD sample planner: returns playlist-relative segment references
 * at requested playback times. No network calls and no token reconstruction.
 */
internal object AfterDarkHlsCheckpoints {
    data class Checkpoint(val seconds: Int, val segment: String)

    fun select(playlist: String, checkpointsSeconds: List<Int> = listOf(120, 600)): List<Checkpoint> {
        if (!playlist.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")) return emptyList()
        val lines = playlist.lineSequence().map(String::trim).filter(String::isNotBlank)
        var nextDuration: Double? = null
        var elapsed = 0.0
        val segments = mutableListOf<Pair<Double, String>>()
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    nextDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it >= 0.0 }
                }
                !line.startsWith('#') && nextDuration != null -> {
                    val duration = nextDuration
                    if (duration > 0.0) segments += elapsed to line
                    elapsed += duration
                    nextDuration = null
                }
            }
        }
        if (segments.size < 2) return emptyList()
        return checkpointsSeconds.distinct().asSequence().filter { it >= 0 && it < elapsed }
            .mapNotNull { seconds ->
                val i = segments.indexOfLast { it.first <= seconds.toDouble() }
                if (i <= 0) null else Checkpoint(seconds, segments[i].second)
            }
            .distinctBy { it.segment }
            .toList()
    }
}
