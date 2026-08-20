package com.agoro.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.PlayableItem

/**
 * Which piece of player chrome owns the screen. Exactly one layer at a time —
 * the five booleans this replaced could disagree with each other, and the
 * "GUIDE closes every other panel" rule fell out of keeping them consistent
 * by hand. The channel banner is deliberately *not* a layer: it coexists with
 * bare playback and with the controls, on its own timer.
 */
enum class PlayerLayer { None, Controls, ChannelList, Guide, Tracks, Catchup, Options, Error }

/** What the player should do in response to a key, decided by [playerKeyAction]. */
internal sealed interface PlayerKeyAction {
    /** Show the transport/controls bar (poke). */
    data object ShowControls : PlayerKeyAction

    data class Zap(val delta: Int) : PlayerKeyAction
    data object ShowBanner : PlayerKeyAction
    data object PlayPause : PlayerKeyAction
    data object LastChannel : PlayerKeyAction
    data object ToggleGuide : PlayerKeyAction
    data object OpenChannelList : PlayerKeyAction

    /** The channel options menu (live) — OK, MENU, or long-press OK. */
    data object OpenOptions : PlayerKeyAction

    /** The tracks/options sheet (VOD) — MENU or long-press OK. */
    data object OpenTracks : PlayerKeyAction

    data class Digit(val digit: Int) : PlayerKeyAction

    /** OK with digits collected: tune to the typed number now, skip the wait. */
    data object CommitDigits : PlayerKeyAction

    /** VOD LEFT/RIGHT: seek with the transient seek chrome. */
    data class Seek(val deltaMs: Long) : PlayerKeyAction

    /**
     * OK went down on bare playback. Nothing happens yet — a short press acts
     * on the *release*, so it can be told apart from a long press, and so the
     * release can never land on a row the press just focused and tune it.
     */
    data object CenterArm : PlayerKeyAction

    /** OK held down (first key repeat): channel options / VOD tracks. */
    data object CenterLongPress : PlayerKeyAction

    /** OK released after a long press: swallow it, clear the center state. */
    data object CenterRelease : PlayerKeyAction
}

internal data class PlayerKeyResult(
    val consumed: Boolean,
    val action: PlayerKeyAction? = null,
) {
    companion object {
        val Ignored = PlayerKeyResult(consumed = false)
    }
}

/**
 * The player's key map as a pure function, so it can be unit-tested without a
 * composition. The caller applies the returned action to its state and returns
 * [PlayerKeyResult.consumed] from its onPreviewKeyEvent.
 *
 * The live no-chrome map, TiviMate-style:
 *   OK → channel options (favorite, etc.) · long-OK / MENU → options
 *   LEFT → channel list · RIGHT → controls · INFO → banner (again → controls)
 *   UP/DOWN & CH± → zap · GUIDE → grid · digits → number tune
 *   LAST_CHANNEL/RED → back to previous · PLAY_PAUSE → pause · BACK → exit
 * VOD no-chrome: OK/UP/DOWN → controls · LEFT/RIGHT → ±10s seek ·
 *   long-OK / MENU → tracks.
 */
internal fun playerKeyAction(
    code: Int,
    isKeyDown: Boolean,
    isKeyUp: Boolean,
    repeatCount: Int,
    layer: PlayerLayer,
    isLive: Boolean,
    hasMultipleItems: Boolean,
    hasPreviousChannel: Boolean,
    bannerVisible: Boolean,
    centerArmed: Boolean,
    centerLongPressFired: Boolean,
    /** True while typed channel digits are waiting out their commit timer. */
    digitsPending: Boolean = false,
): PlayerKeyResult {
    // "No overlay" — bare playback or the transport bar. Panels (channel
    // list, guide, tracks, catch-up, options) and the error card own their
    // own keys.
    val noOverlay = layer == PlayerLayer.None || layer == PlayerLayer.Controls
    val chromeFree = layer == PlayerLayer.None
    // Zapping stays available from the error card: UP/DOWN onto another
    // channel is the natural way out of a dead stream, and it clears the error.
    val zapFromBare = chromeFree || layer == PlayerLayer.Error
    // NUMPAD_ENTER and BUTTON_A are what HID-style remotes and gamepads send
    // for their select key — without them OK does nothing on those devices.
    val isCenter = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        code == AndroidKeyEvent.KEYCODE_ENTER ||
        code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
        code == AndroidKeyEvent.KEYCODE_BUTTON_A

    // OK while digits are collected commits the number at once instead of
    // arming — acting on the release, with the press swallowed, so the same
    // preview interception that blocks a channel-list row's click here can
    // never half-run it. NUMPAD_ENTER is deliberately in [isCenter] for this:
    // keypad remotes end a typed number with their own enter key.
    if (isCenter && digitsPending) {
        return if (isKeyUp) PlayerKeyResult(consumed = true, action = PlayerKeyAction.CommitDigits)
        else PlayerKeyResult(consumed = true)
    }

    // OK on bare playback: press arms, hold opens options, release opens the
    // channel options. Acting on the release is what keeps the two press lengths
    // distinguishable — and it doubles as the swallow that used to exist
    // here: an unhandled KeyUp would land on whatever the action just
    // focused and activate it immediately.
    if (isCenter) {
        if (isKeyDown && repeatCount == 0 && chromeFree && !centerArmed) {
            return PlayerKeyResult(consumed = true, action = PlayerKeyAction.CenterArm)
        }
        if (isKeyDown && repeatCount >= 1 && centerArmed && !centerLongPressFired) {
            return PlayerKeyResult(consumed = true, action = PlayerKeyAction.CenterLongPress)
        }
        // Further repeats after the long press fired: swallow quietly.
        if (isKeyDown && centerLongPressFired) return PlayerKeyResult(consumed = true)
        if (isKeyUp && centerLongPressFired) {
            return PlayerKeyResult(consumed = true, action = PlayerKeyAction.CenterRelease)
        }
        if (isKeyUp && centerArmed) {
            // The layer can change between press and release — an engine error
            // landing mid-press sets Error. Opening the channel list on top of
            // it, then backing out, stranded a chrome-less black screen; only
            // a release on still-bare playback opens chrome.
            if (layer != PlayerLayer.None) {
                return PlayerKeyResult(consumed = true, action = PlayerKeyAction.CenterRelease)
            }
            return PlayerKeyResult(
                consumed = true,
                action = if (isLive) PlayerKeyAction.OpenOptions
                else PlayerKeyAction.ShowControls,
            )
        }
    }

    if (!isKeyDown) return PlayerKeyResult.Ignored
    return when (code) {
        AndroidKeyEvent.KEYCODE_CHANNEL_UP ->
            if (noOverlay || layer == PlayerLayer.Error) {
                PlayerKeyResult(true, PlayerKeyAction.Zap(+1))
            } else PlayerKeyResult.Ignored

        AndroidKeyEvent.KEYCODE_CHANNEL_DOWN ->
            if (noOverlay || layer == PlayerLayer.Error) {
                PlayerKeyResult(true, PlayerKeyAction.Zap(-1))
            } else PlayerKeyResult.Ignored

        // INFO summons the banner; pressed again while it's up, it means
        // "tell me more" — the controls.
        AndroidKeyEvent.KEYCODE_INFO ->
            if (chromeFree && bannerVisible) PlayerKeyResult(true, PlayerKeyAction.ShowControls)
            else if (noOverlay || layer == PlayerLayer.Error) {
                PlayerKeyResult(true, PlayerKeyAction.ShowBanner)
            } else PlayerKeyResult.Ignored

        AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
            when {
                // Swallowed on live: pausing a broadcast only resumes at the
                // live edge, and remotes that alias the centre button to
                // PLAY_PAUSE when a media session is active made OK look
                // like it had two random behaviours. Still allowed from the
                // error card, where "press play" is a natural retry.
                isLive && layer != PlayerLayer.Error ->
                    if (noOverlay) PlayerKeyResult(consumed = true) else PlayerKeyResult.Ignored
                noOverlay || layer == PlayerLayer.Error ->
                    PlayerKeyResult(true, PlayerKeyAction.PlayPause)
                else -> PlayerKeyResult.Ignored
            }

        AndroidKeyEvent.KEYCODE_DPAD_UP ->
            when {
                isLive && zapFromBare -> PlayerKeyResult(true, PlayerKeyAction.Zap(+1))
                !isLive && chromeFree -> PlayerKeyResult(true, PlayerKeyAction.ShowControls)
                else -> PlayerKeyResult.Ignored
            }

        AndroidKeyEvent.KEYCODE_DPAD_DOWN ->
            when {
                isLive && zapFromBare -> PlayerKeyResult(true, PlayerKeyAction.Zap(-1))
                !isLive && chromeFree -> PlayerKeyResult(true, PlayerKeyAction.ShowControls)
                else -> PlayerKeyResult.Ignored
            }

        AndroidKeyEvent.KEYCODE_LAST_CHANNEL, AndroidKeyEvent.KEYCODE_PROG_RED ->
            if (isLive && hasPreviousChannel) PlayerKeyResult(true, PlayerKeyAction.LastChannel)
            else PlayerKeyResult.Ignored

        // A dedicated guide key toggles the grid from anywhere in
        // the player — including from inside the mini-guide, where
        // it trades the zapping list for the planning grid.
        AndroidKeyEvent.KEYCODE_GUIDE ->
            if (isLive) PlayerKeyResult(true, PlayerKeyAction.ToggleGuide)
            else PlayerKeyResult.Ignored

        // MENU is the long-press fallback: BLE remotes without key repeat
        // still need a route to the options menu.
        AndroidKeyEvent.KEYCODE_MENU ->
            when {
                chromeFree ->
                    PlayerKeyResult(
                        true,
                        if (isLive) PlayerKeyAction.OpenOptions else PlayerKeyAction.OpenTracks,
                    )
                layer == PlayerLayer.Controls -> PlayerKeyResult(true, PlayerKeyAction.ShowControls)
                else -> PlayerKeyResult.Ignored
            }

        // Live LEFT is the way out to the channel list and, from there,
        // categories and Home. On VOD the horizontal axis is the timeline.
        AndroidKeyEvent.KEYCODE_DPAD_LEFT ->
            when {
                chromeFree && !isLive -> PlayerKeyResult(true, PlayerKeyAction.Seek(-10_000))
                chromeFree ->
                    PlayerKeyResult(
                        consumed = true,
                        action = if (hasMultipleItems) PlayerKeyAction.OpenChannelList
                        else PlayerKeyAction.ShowControls,
                    )
                else -> PlayerKeyResult.Ignored
            }

        AndroidKeyEvent.KEYCODE_DPAD_RIGHT ->
            when {
                chromeFree && !isLive -> PlayerKeyResult(true, PlayerKeyAction.Seek(+10_000))
                chromeFree -> PlayerKeyResult(true, PlayerKeyAction.ShowControls)
                else -> PlayerKeyResult.Ignored
            }

        // Both digit rows: the remote's own keys and the NUMPAD codes that
        // HID keyboards and air-mouse remotes send instead.
        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9,
        in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9,
        ->
            // Digits work from bare playback, the controls, the channel list —
            // and the error card, where typing a number is a way out of a dead
            // stream, exactly like zapping.
            if (isLive && (
                    noOverlay || layer == PlayerLayer.ChannelList ||
                        layer == PlayerLayer.Error
                    )
            ) {
                val base = if (code >= AndroidKeyEvent.KEYCODE_NUMPAD_0) {
                    AndroidKeyEvent.KEYCODE_NUMPAD_0
                } else AndroidKeyEvent.KEYCODE_0
                PlayerKeyResult(true, PlayerKeyAction.Digit(code - base))
            } else PlayerKeyResult.Ignored

        // BACK must not poke: its KeyDown would raise the controls
        // and its KeyUp's back dispatch would then find them open
        // and close them again — every press cancelling itself, so
        // BACK could never reach onExit() and playback had no way
        // out from the remote.
        AndroidKeyEvent.KEYCODE_BACK -> PlayerKeyResult.Ignored

        else ->
            if (noOverlay) PlayerKeyResult(consumed = false, action = PlayerKeyAction.ShowControls)
            else PlayerKeyResult.Ignored
    }
}

/** Where a typed channel number leads — see [resolveDigitTune]. */
internal sealed interface DigitTune {
    /** The channel is in the current zap playlist: jump within it. */
    data class Jump(val itemIndex: Int) : DigitTune

    /** It lives outside the playlist: retune onto the full list at this index. */
    data class Retune(val channelIndex: Int) : DigitTune

    /** No channel carries this number. */
    data class Unknown(val number: Int) : DigitTune
}

/**
 * Resolves a committed channel number against the FULL live list, not just the
 * playlist the player is zapping through. A category pick narrows the zap list
 * to that category, and the whole point of typing a number is that it works
 * from anywhere — matching only inside the current playlist made most numbers
 * "unknown" the moment a category was chosen.
 *
 * Matched by [LiveChannel.number], never by position: the numbers are what the
 * guide's rows display, so typing what you see always lands on it.
 */
internal fun resolveDigitTune(
    number: Int,
    items: List<PlayableItem>,
    channels: List<LiveChannel>,
): DigitTune {
    val channelIndex = channels.indexOfFirst { it.number == number }
    if (channelIndex < 0) return DigitTune.Unknown(number)
    val channel = channels[channelIndex]
    // The url as well as the id: the failure ladder rewrites an item's url in
    // place, so the id is the identity that survives recovery.
    val itemIndex = items.indexOfFirst { it.channelId == channel.id || it.url == channel.url }
    return if (itemIndex >= 0) DigitTune.Jump(itemIndex) else DigitTune.Retune(channelIndex)
}
