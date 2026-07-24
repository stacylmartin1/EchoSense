package com.google.ai.edge.gallery.ui.echosense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannedVisualCodeTest {
    @Test
    fun webAddressSpeaksHostWithoutOpeningOrReadingFullUrl() {
        val code = ScannedVisualCode(
            type = "QR code",
            value = "https://example.com/private/path?token=secret",
        )

        assertEquals(
            "QR code containing a web address for example.com.",
            code.spokenDescription,
        )
        assertFalse(code.spokenDescription.contains("secret"))
    }

    @Test
    fun wifiCodeDoesNotSpeakPassword() {
        val code = ScannedVisualCode(
            type = "QR code",
            value = "WIFI:T:WPA;S:Home Network;P:very-secret;;",
        )

        assertTrue(code.spokenDescription.contains("Home Network"))
        assertFalse(code.spokenDescription.contains("very-secret"))
    }

    @Test
    fun retailBarcodeDigitsAreSpokenIndividually() {
        val code = ScannedVisualCode(type = "EAN-13 barcode", value = "0123456789012")

        assertEquals(
            "EAN-13 barcode, 0 1 2 3 4 5 6 7 8 9 0 1 2.",
            code.spokenDescription,
        )
    }
}
