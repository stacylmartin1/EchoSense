/*
 * Copyright 2025 TerraNet Technologies LLC
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

package com.terranet.echosense.android.ui.echosense

/** Converts Markdown-heavy model output into natural text for speech synthesis. */
object SpeechTextSanitizer {
  fun sanitize(text: String): String =
    text
      .replace(Regex("""```[\w+-]*\s*"""), "")
      .replace("```", "")
      .replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")
      .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
      .replace(Regex("""(?m)^\s{0,3}#{1,6}\s+"""), "")
      .replace(Regex("""(?m)^\s*>\s?"""), "")
      .replace(Regex("""(?m)^\s*(?:[-+*]|\d+[.)])\s+"""), "")
      .replace(Regex("""[*_~`]"""), "")
      .replace(Regex("""<[^>]+>"""), " ")
      .replace('|', ',')
      .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
      .replace(Regex("""[ \t]+"""), " ")
      .replace(Regex("""(?<![.!?;:])\s*\n+\s*"""), ". ")
      .replace(Regex("""\s*\n+\s*"""), " ")
      .replace(Regex("""(?:\.\s*){2,}"""), ". ")
      .trim()
}
