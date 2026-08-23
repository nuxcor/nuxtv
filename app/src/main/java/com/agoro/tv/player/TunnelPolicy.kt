package com.agoro.tv.player

import java.util.Collections

/**
 * Decides, per stream and per process, whether ExoPlayer should tunnel.
 *
 * Tunnelled playback hands audio/video sync to the vendor's HAL. On 4K and
 * HDR that is what keeps the picture and sound together on TV silicon; on
 * everything else it buys nothing a viewer can see, and on a fair number of
 * mid-range chipsets — the Amlogic parts in Google TV dongles, the MediaTek
 * parts in Sony and TCL sets — a tunnelled decoder freezes on the PTS jumps
 * and audio-format changes that are routine in an IPTV transport stream.
 * ExoPlayer reports that freeze as buffering, with a full buffer behind it,
 * so it reads to the viewer exactly like a starving line.
 *
 * So: off by default, on for streams that have shown themselves to be 4K or
 * HDR, and off again for the rest of the process the moment a tunnelled
 * stream stalls with its buffer full — that is the device saying no, and it
 * is not asked twice. No setting: a viewer cannot be asked which of their
 * TV's decoder paths works, and the stream and the stall answer it.
 */
internal object TunnelPolicy {
    /** Streams that decoded as 4K or HDR this process; they tunnel from the first frame next time. */
    private val deserving: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Set once a tunnelled stream has frozen with a full buffer on this device. */
    @Volatile
    var refusedByDevice: Boolean = false
        private set

    /** Buffered media, in ms, above which a stall cannot be the network's doing. */
    const val FULL_BUFFER_MS = 8_000L

    fun remember(url: String) {
        deserving += url
    }

    fun refuse() {
        refusedByDevice = true
    }

    /**
     * @param knownUhd what the app learned about this stream on earlier
     * visits (persisted decoded tier), so a 4K channel tunnels from its
     * first frame rather than after it.
     */
    fun wantsTunnel(url: String, knownUhd: Boolean): Boolean =
        !refusedByDevice && (knownUhd || url in deserving)

    /** Test seam. */
    internal fun reset() {
        deserving.clear()
        refusedByDevice = false
    }
}
