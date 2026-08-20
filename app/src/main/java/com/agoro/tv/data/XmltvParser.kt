package com.agoro.tv.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Folds several guides into one, highest-ranked source first.
 *
 * This replaces a pairwise `plus` applied in a loop, which was wrong three
 * ways at once. It rebuilt every accumulated map on each step — O(N²) copying
 * over a dozen packs, one of them 55 MB, on a TV box. It let a pack that
 * merely *declares* a channel shadow a fuller schedule from a lower-ranked
 * pack, so a row showed a few hours of listings with the rest already
 * downloaded and discarded. And it resolved a normalized key that two packs
 * disagreed on by first-wins, which is precisely the cross-wiring
 * [XmltvData.normalizedToId] documents itself as preventing — an invariant
 * that held inside one parse and was then dropped on the merge, where a dozen
 * packs is exactly where "Sky Sports 1" meets "Sky Sports 1 HD".
 *
 * One accumulator, fed a pack at a time so no pack is held after it is
 * folded in, and the contested set survives the whole fold rather than being
 * reinstated by the next pack along.
 */
class XmltvMerger {
    private val channelNames = HashMap<String, String>()
    private val nameToId = HashMap<String, String>()
    private val altNames = HashMap<String, List<String>>()
    private val normalizedToId = HashMap<String, String>()
    private val contested = HashSet<String>()
    private val programmes = HashMap<String, MutableList<EpgProgram>>()
    private var count = 0

    val isEmpty: Boolean get() = count == 0

    fun add(data: XmltvData) {
        count++
        // First source wins: these identify a channel, and the pack order is
        // the ranking.
        data.channelNames.forEach { (k, v) -> channelNames.putIfAbsent(k, v) }
        data.nameToId.forEach { (k, v) -> nameToId.putIfAbsent(k, v) }
        data.altNames.forEach { (k, v) -> altNames.putIfAbsent(k, v) }
        data.normalizedToId.forEach { (k, v) ->
            val held = normalizedToId.putIfAbsent(k, v)
            if (held != null && held != v) contested += k
        }
        // Schedules accumulate instead: two packs covering one channel are
        // two parts of its day, not a winner and a loser.
        data.programmes.forEach { (k, v) ->
            programmes.getOrPut(k) { ArrayList(v.size) }.addAll(v)
        }
    }

    fun build(): XmltvData? {
        if (count == 0) return null
        contested.forEach { normalizedToId.remove(it) }
        val merged = HashMap<String, List<EpgProgram>>(programmes.size)
        programmes.forEach { (id, list) ->
            merged[id] = if (list.size <= 1) list else list
                // One programme per start time, the higher-ranked pack's —
                // distinctBy keeps the first seen, and packs were added in
                // rank order. This is also what bounds the concatenation:
                // packs overlap heavily, so the union stays close to the
                // largest single schedule rather than their sum.
                .distinctBy { it.startMs }
                .sortedBy { it.startMs }
        }
        return XmltvData(
            channelNames = channelNames,
            programmes = merged,
            nameToId = nameToId,
            normalizedToId = normalizedToId,
            altNames = altNames,
        )
    }
}

data class XmltvData(
    /** channel id → first display name (both trimmed). */
    val channelNames: Map<String, String>,
    /** lowercase channel id → programmes sorted by start. */
    val programmes: Map<String, List<EpgProgram>>,
    /** lowercase display name (every alternate) → lowercase channel id. */
    val nameToId: Map<String, String>,
    /**
     * [EpgMatcher.normalizeKey] of every display-name alternate → lowercase
     * channel id. Keys claimed by two DIFFERENT channels are removed rather
     * than arbitrated — the fuzzy stage must never cross-wire "Sky Sports 1"
     * with "Sky Sports 1 HD" when the guide treats them as distinct.
     */
    val normalizedToId: Map<String, String> = emptyMap(),
    /** lowercase channel id → all display-name alternates. */
    val altNames: Map<String, List<String>> = emptyMap(),
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

        // Every display-name alternate per channel: feeds routinely carry a
        // long name, short name, call sign and localized name, and any of
        // them may be the one a playlist uses.
        val channelAlts = LinkedHashMap<String, MutableList<String>>()
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
                            currentChannelId?.let { channelAlts.getOrPut(it) { mutableListOf() } += text }
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

        val channelNames = HashMap<String, String>(channelAlts.size)
        val altNames = HashMap<String, List<String>>(channelAlts.size)
        val nameToId = HashMap<String, String>(channelAlts.size)
        val normalizedToId = HashMap<String, String>(channelAlts.size)
        val contested = HashSet<String>()
        channelAlts.forEach { (id, names) ->
            val lowerId = id.lowercase()
            channelNames[id] = names.first()
            altNames[lowerId] = names
            names.forEach { name ->
                nameToId.putIfAbsent(name.trim().lowercase(), lowerId)
                val key = EpgMatcher.normalizeKey(name)
                val holder = normalizedToId.putIfAbsent(key, lowerId)
                if (holder != null && holder != lowerId) contested += key
            }
        }
        contested.forEach { normalizedToId.remove(it) }
        return XmltvData(
            channelNames = channelNames,
            programmes = programmes,
            nameToId = nameToId,
            normalizedToId = normalizedToId,
            altNames = altNames,
        )
    }

    private enum class TextTarget { NONE, CHANNEL_NAME, TITLE, DESC }

    private class ProgrammeBuilder(val channel: String, val startMs: Long, val stopMs: Long) {
        var title: String? = null
        var desc: String? = null
    }
}
