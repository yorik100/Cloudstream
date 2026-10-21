package com.flemmix.cloudstream

import java.net.URI
import org.jsoup.Jsoup

internal object FlemmixEmbedParser {
    /** A generic "Lecteur 2" label can still point to an identifiable player. */
    fun family(html: String): String? {
        val document = Jsoup.parse(html)
        val identity = document.title() + " " + document.select(
            "meta[property=og:site_name], meta[name=og:site_name]",
        ).joinToString(" ") { it.attr("content") }
        return when {
            Regex("""\bvoe\b""", RegexOption.IGNORE_CASE).containsMatchIn(identity) -> "voe"
            Regex("""\b(?:doodstream|ddstream)\b""", RegexOption.IGNORE_CASE).containsMatchIn(identity) -> "dood"
            Regex("""\buqload\b""", RegexOption.IGNORE_CASE).containsMatchIn(identity) -> "uqload"
            else -> null
        }
    }

    fun redirect(html: String, pageUrl: String): String? {
        for (script in Jsoup.parse(html).select("script:not([src])")) {
            val value = Regex("""\bwindow\.location\.href\s*=\s*(['"])(https?://[^'"]+)\1""")
                .find(script.data())?.groupValues?.get(2) ?: continue
            val url = FlemmixPlayerParser.resolvePlayerUrl(pageUrl, value) ?: continue
            if (url != pageUrl) return url
        }
        return null
    }

    /** Read only the player's sources array, never ads, captions or poster URLs. */
    fun sources(html: String, unpack: (String) -> String?): List<String> {
        return Jsoup.parse(html).select("script:not([src])").flatMap { element ->
            val script = element.data()
            val decoded = if (script.contains("eval(function(")) unpack(script) ?: script else script
            Regex("""(?:\bsources|['"]sources['"])\s*:\s*\[([\s\S]*?)]""").findAll(decoded).flatMap { match ->
                Regex("""(['"])((?:https?:)?//[^'"]+)\1""")
                    .findAll(match.groupValues[1].replace("\\/", "/"))
                    .map { it.groupValues[2].replace("\\u0026", "&") }
            }.filter { url ->
                runCatching { URI(url).host != null }.getOrDefault(false)
            }.toList()
        }.distinct()
    }
}
