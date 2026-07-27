package app.podara.manager

import app.podara.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class ImportManager(private val db: AppDatabase) {

    companion object {
        internal const val MAX_OPML_CHARACTERS = 2 * 1024 * 1024
    }

    private val podcastManager = PodcastManager(db)

    suspend fun importOpml(opmlContent: String): ImportResult = withContext(Dispatchers.Default) {
        if (opmlContent.length > MAX_OPML_CHARACTERS) {
            return@withContext ImportResult.Error(
                "OPML exceeds the maximum size of $MAX_OPML_CHARACTERS characters"
            )
        }

        var added = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()

        try {
            val factory = secureDocumentBuilderFactory()
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            val document = builder.parse(InputSource(StringReader(opmlContent)))

            val outlines = document.getElementsByTagName("outline")
            for (i in 0 until outlines.length) {
                val outline = outlines.item(i)
                val attributes = outline.attributes
                val type = attributes?.getNamedItem("type")?.nodeValue
                val xmlUrl = attributes?.getNamedItem("xmlUrl")?.nodeValue
                val title = attributes?.getNamedItem("title")?.nodeValue
                    ?: attributes?.getNamedItem("text")?.nodeValue

                if (type == "rss" && xmlUrl != null) {
                    try {
                        when (val result = podcastManager.addPodcast(xmlUrl, null)) {
                            is AddPodcastResult.Created -> added++
                            is AddPodcastResult.Duplicate -> skipped++
                        }
                    } catch (e: Exception) {
                        failed++
                        errors.add("${title ?: xmlUrl}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext ImportResult.Error("Failed to parse OPML: ${e.message}")
        }

        ImportResult.Success(added = added, skipped = skipped, failed = failed, errors = errors)
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            setExpandEntityReferences(false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
}

sealed class ImportResult {
    data class Success(
        val added: Int,
        val skipped: Int,
        val failed: Int,
        val errors: List<String>
    ) : ImportResult()

    data class Error(val message: String) : ImportResult()
}
