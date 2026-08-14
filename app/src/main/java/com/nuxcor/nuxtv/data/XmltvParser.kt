package com.nuxcor.nuxtv.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

data class XmltvData(
    /** channel id → display name (both trimmed). */
    val channelNames: Map<String, String>,
    /** lowercase channel id → programmes sorted by start. */
    val programmes: Map<String, List<EpgProgram>>,
)

/**
 * Streaming parser for XMLTV guides (plain or gzipped), as served by
 * providers and by Xtream's xmltv.php endpoint.
 */
object XmltvParser {

    // XMLTV timestamps look like "20260814200000 +0000"; zone is sometimes missing.
    private val zoned = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val local = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

    private fun parseTime(raw: String?): Long? {
        val t = raw?.trim() ?: return null
        return runCatching { zoned.parse(t)?.time }.getOrNull()
            ?: runCatching { local.parse(t.substringBefore(' '))?.time }.getOrNull()
    }

    fun parse(input: InputStream): XmltvData {
        val buffered = input.buffered(64 * 1024)
        // Sniff gzip magic bytes so .gz guides work regardless of extension.
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        val stream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered

        val channelNames = HashMap<String, String>()
        val programmes = HashMap<String, MutableList<EpgProgram>>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        var currentChannelId: String? = null
        var programme: ProgrammeBuilder? = null
        var textTarget: TextTarget = TextTarget.NONE

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> currentChannelId = parser.getAttributeValue(null, "id")?.trim()
                    "display-name" -> if (currentChannelId != null) textTarget = TextTarget.CHANNEL_NAME
                    "programme" -> {
                        val channel = parser.getAttributeValue(null, "channel")?.trim()
                        val start = parseTime(parser.getAttributeValue(null, "start"))
                        val stop = parseTime(parser.getAttributeValue(null, "stop"))
                        programme = if (channel != null && start != null && stop != null) {
                            ProgrammeBuilder(channel, start, stop)
                        } else null
                    }
                    "title" -> if (programme != null) textTarget = TextTarget.TITLE
                    "desc" -> if (programme != null) textTarget = TextTarget.DESC
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) when (textTarget) {
                        TextTarget.CHANNEL_NAME ->
                            currentChannelId?.let { channelNames.putIfAbsent(it, text) }
                        TextTarget.TITLE -> programme?.let { it.title = it.title ?: text }
                        TextTarget.DESC -> programme?.let { it.desc = it.desc ?: text }
                        TextTarget.NONE -> Unit
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> currentChannelId = null
                    "display-name", "title", "desc" -> textTarget = TextTarget.NONE
                    "programme" -> {
                        programme?.let { p ->
                            programmes.getOrPut(p.channel.lowercase()) { mutableListOf() } += EpgProgram(
                                id = "${p.channel}:${p.startMs}",
                                title = p.title ?: "Untitled",
                                description = p.desc,
                                startMs = p.startMs,
                                endMs = p.stopMs,
                                hasArchive = false,
                            )
                        }
                        programme = null
                    }
                }
            }
            event = parser.next()
        }

        programmes.values.forEach { it.sortBy { p -> p.startMs } }
        return XmltvData(channelNames = channelNames, programmes = programmes)
    }

    private enum class TextTarget { NONE, CHANNEL_NAME, TITLE, DESC }

    private class ProgrammeBuilder(val channel: String, val startMs: Long, val stopMs: Long) {
        var title: String? = null
        var desc: String? = null
    }
}
