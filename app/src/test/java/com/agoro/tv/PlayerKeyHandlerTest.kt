package com.agoro.tv

import android.view.KeyEvent
import com.agoro.tv.ui.player.PlayerKeyAction
import com.agoro.tv.ui.player.PlayerLayer
import com.agoro.tv.ui.player.playerKeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player key map is a pure function — pin its contract down. */
class PlayerKeyHandlerTest {

    private fun press(
        code: Int,
        layer: PlayerLayer = PlayerLayer.None,
        isLive: Boolean = true,
        hasMultipleItems: Boolean = true,
        hasPreviousChannel: Boolean = false,
        bannerVisible: Boolean = false,
        centerArmed: Boolean = false,
        centerLongPressFired: Boolean = false,
        isKeyDown: Boolean = true,
        isKeyUp: Boolean = false,
        repeatCount: Int = 0,
        digitsPending: Boolean = false,
        playing: Boolean = true,
    ) = playerKeyAction(
        code = code,
        isKeyDown = isKeyDown,
        isKeyUp = isKeyUp,
        repeatCount = repeatCount,
        layer = layer,
        isLive = isLive,
        hasMultipleItems = hasMultipleItems,
        hasPreviousChannel = hasPreviousChannel,
        bannerVisible = bannerVisible,
        centerArmed = centerArmed,
        centerLongPressFired = centerLongPressFired,
        digitsPending = digitsPending,
        playing = playing,
    )

    // --- OK press / hold ---------------------------------------------------

    @Test
    fun `OK down on bare playback arms and is swallowed`() {
        val down = press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(down.consumed)
        assertEquals(PlayerKeyAction.CenterArm, down.action)
    }

    @Test
    fun `OK short press release opens the channel options on live`() {
        val up = press(
            KeyEvent.KEYCODE_DPAD_CENTER,
            centerArmed = true,
            isKeyDown = false,
            isKeyUp = true,
        )
        assertTrue(up.consumed)
        assertEquals(PlayerKeyAction.OpenOptions, up.action)
    }

    @Test
    fun `NUMPAD_ENTER and BUTTON_A count as OK`() {
        // HID-style remotes and gamepads send these for their select key.
        for (code in listOf(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)) {
            val down = press(code)
            assertTrue(down.consumed)
            assertEquals(PlayerKeyAction.CenterArm, down.action)
            val up = press(code, centerArmed = true, isKeyDown = false, isKeyUp = true)
            assertTrue(up.consumed)
            assertEquals(PlayerKeyAction.OpenOptions, up.action)
        }
    }

    @Test
    fun `OK short press release opens the controls on VOD`() {
        val up = press(
            KeyEvent.KEYCODE_DPAD_CENTER,
            isLive = false,
            centerArmed = true,
            isKeyDown = false,
            isKeyUp = true,
        )
        assertTrue(up.consumed)
        assertEquals(PlayerKeyAction.ShowControls, up.action)
    }

    @Test
    fun `OK long press fires once on the first repeat`() {
        val repeat = press(KeyEvent.KEYCODE_DPAD_CENTER, centerArmed = true, repeatCount = 1)
        assertTrue(repeat.consumed)
        assertEquals(PlayerKeyAction.CenterLongPress, repeat.action)

        // Further repeats are swallowed quietly.
        val again = press(
            KeyEvent.KEYCODE_DPAD_CENTER,
            layer = PlayerLayer.Options,
            centerArmed = true,
            centerLongPressFired = true,
            repeatCount = 2,
        )
        assertTrue(again.consumed)
        assertEquals(null, again.action)
    }

    @Test
    fun `OK release after a long press is swallowed and clears state`() {
        val up = press(
            KeyEvent.KEYCODE_DPAD_CENTER,
            layer = PlayerLayer.Options,
            centerArmed = true,
            centerLongPressFired = true,
            isKeyDown = false,
            isKeyUp = true,
        )
        assertTrue(up.consumed)
        assertEquals(PlayerKeyAction.CenterRelease, up.action)
    }

    @Test
    fun `OK release after an error landed mid-press only clears the arm`() {
        // An engine error between press and release moves the layer to Error;
        // the release must not open the channel list over the error card.
        val up = press(
            KeyEvent.KEYCODE_DPAD_CENTER,
            layer = PlayerLayer.Error,
            centerArmed = true,
            isKeyDown = false,
            isKeyUp = true,
        )
        assertTrue(up.consumed)
        assertEquals(PlayerKeyAction.CenterRelease, up.action)
    }

    @Test
    fun `OK with controls open passes through to the focused button`() {
        val down = press(KeyEvent.KEYCODE_DPAD_CENTER, layer = PlayerLayer.Controls)
        assertFalse(down.consumed)
        // …but it still refreshes the auto-hide timer.
        assertEquals(PlayerKeyAction.ShowControls, down.action)
    }

    // --- MENU --------------------------------------------------------------

    @Test
    fun `MENU opens the options menu on live and tracks on VOD`() {
        assertEquals(PlayerKeyAction.OpenOptions, press(KeyEvent.KEYCODE_MENU).action)
        assertEquals(PlayerKeyAction.OpenTracks, press(KeyEvent.KEYCODE_MENU, isLive = false).action)
        assertEquals(
            PlayerKeyAction.ShowControls,
            press(KeyEvent.KEYCODE_MENU, layer = PlayerLayer.Controls).action,
        )
        assertFalse(press(KeyEvent.KEYCODE_MENU, layer = PlayerLayer.Options).consumed)
    }

    // --- INFO --------------------------------------------------------------

    @Test
    fun `INFO shows the banner, and a second press opens the controls`() {
        assertEquals(PlayerKeyAction.ShowBanner, press(KeyEvent.KEYCODE_INFO).action)
        assertEquals(
            PlayerKeyAction.ShowControls,
            press(KeyEvent.KEYCODE_INFO, bannerVisible = true).action,
        )
        // With the controls already up, INFO refreshes the banner.
        assertEquals(
            PlayerKeyAction.ShowBanner,
            press(KeyEvent.KEYCODE_INFO, layer = PlayerLayer.Controls, bannerVisible = true).action,
        )
    }

    // --- BACK --------------------------------------------------------------

    @Test
    fun `BACK is never consumed`() {
        for (layer in PlayerLayer.entries) {
            val result = press(KeyEvent.KEYCODE_BACK, layer = layer)
            assertFalse("BACK consumed in $layer", result.consumed)
            assertEquals(null, result.action)
        }
    }

    // --- zapping -----------------------------------------------------------

    @Test
    fun `UP and DOWN zap on live bare playback`() {
        assertEquals(PlayerKeyAction.Zap(+1), press(KeyEvent.KEYCODE_DPAD_UP).action)
        assertEquals(PlayerKeyAction.Zap(-1), press(KeyEvent.KEYCODE_DPAD_DOWN).action)
    }

    @Test
    fun `UP zaps out of the error layer`() {
        val result = press(KeyEvent.KEYCODE_DPAD_UP, layer = PlayerLayer.Error)
        assertTrue(result.consumed)
        assertEquals(PlayerKeyAction.Zap(+1), result.action)
    }

    @Test
    fun `UP and DOWN open the controls on VOD`() {
        assertEquals(
            PlayerKeyAction.ShowControls,
            press(KeyEvent.KEYCODE_DPAD_UP, isLive = false).action,
        )
        assertEquals(
            PlayerKeyAction.ShowControls,
            press(KeyEvent.KEYCODE_DPAD_DOWN, isLive = false).action,
        )
    }

    @Test
    fun `UP does not zap under panels`() {
        assertFalse(press(KeyEvent.KEYCODE_DPAD_UP, layer = PlayerLayer.ChannelList).consumed)
        assertFalse(press(KeyEvent.KEYCODE_DPAD_UP, layer = PlayerLayer.Controls).consumed)
        assertFalse(press(KeyEvent.KEYCODE_DPAD_UP, layer = PlayerLayer.Options).consumed)
    }

    @Test
    fun `channel keys zap through the controls layer too`() {
        val result = press(KeyEvent.KEYCODE_CHANNEL_UP, layer = PlayerLayer.Controls)
        assertTrue(result.consumed)
        assertEquals(PlayerKeyAction.Zap(+1), result.action)
        assertFalse(press(KeyEvent.KEYCODE_CHANNEL_UP, layer = PlayerLayer.Tracks).consumed)
    }

    // --- horizontal axis ---------------------------------------------------

    @Test
    fun `LEFT opens the channel list on live playlists, controls otherwise`() {
        assertEquals(PlayerKeyAction.OpenChannelList, press(KeyEvent.KEYCODE_DPAD_LEFT).action)
        assertEquals(
            PlayerKeyAction.ShowControls,
            press(KeyEvent.KEYCODE_DPAD_LEFT, hasMultipleItems = false).action,
        )
    }

    @Test
    fun `RIGHT opens the controls on live`() {
        assertEquals(PlayerKeyAction.ShowControls, press(KeyEvent.KEYCODE_DPAD_RIGHT).action)
    }

    @Test
    fun `LEFT and RIGHT seek on bare VOD playback`() {
        assertEquals(
            PlayerKeyAction.Seek(-10_000),
            press(KeyEvent.KEYCODE_DPAD_LEFT, isLive = false).action,
        )
        assertEquals(
            PlayerKeyAction.Seek(+10_000),
            press(KeyEvent.KEYCODE_DPAD_RIGHT, isLive = false).action,
        )
        // Under chrome the axis belongs to focus navigation again.
        assertFalse(
            press(KeyEvent.KEYCODE_DPAD_LEFT, isLive = false, layer = PlayerLayer.Controls).consumed
        )
    }

    // --- guide, digits, misc ----------------------------------------------

    @Test
    fun `GUIDE toggles from anywhere on live`() {
        for (layer in PlayerLayer.entries) {
            val result = press(KeyEvent.KEYCODE_GUIDE, layer = layer)
            assertTrue("GUIDE ignored in $layer", result.consumed)
            assertEquals(PlayerKeyAction.ToggleGuide, result.action)
        }
        assertFalse(press(KeyEvent.KEYCODE_GUIDE, isLive = false).consumed)
    }

    @Test
    fun `digits collect from bare playback, controls, channel list and error card`() {
        val layers = listOf(
            PlayerLayer.None, PlayerLayer.Controls, PlayerLayer.ChannelList, PlayerLayer.Error,
        )
        for (layer in layers) {
            val result = press(KeyEvent.KEYCODE_5, layer = layer)
            assertTrue("digit ignored in $layer", result.consumed)
            assertEquals(PlayerKeyAction.Digit(5), result.action)
        }
        assertFalse(press(KeyEvent.KEYCODE_5, layer = PlayerLayer.Guide).consumed)
        assertFalse(press(KeyEvent.KEYCODE_5, isLive = false).consumed)
    }

    @Test
    fun `numpad digits collect like the remote's own digit row`() {
        // HID keyboards and air-mouse remotes send NUMPAD codes instead.
        val result = press(KeyEvent.KEYCODE_NUMPAD_7)
        assertTrue(result.consumed)
        assertEquals(PlayerKeyAction.Digit(7), result.action)
        assertFalse(press(KeyEvent.KEYCODE_NUMPAD_7, isLive = false).consumed)
    }

    @Test
    fun `OK with digits pending commits the number on release`() {
        // The press is swallowed so a channel-list row can't also be clicked.
        val down = press(KeyEvent.KEYCODE_DPAD_CENTER, digitsPending = true)
        assertTrue(down.consumed)
        assertEquals(null, down.action)
        val up = press(
            KeyEvent.KEYCODE_DPAD_CENTER,
            digitsPending = true,
            isKeyDown = false,
            isKeyUp = true,
        )
        assertTrue(up.consumed)
        assertEquals(PlayerKeyAction.CommitDigits, up.action)
        // From the channel list too — everywhere digits collect.
        assertEquals(
            PlayerKeyAction.CommitDigits,
            press(
                KeyEvent.KEYCODE_DPAD_CENTER,
                layer = PlayerLayer.ChannelList,
                digitsPending = true,
                isKeyDown = false,
                isKeyUp = true,
            ).action,
        )
    }

    // --- typed-number resolution ------------------------------------------

    private fun channel(id: String, number: Int) = com.agoro.tv.data.LiveChannel(
        id = id,
        name = "Channel $id",
        logo = null,
        url = "http://host/live/u/p/$id.ts",
        categoryId = null,
        number = number,
    )

    private fun item(channel: com.agoro.tv.data.LiveChannel) = com.agoro.tv.data.PlayableItem(
        url = channel.url,
        title = channel.name,
        channelId = channel.id,
    )

    @Test
    fun `a number inside the zap playlist jumps within it`() {
        val channels = listOf(channel("a", 1), channel("b", 2), channel("c", 3))
        val items = listOf(item(channels[1]), item(channels[2]))
        assertEquals(
            com.agoro.tv.ui.player.DigitTune.Jump(1),
            com.agoro.tv.ui.player.resolveDigitTune(3, items, channels),
        )
    }

    @Test
    fun `a number outside the zap playlist retunes onto the full list`() {
        // The playlist is often one category; the typed number must reach
        // everything the guide numbers.
        val channels = listOf(channel("a", 1), channel("b", 2), channel("c", 3))
        val items = listOf(item(channels[1]))
        assertEquals(
            com.agoro.tv.ui.player.DigitTune.Retune(0),
            com.agoro.tv.ui.player.resolveDigitTune(1, items, channels),
        )
    }

    @Test
    fun `a number nothing carries is unknown, never a positional guess`() {
        val channels = listOf(channel("a", 1), channel("b", 2))
        assertEquals(
            com.agoro.tv.ui.player.DigitTune.Unknown(9),
            com.agoro.tv.ui.player.resolveDigitTune(9, listOf(item(channels[0])), channels),
        )
    }

    @Test
    fun `a rewritten stream url still resolves to its own playlist entry`() {
        // The failure ladder swaps an item's url in place; the channel id is
        // the identity that survives recovery.
        val channels = listOf(channel("a", 1))
        val items = listOf(item(channels[0]).copy(url = "http://host/live/u/p/a.m3u8"))
        assertEquals(
            com.agoro.tv.ui.player.DigitTune.Jump(0),
            com.agoro.tv.ui.player.resolveDigitTune(1, items, channels),
        )
    }

    @Test
    fun `PLAY_PAUSE is swallowed on live playback`() {
        // Remotes alias the centre button to PLAY_PAUSE when a media session
        // is active; pausing a broadcast is pointless and made OK look
        // like it had two behaviours.
        val bare = press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertTrue(bare.consumed)
        assertEquals(null, bare.action)
        // VOD keeps its pause.
        assertEquals(
            PlayerKeyAction.PlayPause,
            press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, isLive = false).action,
        )
    }

    @Test
    fun `INFO and PLAY_PAUSE still work on the error card`() {
        // Typing a number, checking the banner, or pressing play are all ways
        // out of a dead stream — the old player allowed them and so do we.
        assertEquals(
            PlayerKeyAction.ShowBanner,
            press(KeyEvent.KEYCODE_INFO, layer = PlayerLayer.Error).action,
        )
        assertEquals(
            PlayerKeyAction.PlayPause,
            press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, layer = PlayerLayer.Error).action,
        )
    }

    @Test
    fun `last-channel works only with a previous channel`() {
        assertFalse(press(KeyEvent.KEYCODE_LAST_CHANNEL).consumed)
        val result = press(KeyEvent.KEYCODE_LAST_CHANNEL, hasPreviousChannel = true)
        assertTrue(result.consumed)
        assertEquals(PlayerKeyAction.LastChannel, result.action)
    }

    @Test
    fun `last-channel does not zap underneath an open panel`() {
        val under = press(
            KeyEvent.KEYCODE_LAST_CHANNEL, hasPreviousChannel = true, layer = PlayerLayer.Options,
        )
        assertFalse(under.consumed)
        assertEquals(null, under.action)
    }

    @Test
    fun `PLAY_PAUSE resumes a paused live stream`() {
        val paused = press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, playing = false)
        assertTrue(paused.consumed)
        assertEquals(PlayerKeyAction.PlayPause, paused.action)
    }

    @Test
    fun `D-pad travel inside the controls keeps them up`() {
        for (code in listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        )) {
            val move = press(code, layer = PlayerLayer.Controls, isLive = false)
            assertFalse("the focus system owns the move", move.consumed)
            assertEquals(PlayerKeyAction.ShowControls, move.action)
        }
    }

    @Test
    fun `INFO opens the controls on VOD, which has no banner`() {
        assertEquals(PlayerKeyAction.ShowControls, press(KeyEvent.KEYCODE_INFO, isLive = false).action)
    }

    @Test
    fun `unmapped keys poke the controls without being consumed`() {
        val result = press(KeyEvent.KEYCODE_CAPTIONS)
        assertFalse(result.consumed)
        assertEquals(PlayerKeyAction.ShowControls, result.action)
        assertFalse(press(KeyEvent.KEYCODE_CAPTIONS, layer = PlayerLayer.Guide).consumed)
        assertEquals(null, press(KeyEvent.KEYCODE_CAPTIONS, layer = PlayerLayer.Guide).action)
    }
}
