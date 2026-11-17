package com.ender.ninni.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val state = PlayerState(
        playing = true,
        title = "Brahms Lullaby — Uyusun da büyüsün ninni",
        artist = "Baby Sleep Music Çocuk Şarkıları",
        volume = 4,
        maxVolume = 15,
        battery = 82,
        charging = true,
        mediaAccess = true,
        duration = 215_000,
        position = 61_250,
        shuffle = false,
        repeat = RepeatMode.ALL,
        volumeCap = 9,
        sleep = Sleep(remainingSeconds = 1_799, fadeSeconds = 600),
    )

    @Test
    fun fullStateRoundTrips() {
        val message = Protocol.decodeState(Protocol.encodeState(state))!!
        assertEquals(state, message.state)
        assertEquals(false, message.truncated)
    }

    @Test
    fun optionalFieldsStayNull() {
        val bare = state.copy(
            duration = null, position = null, shuffle = null, repeat = null, volumeCap = null, sleep = null,
        )
        assertEquals(bare, Protocol.decodeState(Protocol.encodeState(bare))!!.state)
    }

    @Test
    fun version1StateDecodes() {
        val v1 = """{"pv":1,"p":0,"v":4,"m":15,"b":82,"c":0,"n":1}"""
        val decoded = Protocol.decodeState(v1.toByteArray())!!.state!!
        assertEquals(1, decoded.version)
        assertNull(decoded.position)
        assertNull(decoded.sleep)
        assertNull(decoded.volumeCap)
    }

    @Test
    fun smallMtuShortensTitleAndFlagsIt() {
        val bytes = Protocol.encodeState(state, maxBytes = 182) // a typical iPhone MTU
        assertTrue(bytes.size <= 182)
        val message = Protocol.decodeState(bytes)!!
        assertTrue(message.truncated)
        assertEquals(4, message.state!!.volume)
        assertEquals(61_250L, message.state!!.position)
        assertEquals(Sleep(1_799, 600), message.state!!.sleep)
    }

    @Test
    fun defaultMtuFallsBackToReadMe() {
        val bytes = Protocol.encodeState(state, maxBytes = 20)
        assertEquals("""{"x":1}""", String(bytes))
        val message = Protocol.decodeState(bytes)!!
        assertNull(message.state)
        assertTrue(message.truncated)
    }

    @Test
    fun missingTitleDecodesAsNull() {
        val message = Protocol.decodeState(Protocol.encodeState(state.copy(title = null, artist = null)))!!
        assertNull(message.state!!.title)
        assertNull(message.state!!.artist)
    }

    @Test
    fun garbageIsRejected() {
        assertNull(Protocol.decodeState("hello".toByteArray()))
    }

    @Test
    fun everyCommandRoundTrips() {
        val commands = listOf(
            Command.Auth("0427"), Command.SetVolume(7), Command.Seek(93_500), Command.SetShuffle(true),
            Command.SetShuffle(false), Command.SetRepeat(RepeatMode.ONE), Command.SetVolumeCap(9),
            Command.SetVolumeCap(null), Command.StartSleep(1_800, 600), Command.StartSleep(600, 0),
            Command.CancelSleep, Command.Play, Command.Pause, Command.Toggle, Command.Next,
            Command.Previous, Command.Forward, Command.Rewind, Command.VolumeUp, Command.VolumeDown,
            Command.Ping,
        )
        for (command in commands) {
            assertEquals(command, Protocol.parseCommand(command.wire.toByteArray()))
        }
    }

    @Test
    fun badCommandsAreRejected() {
        for (text in listOf("dance", "seek:-5", "shuffle:2", "repeat:7", "cap:-1", "sleep:0:0", "sleep:60:120", "vol:x")) {
            assertNull(text, Protocol.parseCommand(text.toByteArray()))
        }
    }

    @Test
    fun repeatButtonCycles() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next)
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next)
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next)
    }
}
