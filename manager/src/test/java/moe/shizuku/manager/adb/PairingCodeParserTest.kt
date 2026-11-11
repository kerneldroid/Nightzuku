package moe.shizuku.manager.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCodeParserTest {

    @Test
    fun parsesCodeAndEndpointFromSettingsPairingDialog() {
        val result = PairingCodeParser.parse(
            texts = listOf(
                "Pair device with pairing code",
                "Wi-Fi pairing code",
                "192.168.1.42:37123",
                "123456"
            ),
            packageName = "com.google.android.tv.settings"
        )
        assertEquals("123456", result?.code)
        assertEquals("192.168.1.42", result?.host)
        assertEquals(37123, result?.port)
    }

    @Test
    fun parsesOemPackageWithPairingWords() {
        val result = PairingCodeParser.parse(
            texts = listOf("Wireless debugging", "Pairing code", "654321", "port: 43210"),
            packageName = "com.vendor.tv.assistant"
        )
        assertEquals("654321", result?.code)
        assertEquals(43210, result?.port)
    }

    @Test
    fun ignoresSixDigitsOutsidePairingWindow() {
        assertNull(
            PairingCodeParser.parse(
                texts = listOf("Build number", "202611", "Animation scale 1x"),
                packageName = "com.android.settings"
            )
        )
    }

    @Test
    fun ignoresLongerNumbersAndInvalidPorts() {
        assertNull(
            PairingCodeParser.parse(
                texts = listOf("Pair device", "1234567", "10.0.0.1:70000"),
                packageName = "com.android.settings"
            )
        )
    }

    @Test
    fun codeOnlyIsAcceptedBecauseMdnsSuppliesThePort() {
        val result = PairingCodeParser.parse(
            texts = listOf("Pair with code", "000042"),
            packageName = "com.android.tv.settings"
        )
        assertEquals("000042", result?.code)
        assertNull(result?.port)
    }
}
