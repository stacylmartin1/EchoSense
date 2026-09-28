package com.terranet.echosense.android.ui.echosense.currencymode

import com.terranet.echosense.android.ui.echosense.StructuredOcrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyRecognitionTest {
    @Test
    fun `extracts USD issuer and denomination but ignores serial and series year`() {
        val evidence = CurrencyEvidenceExtractor.extract(
            StructuredOcrResult(
                text = "FEDERAL RESERVE NOTE 20 TWENTY Series 2017 A L12345678",
                lines = emptyList(),
                script = "Latin",
                confidence = 0.92f,
                processingTimeMillis = 10,
            )
        )

        assertEquals(setOf("USD"), evidence.countryCodes)
        assertEquals(setOf(20), evidence.denominations)
    }

    @Test
    fun `requires OCR country and denomination agreement before identification`() {
        val evidence = CurrencyOcrEvidence(
            rawText = "FEDERAL RESERVE NOTE TWENTY 20",
            countryCodes = setOf("USD"),
            denominations = setOf(20),
            confidence = 0.9f,
        )

        val decision = CurrencyDecisionEngine.decide(
            evidence,
            CurrencyVisualAssessment("USD", 20, imageUsable = true, explicitlyUnsupported = false),
        )

        assertEquals(CurrencyDecisionKind.IDENTIFIED, decision.kind)
        assertEquals("20 US Dollars.", decision.spokenText)
    }

    @Test
    fun `asks for another view when vision and OCR disagree`() {
        val evidence = CurrencyOcrEvidence(
            rawText = "FEDERAL RESERVE NOTE TEN 10",
            countryCodes = setOf("USD"),
            denominations = setOf(10),
            confidence = 0.9f,
        )

        val decision = CurrencyDecisionEngine.decide(
            evidence,
            CurrencyVisualAssessment("USD", 20, imageUsable = true, explicitlyUnsupported = false),
        )

        assertEquals(CurrencyDecisionKind.NEEDS_ANOTHER_VIEW, decision.kind)
        assertTrue(decision.spokenText.contains("other side"))
    }

    @Test
    fun `rejects out of catalog currency`() {
        val decision = CurrencyDecisionEngine.decide(
            CurrencyOcrEvidence("", emptySet(), emptySet(), 0f),
            CurrencyVisualAssessment("JPY", 1000, imageUsable = true, explicitlyUnsupported = false),
        )

        assertEquals(CurrencyDecisionKind.UNSUPPORTED, decision.kind)
    }

    @Test
    fun `unknown vision result is not described as an unsupported currency`() {
        val visual = CurrencyVisualAssessmentParser.parse(
            "CURRENCY=UNKNOWN; DENOMINATION=0; IMAGE_USABLE=YES"
        )

        val decision = CurrencyDecisionEngine.decide(
            CurrencyOcrEvidence("", emptySet(), emptySet(), 0f),
            visual,
        )

        assertEquals(CurrencyDecisionKind.UNRECOGNIZED, decision.kind)
    }

    @Test
    fun `long denomination phrase does not also match its shorter word`() {
        val evidence = CurrencyEvidenceExtractor.extract(
            StructuredOcrResult(
                text = "THE UNITED STATES OF AMERICA ONE HUNDRED DOLLARS",
                lines = emptyList(),
                script = "Latin",
                confidence = 0.9f,
                processingTimeMillis = 10,
            )
        )

        assertEquals(setOf(100), evidence.denominations)
    }

    @Test
    fun `issuer evidence requires a complete phrase`() {
        val evidence = CurrencyEvidenceExtractor.extract(
            StructuredOcrResult(
                text = "EUROPE 20",
                lines = emptyList(),
                script = "Latin",
                confidence = 0.9f,
                processingTimeMillis = 10,
            )
        )

        assertTrue(evidence.countryCodes.isEmpty())
    }
}
