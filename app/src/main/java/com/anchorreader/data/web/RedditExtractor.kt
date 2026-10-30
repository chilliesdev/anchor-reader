package com.anchorreader.data.web

import org.json.JSONArray
import java.io.IOException

object RedditExtractor {
    private val REDDIT_POST_REGEX = Regex(
        """https?://(?:www\.|old\.|np\.|new\.)?(?:reddit\.com/r/([A-Za-z0-9_]+)/comments/([a-z0-9]+)|redd\.it/([a-z0-9]+))""",
        RegexOption.IGNORE_CASE
    )

    fun isRedditUrl(url: String): Boolean = REDDIT_POST_REGEX.containsMatchIn(url)

    fun extract(url: String): ExtractedArticle {
        val match = REDDIT_POST_REGEX.find(url)
            ?: throw IllegalArgumentException("Not a valid Reddit post link: $url")

        val subreddit = match.groups[1]?.value
        val postId = match.groups[2]?.value ?: match.groups[3]?.value
            ?: throw IllegalArgumentException("Could not extract Reddit post ID from $url")

        return try {
            fetchViaJson(subreddit, postId, url)
        } catch (e: Exception) {
            try {
                GenericArticleExtractor.extract(url)
            } catch (_: Exception) {
                throw IOException("Could not load Reddit post from $url. Check your internet connection.", e)
            }
        }
    }

    private fun fetchViaJson(subreddit: String?, postId: String, sourceUrl: String): ExtractedArticle {
        val apiUrl = if (subreddit != null) {
            "https://www.reddit.com/r/$subreddit/comments/$postId.json?limit=25"
        } else {
            "https://www.reddit.com/comments/$postId.json?limit=25"
        }

        val response = WebHttp.get(
            apiUrl,
            mapOf("User-Agent" to "android:com.anchorreader:v1.0.0 (by /u/anchorreader)")
        )
        if (response.statusCode !in 200..299) {
            throw IOException("Reddit API returned HTTP ${response.statusCode}")
        }

        val jsonArray = JSONArray(response.body)
        require(jsonArray.length() >= 1) { "Invalid Reddit response: expected listing array" }

        // Listing 0: Post
        val postListing = jsonArray.getJSONObject(0)
        val postChildren = postListing.getJSONObject("data").getJSONArray("children")
        require(postChildren.length() > 0) { "No post data found in Reddit response." }
        val postData = postChildren.getJSONObject(0).getJSONObject("data")

        val postTitle = postData.optString("title", "Reddit Post").trim()
        val postAuthor = postData.optString("author", "anonymous")
        val postSub = postData.optString("subreddit", subreddit ?: "reddit")
        val selftext = postData.optString("selftext", "").trim()
        val postUrl = postData.optString("url", "").trim()

        val units = mutableListOf<ConceptDraft>()

        // Post body concept
        val postBodyText = when {
            selftext.isNotBlank() -> "$postTitle\n\n$selftext"
            postUrl.isNotBlank() && !postUrl.contains("reddit.com") -> "$postTitle\n\nLink: $postUrl"
            else -> postTitle
        }
        units.add(ConceptDraft("Post · r/$postSub", postBodyText))

        // Listing 1: Top comments
        if (jsonArray.length() > 1) {
            val commentListing = jsonArray.getJSONObject(1)
            val commentChildren = commentListing.getJSONObject("data").getJSONArray("children")
            var count = 0
            for (i in 0 until commentChildren.length()) {
                if (count >= 10) break
                val item = commentChildren.getJSONObject(i)
                if (item.optString("kind") != "t1") continue
                val data = item.getJSONObject("data")
                val commentAuthor = data.optString("author", "[deleted]")
                val commentBody = data.optString("body", "").trim()
                val score = data.optInt("score", 0)

                if (commentAuthor == "[deleted]" || commentAuthor.equals("AutoModerator", ignoreCase = true) || commentBody.isBlank()) {
                    continue
                }

                val scoreText = if (score > 0) " (↑$score)" else ""
                val commentTitle = "Comment · u/$commentAuthor$scoreText"
                units.add(ConceptDraft(commentTitle, commentBody))
                count++
            }
        }

        val fullText = units.joinToString("\n\n") { "${it.title}\n${it.text}" }
        val author = "u/$postAuthor (r/$postSub)"

        return ExtractedArticle(
            title = postTitle.take(120),
            author = author.take(120),
            units = units,
            sourceUrl = sourceUrl,
            fullText = fullText
        )
    }
}
