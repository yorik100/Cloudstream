package com.afterdark.cloudstream

import java.net.URI
import java.util.Locale

/** Routing and identity must describe the actual URL, not just the hoster label. */
internal object AfterDarkSourcePolicy {
    private fun lower(value: String): String = value.lowercase(Locale.ROOT)

    private fun path(url: String): String = runCatching {
        lower(URI(url).path.orEmpty())
    }.getOrDefault("")

    fun isKnownNative(source: ParsedSource): Boolean {
        val host = AfterDarkMediaProbe.safeHost(source.url)
        val service = lower(source.service)
        return "uqload" in host || "vidzy" in host ||
            "uqload" in service || "vidzy" in service
    }

    fun isEmbed(source: ParsedSource): Boolean {
        val declared = lower(source.type.orEmpty())
        if (declared in setOf("embed", "iframe", "player")) return true
        val path = path(source.url)
        // Misdeclared embed pages still need the native HTML resolver.
        if (path.contains("/embed-") || path.contains("/embed/")) return true
        if (isKnownNative(source) && !source.proxied &&
            (path.endsWith(".html") || Regex("""^/(?:d/|download/|file/|f/|v/)?[a-z0-9]{12}$""").matches(path))) {
            return true
        }
        // A Paris/Uqload proxy is a direct resource even though its service
        // is "uqload". This must precede service-based fallback detection.
        if (source.proxied || declared in setOf("hls", "m3u8", "dash", "mpd", "mp4", "video", "direct")) {
            return false
        }
        if (Regex("""\.(m3u8|mpd|mp4|m4v|mkv|webm)$""").containsMatchIn(path)) return false
        return isKnownNative(source)
    }

    fun isDecoy(url: String): Boolean = path(url).contains("/troll/") &&
        Regex("""(?:^|\.)(?:vidzy|fsvid)\.(?:cc|org|live|to|lol)$""")
            .containsMatchIn(AfterDarkMediaProbe.safeHost(url))

    private fun headerKey(headers: Map<String, String>): String = headers.entries
        .sortedBy { lower(it.key) }.joinToString("\n") { "${lower(it.key)}:${it.value}" }

    fun sourceKey(source: ParsedSource): List<String> = listOf(
        source.url, lower(source.provider), lower(source.service),
        lower(source.language.orEmpty()), source.quality.orEmpty(),
        if (isEmbed(source)) "embed" else "direct",
        source.referer.orEmpty(), headerKey(source.headers),
    )

    /**
     * The site puts every embed into its "Embeds" selector even when the
     * embedded source inherited provider="Paris" from the API. Never label
     * an embed "Paris": group by actual playback mode first, not by the
     * provider field. Keep direct Uqload (Paris) distinct from Uqload Embed.
     */
    fun displayName(source: ParsedSource): String {
        val category = if (isEmbed(source)) {
            "Embed"
        } else {
            source.provider.takeIf { it.isNotBlank() && !it.equals("AfterDark", true) }
                ?: source.group.takeIf { it.isNotBlank() && !it.equals("AfterDark", true) }
                ?: "Direct"
        }

        return listOfNotNull(
            category,
            source.service.takeIf(String::isNotBlank),
            source.language?.takeIf(String::isNotBlank),
            source.quality?.takeIf(String::isNotBlank),
        ).distinct().joinToString(" · ")
    }

    // Never remove signed query parameters to deduplicate. Keep languages and
    // request contexts distinct even when two choices share a media URL.
    fun mediaKey(source: ParsedSource, media: ResolvedWebMedia): List<String> = listOf(
        media.url, media.type.orEmpty(), lower(source.language.orEmpty()),
        media.referer.orEmpty(),
        headerKey(media.headers),
    )
}
