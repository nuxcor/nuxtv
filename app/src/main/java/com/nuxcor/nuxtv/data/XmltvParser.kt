package com.nuxcor.nuxtv.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

data class XmltvData(
    /** channel id → display name (both trimmed). */
    val channelNames: Map<String, String>,
    /** lowercase channel id → programmes sorted by start. */
    val programmes: Map<String, List<EpgProgram>>,
    /** lowercase display name → lowercase channel id, for O(1) name matching. */
    val nameToId: Map<String, String>,
)

/**
 * Streaming parser for XMLTV guides (plain or gzipped), as served by
 * providers and by Xtream's xmltv.php endpoint.
 */
object XmltvParser {

    private const val MAX_DESCRIPTION_LENGTH = 300

    fun parse(
        input: InputStream,
        /** Programmes entirely outside [windowStartMs, windowEndMs] are dropped
         *  so multi-week guide packs don't exhaust memory on TV boxes. */
        windowStartMs: Long = Long.MIN_VALUE,
        windowEndMs: Long = Long.MAX_VALUE,
    ): XmltvData {
        // SimpleDateFormat is not thread-safe; create per parse call.
        val zoned = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val local = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fun parseTime(raw: String?): Long? {
            val t = raw?.trim() ?: return null
            return runCatching { zoned.parse(t)?.time }.getOrNull()
                ?: runCatching { local.parse(t.substringBefore(' '))?.time }.getOrNull()
        }

        val buffered = input.buffered(64 * 1024)
        // Sniff gzip magic bytes so .gz guides work regardless of extension.
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        val stream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered

        val channelNames = HashMap<String, String>()
        val programmes = HashMap<String, MutableList<EpgProgram>>()

        val parser = XmlPullParserFactory.newInstance().newPullParser()
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
                        programme = if (channel != null && start != null && stop != null &&
                            stop > windowStartMs && start < windowEndMs
                        ) {
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
                                description = p.desc?.take(MAX_DESCRIPTION_LENGTH),
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
        val nameToId = HashMap<String, String>(channelNames.size)
        channelNames.forEach { (id, name) -> nameToId.putIfAbsent(name.trim().lowercase(), id.lowercase()) }
        return XmltvData(channelNames = channelNames, programmes = programmes, nameToId = nameToId)
    }

    private enum class TextTarget { NONE, CHANNEL_NAME, TITLE, DESC }

    private class ProgrammeBuilder(val channel: String, val startMs: Long, val stopMs: Long) {
        var title: String? = null
        var desc: String? = null
    }
}
