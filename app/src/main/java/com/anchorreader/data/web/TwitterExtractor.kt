package com.anchorreader.data.web

import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException

object TwitterExtractor {
    private val TWEET_URL_REGEX = Regex(
        """https?://(?:www\.|mobile\.)?(?:twitter\.com|x\.com|fxtwitter\.com|vxtwitter\.com|fixupx\.com)/([A-Za-z0-9_]+)/status/([0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    fun isTweetUrl(url: String): Boolean = TWEET_URL_REGEX.containsMatchIn(url)

    fun extract(url: String): ExtractedArticle {
        val match = TWEET_URL_REGEX.find(url)
            ?: throw IllegalArgumentException("Not a valid Twitter or X status link: $url")
        val username = match.groupValues[1]
        val statusId = match.groupValues[2]

        return try {
            fetchViaFxTwitter(username, statusId, url)
        } catch (e: Exception) {
            try {
                fetchViaOEmbed(username, statusId, url)
            } catch (fallbackError: Exception) {
                try {
                    GenericArticleExtractor.extract(url)
                } catch (_: Exception) {
                    throw IOException("Could not load tweet from $url. Check your internet connection.", e)
                }
            }
        }
    }

    private fun fetchViaFxTwitter(username: String, statusId: String, sourceUrl: String): ExtractedArticle {
        val apiUrl = "https://api.fxtwitter.com/$username/status/$statusId"
        val response = WebHttp.get(apiUrl, mapOf("Accept" to "application/json"))
        if (response.statusCode !in 200..299) {
            throw IOException("FXTwitter returned HTTP ${response.statusCode}")
        }
        val json = JSONObject(response.body)
        if (json.optInt("code", 200) != 200) {
            throw IOException(json.optString("message", "FXTwitter error"))
        }
        val tweet = json.getJSONObject("tweet")
        val tweets = mutableListOf<JSONObject>()
        tweets.add(tweet)

        val threadArray = tweet.optJSONArray("thread")
        if (threadArray != null) {
            for (i in 0 until threadArray.length()) {
                val t = threadArray.optJSONObject(i)
                if (t != null) tweets.add(t)
            }
        }

        val mainAuthorObj = tweet.optJSONObject("author")
        val mainHandle = mainAuthorObj?.optString("screen_name") ?: username
        val mainName = mainAuthorObj?.optString("name") ?: mainHandle
        val total = tweets.size

        val units = tweets.mapIndexed { index, t ->
            val authorObj = t.optJSONObject("author")
            val handle = authorObj?.optString("screen_name") ?: mainHandle
            val text = t.optString("text").trim()
            val unitTitle = if (total > 1) "Tweet ${index + 1}/$total · @$handle" else "Tweet · @$handle"
            ConceptDraft(unitTitle, text)
        }.filter { it.text.isNotBlank() }

        require(units.isNotEmpty()) { "No readable tweet text found." }
        val title = if (total > 1) "Thread by @$mainHandle ($total tweets)" else (units.first().text.take(50).let { "\"$it…\"" })
        val author = "$mainName (@$mainHandle)"
        val fullText = units.joinToString("\n\n") { "${it.title}\n${it.text}" }

        return ExtractedArticle(
            title = title.take(120),
            author = author.take(120),
            units = units,
            sourceUrl = sourceUrl,
            fullText = fullText
        )
    }

    private fun fetchViaOEmbed(username: String, statusId: String, sourceUrl: String): ExtractedArticle {
        val oEmbedUrl = "https://publish.twitter.com/oembed?url=https://twitter.com/$username/status/$statusId"
        val response = WebHttp.get(oEmbedUrl, mapOf("Accept" to "application/json"))
        if (response.statusCode !in 200..299) {
            throw IOException("Twitter oEmbed returned HTTP ${response.statusCode}")
        }
        val json = JSONObject(response.body)
        val html = json.getString("html")
        val authorName = json.optString("author_name", username)
        val doc = Jsoup.parse(html)
        val tweetText = doc.selectFirst("blockquote p")?.text() ?: doc.text()
        require(tweetText.isNotBlank()) { "No tweet text in oEmbed response." }

        val unit = ConceptDraft("Tweet · @$username", tweetText.trim())
        val title = tweetText.take(50).let { "\"$it…\"" }
        val author = "$authorName (@$username)"
        val fullText = "${unit.title}\n${unit.text}"

        return ExtractedArticle(
            title = title.take(120),
            author = author.take(120),
            units = listOf(unit),
            sourceUrl = sourceUrl,
            fullText = fullText
        )
    }
}
