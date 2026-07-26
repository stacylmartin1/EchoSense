/*
 * Copyright 2026 TerraNet Technologies LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.terranet.echosense.android.ui.echosense.currencymode

import android.app.Application
import com.terranet.echosense.android.ui.echosense.EchoSenseBaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CurrencyModeViewModel @Inject constructor(
    application: Application
) : EchoSenseBaseViewModel(application) {

    override val supportsCollisionAvoidance: Boolean = false

    override fun getAnalysisPrompt(customPrompt: String?, isVerbose: Boolean): String {
        // Core identification instructions shared by both modes.
        // Emphasis on denomination-first output, disambiguation, and confidence.
        val identificationCore = buildString {
            append("You are a currency identification assistant for a visually impaired person. ")
            append("Your absolute top priority is to identify the DENOMINATION and COUNTRY of each bank note as fast as possible. ")
            append("NEVER begin your response with filler like 'OK', 'Sure', 'I can see', or 'I will describe'. ")
            append("Start your very first word with the denomination number followed by the currency name. ")
            append("Example correct responses: '100 Thai Baht. Confidence: HIGH', '20 US Dollars. Confidence: HIGH'. ")
            append("\n\n")
            append("CONFIDENCE LEVEL — always end your identification with 'Confidence: HIGH', 'Confidence: MEDIUM', or 'Confidence: LOW'.\n")
            append("- HIGH: Key identifying features (text, portraits, symbols) are clearly visible and unambiguous.\n")
            append("- MEDIUM: Some features are visible but image is partially obscured, blurry, or at an angle.\n")
            append("- LOW: Very few identifying features visible; this is your best guess.\n")
            append("If the image is too blurry or does not clearly show currency, respond: 'Unable to identify. Please hold the note closer and try again. Confidence: LOW'\n")
            append("\n")
            append("CRITICAL DISAMBIGUATION RULES — many currencies share visual similarities:\n")
            append("- Thai Baht (฿): Portrait of the Thai King, Thai script (curvy, looping), 'ธนาคารแห่งประเทศไทย' (Bank of Thailand).\n")
            append("- Chinese Yuan/Renminbi (¥): Mao Zedong's portrait, '中国人民银行' (People's Bank of China), simplified Chinese characters.\n")
            append("- New Taiwan Dollar (NT\$): Portraits of Sun Yat-sen or Chiang Kai-shek, '中央銀行' (Central Bank) or '中華民國' (Republic of China), traditional Chinese characters.\n")
            append("- Japanese Yen (¥): Japanese kanji mixed with hiragana/katakana, '日本銀行' (Bank of Japan), Shibusawa Eiichi or other historical figures.\n")
            append("- Korean Won (₩): Hangul script, '한국은행' (Bank of Korea), portraits of scholars like Yi Hwang or Sejong the Great.\n")
            append("- Hong Kong Dollar (HK\$): HSBC, Bank of China HK, or Standard Chartered logos, '香港' text.\n")
            append("- Singapore Dollar (S\$): Portrait of Yusof bin Ishak, '新加坡' or 'SINGAPORE' text.\n")
            append("- Indian Rupee (₹): Portrait of Mahatma Gandhi, '₹' symbol, 'भारतीय रिज़र्व बैंक' (Reserve Bank of India), Devanagari script.\n")
            append("- Vietnamese Dong (₫): Portrait of Ho Chi Minh, '₫' symbol, Vietnamese text with diacritics.\n")
            append("- Malaysian Ringgit (RM): Portrait of Tuanku Abdul Rahman, 'BANK NEGARA MALAYSIA', Jawi and Latin script.\n")
            append("- Philippine Peso (₱): Filipino heroes, '₱' symbol, 'BANGKO SENTRAL NG PILIPINAS'.\n")
            append("- Indonesian Rupiah (Rp): Indonesian national heroes, 'BANK INDONESIA', Latin alphabet.\n")
            append("- Myanmar Kyat: Aung San portrait, Burmese script (circular characters).\n")
            append("- Cambodian Riel: Khmer script, temples or Cambodian landmarks.\n")
            append("- Lao Kip: Kaysone Phomvihane portrait, Lao script.\n")
            append("- US Dollar (\$): Green tint, US presidents, 'FEDERAL RESERVE NOTE', 'IN GOD WE TRUST'.\n")
            append("- Euro (€): Modern architectural motifs, '€' symbol, 'EURO' or 'ΕΥΡΩ'.\n")
            append("- British Pound (£): Portrait of King Charles III or Queen Elizabeth II, 'BANK OF ENGLAND'.\n")
            append("- Australian Dollar (A\$): Polymer/plastic note, Australian figures, Parliament House.\n")
            append("- Canadian Dollar (C\$): Polymer/plastic note, portraits of Canadian PMs or Queen/King.\n")
            append("- Do NOT assume a note is Chinese Yuan just because Chinese characters are present. Taiwan, Hong Kong, and Macau also use Chinese characters.\n")
        }

        return if (customPrompt != null) {
            if (isVerbose) {
                "${identificationCore}\nUser request: $customPrompt\nFirst state the denomination and country with confidence level. Then describe visual details: colors, condition, portraits, and any notable features."
            } else {
                "${identificationCore}\nUser request: $customPrompt\nRespond ONLY with the denomination, country name, and confidence level. Nothing else."
            }
        } else {
            if (isVerbose) {
                "${identificationCore}\nIdentify this bank note. Start with denomination and country (e.g. '500 Thai Baht'). Then on a new sentence describe: the dominant colors, the portrait or imagery on the front, the condition (new, worn, creased), and any other notable features. End with confidence level. Keep it natural and conversational."
            } else {
                "${identificationCore}\nIdentify this bank note. Respond ONLY with the denomination, country name, and confidence level. Example: '100 Thai Baht. Confidence: HIGH' or '20 US Dollars. Confidence: MEDIUM'. If multiple notes are visible, list each on a separate line. Nothing else."
            }
        }
    }

    companion object {
        private const val TAG = "CurrencyModeVM"
    }
}
