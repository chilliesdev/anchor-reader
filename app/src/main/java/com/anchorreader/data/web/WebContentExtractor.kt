package com.anchorreader.data.web

import java.net.URI

data class ExtractedArticle(
    val title: String,
    val author: String,
    val units: List<ConceptDraft>,
    val sourceUrl: String,
    val fullText: String
)

data class ConceptDraft(
    val title: String,
    val text: String
)

object WebContentExtractor {
    private val URL_REGEX = Regex("""https?://[^\s<>"'{}|\\^`]+""")

    fun findUrl(text: String): String? {
        val match = URL_REGEX.find(text) ?: return null
        return match.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')
    }

    fun extract(rawInput: String): ExtractedArticle {
        val url = findUrl(rawInput) ?: throw IllegalArgumentException("No valid http or https URL found in input.")
        val parsedUri = try {
            URI(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid URL: $url", e)
        }
        val host = (parsedUri.host ?: "").lowercase()

        return when {
            isTwitterHost(host) -> TwitterExtractor.extract(url)
            isRedditHost(host) -> RedditExtractor.extract(url)
            else -> GenericArticleExtractor.extract(url)
        }
    }

    private fun isTwitterHost(host: String): Boolean {
        return host == "twitter.com" || host.endsWith(".twitter.com") ||
               host == "x.com" || host.endsWith(".x.com") ||
               host == "fxtwitter.com" || host.endsWith(".fxtwitter.com") ||
               host == "vxtwitter.com" || host.endsWith(".vxtwitter.com") ||
               host == "fixupx.com" || host.endsWith(".fixupx.com")
    }

    private fun isRedditHost(host: String): Boolean {
        return host == "reddit.com" || host.endsWith(".reddit.com") ||
               host == "redd.it" || host.endsWith(".redd.it")
    }
}
