package com.ender.ninni.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        artId = "0a1b2c3d",
        queueId = "ffeeddcc",
        queueActive = 4_000_000_001,
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
            artId = null, queueId = null, queueActive = null,
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
            Command.Ping, Command.PlayItem("0be4d7c3", 4_000_000_001), Command.GetPage(Blob.ART, 0),
            Command.GetPage(Blob.QUEUE, 254),
        )
        for (command in commands) {
            assertEquals(command, Protocol.parseCommand(command.wire.toByteArray()))
        }
    }

    @Test
    fun badCommandsAreRejected() {
        for (text in listOf("dance", "seek:-5", "shuffle:2", "repeat:7", "cap:-1", "sleep:0:0", "sleep:60:120", "vol:x",
            "item:abc", "item:12", "item:0be4d7c3", "item:0be4d7c:12", "item:0be4d7cg:12", "get:art", "get:art:255", "get:cover:0", "get:queue:-1")) {
            assertNull(text, Protocol.parseCommand(text.toByteArray()))
        }
    }

    @Test
    fun repeatButtonCycles() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next)
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next)
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next)
    }

    @Test
    fun blobsRoundTripThroughPages() {
        for (size in listOf(0, 1, Protocol.PAGE_PAYLOAD, Protocol.PAGE_PAYLOAD + 1, 6_000, Protocol.MAX_BLOB)) {
            val bytes = ByteArray(size) { (it * 31 + 7).toByte() }
            val id = Protocol.blobId(bytes)
            val count = Protocol.pageCount(size)
            val pages = (0 until count).map { Protocol.decodePage(Protocol.page(Blob.ART, id, bytes, it)!!)!! }
            pages.forEachIndexed { index, page ->
                assertEquals(Blob.ART, page.blob)
                assertEquals(index, page.index)
                assertEquals(count, page.count)
                assertEquals(id, page.id)
                assertTrue(page.payload.size + Protocol.PAGE_HEADER <= 500)
            }
            assertArrayEquals(bytes, pages.fold(ByteArray(0)) { all, page -> all + page.payload })
            assertNull(Protocol.page(Blob.ART, id, bytes, count))
        }
    }

    @Test
    fun tooBigBlobHasNoPages() {
        assertNull(Protocol.page(Blob.ART, 1, ByteArray(Protocol.MAX_BLOB + 1), 0))
    }

    @Test
    fun idTextIsEightHexDigits() {
        assertEquals("00000000", Protocol.idText(0))
        assertEquals("ffffffff", Protocol.idText(-1))
        assertTrue(Protocol.isId(Protocol.idText(0x0be4d7c3)))
        assertFalse(Protocol.isId("0BE4D7C3"))
        assertFalse(Protocol.isId("0be4d7c"))
        assertEquals(Protocol.blobId("abc".toByteArray()), Protocol.blobId("abc".toByteArray()))
    }

    @Test
    fun queueRoundTrips() {
        val queue = Queue(
            "Ninniler",
            listOf(QueueItem(0, "Dandini dandini dastana", "Anonim"), QueueItem(4_000_000_001, "Brahms Lullaby", null)),
        )
        assertEquals(queue, Protocol.decodeQueue(Protocol.encodeQueue(queue)))
        val untitled = Queue(null, emptyList())
        assertEquals(untitled, Protocol.decodeQueue(Protocol.encodeQueue(untitled)))
        assertNull(Protocol.decodeQueue("[]".toByteArray()))
    }

    @Test
    fun queueIsLimited() {
        val long = "ç".repeat(500)
        val queue = Queue(long, List(300) { QueueItem(it.toLong(), long, long) })
        val decoded = Protocol.decodeQueue(Protocol.encodeQueue(queue))!!
        assertEquals(Protocol.MAX_QUEUE_ITEMS, decoded.items.size)
        assertEquals(80, decoded.title!!.length)
        assertEquals(80, decoded.items[0].title.length)
        assertTrue(Protocol.encodeQueue(queue).size <= Protocol.MAX_BLOB)
    }
}
