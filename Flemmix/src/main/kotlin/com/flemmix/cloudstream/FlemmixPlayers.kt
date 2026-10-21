package com.flemmix.cloudstream

import java.net.URI
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parsing is deliberately independent of networking and Android. */
internal class FlemmixPlayerParser(
    private val decodeBase64: (String) -> String?,
) {
    fun movie(html: String, origin: String): List<FlemmixPlayer> {
        val document = Jsoup.parse(html, "$origin/")
        return document.select("[data-v], [onclick*=loadVideo]")
            .mapNotNull { player(it, origin, null) }
            .distinctBy(FlemmixPlayer::value)
    }

    fun series(html: String, origin: String): Map<Int, List<FlemmixPlayer>> {
        val document = Jsoup.parse(html, "$origin/")
        val result = linkedMapOf<Int, MutableList<FlemmixPlayer>>()

        fun addBlock(block: Element, episode: Int, language: String) {
            if (episode <= 0) return
            val players = block.select("[data-v], [onclick*=loadVideo]")
                .mapNotNull { player(it, origin, language) }
            if (players.isNotEmpty()) {
                result.getOrPut(episode) { arrayListOf() }.addAll(players)
            }
        }

        // rel points to the episode block. Its visual parents are irrelevant.
        for (item in document.select("[rel]")) {
            val rel = item.attr("rel").trim().removePrefix("#").removePrefix(".")
            val marker = EPISODE_MARKER.matchEntire(rel) ?: continue
            val episode = marker.groupValues[1].toIntOrNull() ?: continue
            val language = language(marker.groupValues[2])
            for (block in document.getElementsByClass(rel)) addBlock(block, episode, language)
            document.getElementById(rel)?.let { addBlock(it, episode, language) }
        }

        // Some pages advertise blocks before (or without) an episode selector.
        // Merge both paths so one recognized list cannot hide another language.
        for (block in document.select("[class], [id]")) {
            val marker = (block.classNames() + block.id()).asSequence()
                .mapNotNull(EPISODE_MARKER::matchEntire).firstOrNull() ?: continue
            val episode = marker.groupValues[1].toIntOrNull() ?: continue
            addBlock(block, episode, language(marker.groupValues[2]))
        }
        return result.mapValues { (_, players) -> players.distinctBy(FlemmixPlayer::value) }
    }

    private fun player(element: Element, origin: String, language: String?): FlemmixPlayer? {
        val rawValues = listOf(
            element.attr("data-v"),
            LOAD_VIDEO.find(element.attr("onclick"))?.groupValues?.get(2).orEmpty(),
        )
        val value = rawValues.asSequence().mapNotNull { raw ->
            val clean = raw.trim().replace("\\/", "/")
            when {
                clean.startsWith("fx1_") -> clean.takeIf { it.length > 4 }
                else -> decodeLegacy(clean)?.let { resolvePlayerUrl(origin, it) }
            }
        }.firstOrNull() ?: return null

        // HTTP HTML still says "Lecteur HD"; the browser decodes data-n later.
        val encodedName = element.attr("data-n").ifBlank {
            element.selectFirst("[data-n]")?.attr("data-n").orEmpty()
        }
        val name = encodedName.takeIf(String::isNotBlank)?.let(decodeBase64)?.trim()
            ?.takeIf(String::isNotBlank)
            ?: element.selectFirst("span")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: element.text().trim().takeIf(String::isNotBlank)
            ?: element.attr("title").trim().takeIf(String::isNotBlank)
            ?: "Lecteur"
        return FlemmixPlayer(listOfNotNull(language, name).joinToString(" · "), value)
    }

    private fun decodeLegacy(value: String): String? {
        if (isPlayerUrl(value)) return value
        if (value.isBlank()) return null
        val decoded = decodeBase64(value)?.trim()?.replace("\\/", "/") ?: return null
        if (isPlayerUrl(decoded)) return decoded
        return decoded.map { char ->
            when (char) {
                in 'a'..'z' -> 'a' + ((char - 'a' + 13) % 26)
                in 'A'..'Z' -> 'A' + ((char - 'A' + 13) % 26)
                else -> char
            }
        }.joinToString("").takeIf(::isPlayerUrl)
    }

    private fun language(marker: String): String =
        if (marker.lowercase() in listOf("vs", "vostfr")) "VOSTFR" else "VF"

    companion object {
        private val EPISODE_MARKER = Regex(
            """(?:ep|episode)[_-]?0*([0-9]+)(?:[_-]?(vf|vs|vostfr))?""",
            RegexOption.IGNORE_CASE,
        )
        private val LOAD_VIDEO = Regex(
            """\bloadVideo\s*\(\s*(['"])(.*?)\1""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        private fun isPlayerUrl(value: String): Boolean =
            value.startsWith("/") || value.startsWith("https://", true) ||
                value.startsWith("http://", true)

        fun resolvePlayerUrl(base: String, value: String): String? = runCatching {
            if (!isPlayerUrl(value)) return null
            val uri = URI("${base.trimEnd('/')}/").resolve(value)
            if (uri.scheme?.lowercase() !in listOf("http", "https") ||
                uri.host.isNullOrBlank() || uri.userInfo != null
            ) return null
            uri.toString()
        }.getOrNull()
    }
}

/** Only resolved HTTP URLs reach CloudStream extractors. */
internal suspend fun resolveFlemmixPlayer(
    player: FlemmixPlayer,
    api: FlemmixPlayerApi?,
    post: suspend (String, Map<String, String>) -> String?,
): FlemmixServer? {
    if (!player.needsResolution) return FlemmixServer(player.label, player.value)
    if (api == null) return null
    val body = post(api.endpoint, api.form(player.value)) ?: return null
    val value = runCatching { JSONObject(body).opt("u") as? String }.getOrNull()
        ?.trim()?.takeIf(String::isNotBlank) ?: return null
    val url = FlemmixPlayerParser.resolvePlayerUrl(api.endpoint, value) ?: return null
    return FlemmixServer(player.label, url)
}

/** Parameters belong to the fetched page; never cache them across page loads. */
internal data class FlemmixPlayerApi(
    val endpoint: String,
    private val key: String,
    private val salt: String,
    private val seed: Long,
    private val multiplier: Long,
) {
    fun form(token: String): Map<String, String> {
        var hash = seed
        for (char in "$token|$key|$salt") {
            hash = (hash * multiplier + char.code) and 0x7fffffffL
        }
        return linkedMapOf(
            "v" to token,
            "k" to key,
            "b" to "1",
            "s" to hash.toString(16).padStart(8, '0'),
            "t" to "1",
        )
    }

    companion object {
        fun fromHtml(html: String, pageUrl: String): FlemmixPlayerApi? = runCatching {
            val scripts = Jsoup.parse(html).select("script:not([src])").map { it.data() }
            val playerScript = scripts.firstOrNull {
                it.contains("fx1_") && it.contains("XMLHttpRequest") && it.contains("playSafe")
            } ?: return null
            val route = Regex("""\.open\(\s*['"]POST['"]\s*,\s*['"]([^'"]+)['"]""",
                RegexOption.IGNORE_CASE).find(playerScript)?.groupValues?.get(1) ?: return null
            val page = URI(pageUrl)
            val endpoint = page.resolve(route)
            // Only call the same-origin endpoint announced by this page.
            if (endpoint.scheme != page.scheme || endpoint.host != page.host ||
                endpoint.port != page.port || endpoint.userInfo != null
            ) return null

            val script = scripts.firstOrNull {
                it.contains("window._fx_k") && it.contains("charCodeAt") &&
                    it.contains("XMLHttpRequest.prototype.send")
            } ?: return null

            fun integer(name: String): Long? = Regex(
                """\b${Regex.escape(name)}\s*=\s*(\d+)\b""",
            ).find(script)?.groupValues?.get(1)?.toLongOrNull()

            val values = Regex("""\b_a\s*=\s*\[([\d,\s]+)]""")
                .find(script)?.groupValues?.get(1)?.split(',')
                ?.map { it.trim().toIntOrNull() ?: return null } ?: return null
            val mask = integer("_m")?.toInt() ?: return null
            val parts = values.map { (it xor mask).toChar() }.joinToString("").split('|')
            if (parts.size != 2 || parts[0].isBlank()) return null
            FlemmixPlayerApi(
                endpoint.toString(), parts[0], parts[1],
                integer("_in") ?: return null, integer("_mu") ?: return null,
            )
        }.getOrNull()
    }
}
