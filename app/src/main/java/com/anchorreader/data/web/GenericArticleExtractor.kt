package com.anchorreader.data.web

import com.anchorreader.data.TextSegmenter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

object GenericArticleExtractor {
    fun extract(url: String): ExtractedArticle {
        val response = WebHttp.get(url)
        if (response.statusCode !in 200..299) {
            throw IOException("Failed to load article: HTTP ${response.statusCode}")
        }
        return parseHtml(response.body, response.finalUrl)
    }

    fun parseHtml(html: String, sourceUrl: String): ExtractedArticle {
        val doc = Jsoup.parse(html, sourceUrl)
        val title = extractTitle(doc)
        val author = extractAuthor(doc, sourceUrl)

        // Remove clutter elements
        doc.select(
            "script, style, noscript, iframe, svg, nav, header, footer, aside, " +
            ".nav, .navbar, .menu, .footer, .header, .sidebar, .ad, .ads, .advertisement, " +
            ".banner, .cookie, .popup, .social-share, .comments, .related-posts, " +
            "[role=navigation], [role=banner], [role=contentinfo]"
        ).remove()

        val root = findContentRoot(doc)
        val units = chunkContent(root, title)
        val fullText = units.joinToString("\n\n") { "${it.title}\n${it.text}" }.ifBlank {
            root.text().trim()
        }
        require(fullText.count(Char::isLetterOrDigit) >= 3) {
            "No usable article text could be found at this link."
        }
        return ExtractedArticle(
            title = title,
            author = author,
            units = units,
            sourceUrl = sourceUrl,
            fullText = fullText
        )
    }

    private fun extractTitle(doc: Document): String {
        val raw = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=twitter:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.title().takeIf { it.isNotBlank() }
            ?: "Web Article"

        val cleaned = raw.replace(
            Regex(
                """\s*[|\-–—]\s*(Substack|Medium|The Verge|TechCrunch|Wired|BBC News|CNN|Reuters|The New York Times|The Guardian|Bloomberg).*$""",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim()
        return cleaned.ifBlank { "Web Article" }.take(120)
    }

    private fun extractAuthor(doc: Document, sourceUrl: String): String {
        val raw = doc.selectFirst("meta[name=author]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=article:author]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=twitter:creator]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".author, .byline, [rel=author]")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.URI(sourceUrl).host?.removePrefix("www.") }.getOrNull()
            ?: "Web"
        return raw.trim().take(120)
    }

    private fun findContentRoot(doc: Document): Element {
        return doc.selectFirst("article")
            ?: doc.selectFirst("[role=main]")
            ?: doc.selectFirst(".post-content, .article-content, .entry-content, .story-body, .main-content")
            ?: doc.body() ?: doc
    }

    private fun chunkContent(root: Element, articleTitle: String): List<ConceptDraft> {
        val headings = root.select("h2, h3, h4").filter { it.text().isNotBlank() }
        if (headings.size >= 2) {
            val byHeadings = chunkByHeadings(root, headings)
            if (byHeadings.isNotEmpty()) return byHeadings
        }
        return chunkByParagraphs(root)
    }

    private fun chunkByHeadings(root: Element, headings: List<Element>): List<ConceptDraft> {
        val units = mutableListOf<ConceptDraft>()
        var currentTitle = "Introduction"
        val currentTexts = mutableListOf<String>()

        val elements = root.select("h2, h3, h4, p, blockquote, li")
        for (el in elements) {
            if (el in headings) {
                if (currentTexts.isNotEmpty()) {
                    val text = currentTexts.joinToString(" ").trim()
                    if (text.isNotBlank()) {
                        units.add(ConceptDraft(currentTitle.take(120), text))
                    }
                    currentTexts.clear()
                }
                currentTitle = el.text().trim().take(120)
            } else {
                val t = el.text().trim()
                if (t.isNotBlank()) {
                    currentTexts.add(t)
                }
            }
        }
        if (currentTexts.isNotEmpty()) {
            val text = currentTexts.joinToString(" ").trim()
            if (text.isNotBlank()) {
                units.add(ConceptDraft(currentTitle.take(120), text))
            }
        }
        return units
    }

    private fun chunkByParagraphs(root: Element): List<ConceptDraft> {
        val textBlocks = root.select("p, blockquote, li").map { it.text().trim() }.filter { it.isNotBlank() }
        val full = if (textBlocks.isNotEmpty()) textBlocks.joinToString(" ") else root.text().trim()
        val sentences = TextSegmenter.splitSentences(full)
        if (sentences.isEmpty()) return emptyList()

        val chunks = mutableListOf<List<String>>()
        var currentChunk = mutableListOf<String>()
        var currentWords = 0
        for (sentence in sentences) {
            val words = sentence.split(Regex("\\s+")).size
            currentChunk.add(sentence)
            currentWords += words
            if (currentChunk.size >= 3 || currentWords >= 120) {
                chunks.add(currentChunk.toList())
                currentChunk.clear()
                currentWords = 0
            }
        }
        if (currentChunk.isNotEmpty()) {
            if (chunks.isNotEmpty() && currentChunk.size == 1 && currentWords < 40) {
                chunks[chunks.lastIndex] = chunks.last() + currentChunk
            } else {
                chunks.add(currentChunk.toList())
            }
        }

        return chunks.mapIndexed { index, chunkSentences ->
            val title = if (chunks.size == 1) "Article" else "Section ${index + 1} of ${chunks.size}"
            ConceptDraft(title, chunkSentences.joinToString(" "))
        }
    }
}
