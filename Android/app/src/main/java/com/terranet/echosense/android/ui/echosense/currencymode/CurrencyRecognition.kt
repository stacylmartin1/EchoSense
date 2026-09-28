/*
 * Copyright 2026 TerraNet Technologies LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.terranet.echosense.android.ui.echosense.currencymode

import com.terranet.echosense.android.ui.echosense.StructuredOcrResult
import java.util.Locale

internal data class CurrencyDefinition(
    val code: String,
    val name: String,
    val denominations: Set<Int>,
    val issuerPhrases: Set<String>,
    val denominationWords: Map<String, Int> = emptyMap(),
)

internal object SupportedCurrencyCatalog {
    val currencies = listOf(
        CurrencyDefinition(
            code = "USD",
            name = "US Dollars",
            denominations = setOf(1, 2, 5, 10, 20, 50, 100),
            issuerPhrases = setOf(
                "FEDERAL RESERVE NOTE",
                "UNITED STATES OF AMERICA",
                "THE UNITED STATES OF AMERICA",
            ),
            denominationWords = mapOf(
                "ONE HUNDRED" to 100,
                "FIFTY" to 50,
                "TWENTY" to 20,
                "TEN" to 10,
                "FIVE" to 5,
                "TWO" to 2,
                "ONE" to 1,
            ),
        ),
        CurrencyDefinition(
            code = "CAD",
            name = "Canadian Dollars",
            denominations = setOf(5, 10, 20, 50, 100),
            issuerPhrases = setOf("BANK OF CANADA", "BANQUE DU CANADA"),
            denominationWords = mapOf(
                "ONE HUNDRED" to 100,
                "FIFTY" to 50,
                "TWENTY" to 20,
                "TEN" to 10,
                "FIVE" to 5,
            ),
        ),
        CurrencyDefinition(
            code = "EUR",
            name = "Euros",
            denominations = setOf(5, 10, 20, 50, 100, 200, 500),
            issuerPhrases = setOf("EURO"),
        ),
        CurrencyDefinition(
            code = "GBP",
            name = "British Pounds",
            denominations = setOf(5, 10, 20, 50),
            issuerPhrases = setOf("BANK OF ENGLAND"),
            denominationWords = mapOf(
                "FIFTY POUNDS" to 50,
                "TWENTY POUNDS" to 20,
                "TEN POUNDS" to 10,
                "FIVE POUNDS" to 5,
            ),
        ),
        CurrencyDefinition(
            code = "CHF",
            name = "Swiss Francs",
            denominations = setOf(10, 20, 50, 100, 200, 1000),
            issuerPhrases = setOf(
                "SCHWEIZERISCHE NATIONALBANK",
                "BANQUE NATIONALE SUISSE",
                "BANCA NAZIONALE SVIZZERA",
                "BANCA NAZIUNALA SVIZRA",
            ),
        ),
    )

    fun find(code: String): CurrencyDefinition? =
        currencies.firstOrNull { it.code == code.uppercase(Locale.ROOT) }
}

internal data class CurrencyOcrEvidence(
    val rawText: String,
    val countryCodes: Set<String>,
    val denominations: Set<Int>,
    val confidence: Float,
) {
    val hasCountryEvidence: Boolean get() = countryCodes.isNotEmpty()
    val hasDenominationEvidence: Boolean get() = denominations.isNotEmpty()

    fun promptSummary(): String = buildString {
        append("OCR text:\n")
        append(rawText.ifBlank { "(none)" }.take(1_200))
        append("\nDetected supported currency codes: ")
        append(countryCodes.sorted().joinToString().ifBlank { "none" })
        append("\nDetected valid denominations: ")
        append(denominations.sorted().joinToString().ifBlank { "none" })
        append("\nOCR confidence: ")
        append("%.2f".format(Locale.US, confidence))
    }
}

internal object CurrencyEvidenceExtractor {
    private val numberToken = Regex("(?<![A-Z0-9])([1-9][0-9]{0,3})(?![A-Z0-9])")
    private fun phraseRegex(phrase: String) =
        Regex("(?<![A-Z0-9])${Regex.escape(phrase)}(?![A-Z0-9])")

    fun extract(result: StructuredOcrResult): CurrencyOcrEvidence {
        val normalized = result.text
            .uppercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
        val countryCodes = SupportedCurrencyCatalog.currencies
            .filter { currency ->
                currency.issuerPhrases.any { phraseRegex(it).containsMatchIn(normalized) }
            }
            .mapTo(mutableSetOf()) { it.code }

        val allowedDenominations = if (countryCodes.isEmpty()) {
            SupportedCurrencyCatalog.currencies.flatMapTo(mutableSetOf()) { it.denominations }
        } else {
            SupportedCurrencyCatalog.currencies
                .filter { it.code in countryCodes }
                .flatMapTo(mutableSetOf()) { it.denominations }
        }
        val denominations = numberToken.findAll(normalized)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filterTo(mutableSetOf()) { it in allowedDenominations }

        var remainingWords = normalized
        SupportedCurrencyCatalog.currencies
            .filter { countryCodes.isEmpty() || it.code in countryCodes }
            .flatMap { it.denominationWords.entries }
            .sortedByDescending { it.key.length }
            .forEach { (words, value) ->
                val phrase = phraseRegex(words)
                if (phrase.containsMatchIn(remainingWords)) {
                    denominations += value
                    remainingWords = phrase.replace(remainingWords, " ")
                }
            }

        return CurrencyOcrEvidence(
            rawText = result.text.trim(),
            countryCodes = countryCodes,
            denominations = denominations,
            confidence = result.confidence.coerceIn(0f, 1f),
        )
    }
}

internal data class CurrencyVisualAssessment(
    val code: String?,
    val denomination: Int?,
    val imageUsable: Boolean,
    val explicitlyUnsupported: Boolean,
)

internal object CurrencyVisualAssessmentParser {
    private val field = Regex("([A-Z_]+)\\s*=\\s*([^;\\n]+)")

    fun parse(text: String): CurrencyVisualAssessment? {
        val fields = field.findAll(text.uppercase(Locale.ROOT))
            .associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
        if (fields.isEmpty()) return null
        val rawCode = fields["CURRENCY"]
        val code = rawCode?.takeUnless { it == "UNKNOWN" || it == "UNSUPPORTED" }
        val denomination = fields["DENOMINATION"]?.filter(Char::isDigit)?.toIntOrNull()
        val usable = fields["IMAGE_USABLE"] == "YES"
        return CurrencyVisualAssessment(code, denomination, usable, rawCode == "UNSUPPORTED")
    }
}

internal enum class CurrencyDecisionKind {
    IDENTIFIED,
    NEEDS_ANOTHER_VIEW,
    UNSUPPORTED,
    UNRECOGNIZED,
}

internal data class CurrencyDecision(
    val kind: CurrencyDecisionKind,
    val spokenText: String,
)

internal object CurrencyDecisionEngine {
    fun decide(evidence: CurrencyOcrEvidence, visual: CurrencyVisualAssessment?): CurrencyDecision {
        if (visual == null || !visual.imageUsable) {
            return CurrencyDecision(
                CurrencyDecisionKind.UNRECOGNIZED,
                "Unable to identify the bank note. Hold it flat, move closer, and try again.",
            )
        }
        if (visual.explicitlyUnsupported) return CurrencyDecision(
            CurrencyDecisionKind.UNSUPPORTED,
            "This bank note is not one of the supported currencies: US dollars, Canadian dollars, euros, British pounds, or Swiss francs.",
        )
        val code = visual.code ?: return CurrencyDecision(
            CurrencyDecisionKind.UNRECOGNIZED,
            "Unable to identify the bank note. Show the other side and try again.",
        )
        val currency = SupportedCurrencyCatalog.find(code) ?: return CurrencyDecision(
            CurrencyDecisionKind.UNSUPPORTED,
            "This bank note is not one of the supported currencies: US dollars, Canadian dollars, euros, British pounds, or Swiss francs.",
        )
        val denomination = visual.denomination
        if (denomination == null || denomination !in currency.denominations) {
            return CurrencyDecision(
                CurrencyDecisionKind.NEEDS_ANOTHER_VIEW,
                "The currency may be ${currency.name}, but the denomination is unclear. Show the other side and try again.",
            )
        }
        val countryAgrees = evidence.countryCodes == setOf(code)
        val denominationAgrees = denomination in evidence.denominations
        if (!countryAgrees || !denominationAgrees) {
            return CurrencyDecision(
                CurrencyDecisionKind.NEEDS_ANOTHER_VIEW,
                "The note may be $denomination ${currency.name}, but there is not enough matching text to confirm it. Show the other side and try again.",
            )
        }
        return CurrencyDecision(
            CurrencyDecisionKind.IDENTIFIED,
            "$denomination ${currency.name}.",
        )
    }
}

internal fun currencyFusionPrompt(evidence: CurrencyOcrEvidence): String {
    val supported = SupportedCurrencyCatalog.currencies.joinToString("; ") { currency ->
        "${currency.code}: ${currency.denominations.sorted().joinToString()}"
    }
    return """
        Inspect the bank note image using the OCR evidence below. Supported bank notes are limited to USD, CAD, EUR, GBP, and CHF.
        Valid denominations are: $supported.

        ${evidence.promptSummary()}

        Return exactly one line in this format and no other text:
        CURRENCY=USD; DENOMINATION=20; IMAGE_USABLE=YES

        Use CURRENCY=UNSUPPORTED when the note is clearly another currency. Use CURRENCY=UNKNOWN and DENOMINATION=0 when uncertain. IMAGE_USABLE must be NO for blur, glare, severe cropping, or when no bank note is visible. Do not invent a denomination from serial numbers or series years.
    """.trimIndent()
}
