package moe.shizuku.manager.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import moe.shizuku.manager.adb.AdbProtocol.A_WRTE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbMessageTest {

    @Test
    fun `toStringShort and toString omit payload bytes to prevent leaking tokens or command output`() {
        val secretPayload = "SECRET_AUTH_TOKEN_12345"
        val msg = AdbMessage(A_WRTE, 1, 2, secretPayload)
        val shortStr = msg.toStringShort()
        val fullStr = msg.toString()

        assertFalse(shortStr.contains(secretPayload))
        assertFalse(fullStr.contains(secretPayload))
        assertEquals(
            "command=A_WRTE, arg0=1, arg1=2, data_length=${secretPayload.length + 1}, data_crc32=${msg.data_crc32}, magic=${msg.magic}",
            shortStr
        )
    }

    @Test
    fun `validate and validateOrThrow detect corrupted magic and payload checksum`() {
        val valid = AdbMessage(A_WRTE, 1, 2, "id")
        assertTrue(valid.validate())
        valid.validateOrThrow()

        val badMagic = AdbMessage(A_WRTE, 1, 2, 3, valid.data_crc32, 0, "id\u0000".toByteArray())
        assertFalse(badMagic.validate())
        assertThrows(IllegalArgumentException::class.java) { badMagic.validateOrThrow() }

        val badChecksum = AdbMessage(A_WRTE, 1, 2, 3, valid.data_crc32 + 1, valid.magic, "id\u0000".toByteArray())
        assertFalse(badChecksum.validate())
        assertThrows(IllegalArgumentException::class.java) { badChecksum.validateOrThrow() }
    }

    @Test
    fun `toByteArray encodes 24-byte little-endian header followed by payload`() {
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        val msg = AdbMessage(A_WRTE, 7, 9, payload)
        val raw = msg.toByteArray()

        assertEquals(AdbMessage.HEADER_LENGTH + payload.size, raw.size)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(A_WRTE, buf.int)
        assertEquals(7, buf.int)
        assertEquals(9, buf.int)
        assertEquals(3, buf.int)
        assertEquals(0x10 + 0x20 + 0x30, buf.int)
        assertEquals(A_WRTE xor -1, buf.int)

        val tail = ByteArray(3)
        buf.get(tail)
        assertArrayEquals(payload, tail)
    }
}
