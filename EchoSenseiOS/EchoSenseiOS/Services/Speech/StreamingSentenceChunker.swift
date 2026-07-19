import Foundation

/// Accumulates streaming LLM tokens and emits complete sentences for TTS playback.
///
/// Sentence boundary detection rules (matching Android implementation):
/// - Splits on `.`, `!`, `?`, `;`, `:` followed by whitespace (confirmed boundary).
/// - Skips common abbreviations (Mr., Dr., St., etc.) and numbered lists (1., 2.).
/// - Batches short sentences (<20 chars) with the next sentence to avoid choppy TTS.
final class StreamingSentenceChunker: @unchecked Sendable {
  private let lock = NSLock()
  private var buffer = ""
  private var pendingShort = ""
  
  private static let minChunkSize = 20
  
  /// Common abbreviations that end with a period but are NOT sentence boundaries.
  private static let abbreviations: Set<String> = [
    "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "ave", "blvd",
    "dept", "est", "govt", "inc", "corp", "ltd", "co", "vs", "etc",
    "approx", "ft", "vol", "no", "fig", "ref", "gen", "sgt", "cpl",
    "pvt", "lt", "col", "capt", "maj", "cmdr", "adm"
  ]
  
  /// Regular expression for numbered list items like "1." "12."
  private static let numberedListRegex = try! NSRegularExpression(pattern: "^\\d+$")
  
  /// Feed a new token (partial text) from the LLM. Returns any complete sentences extracted.
  func onToken(_ token: String) -> [String] {
    lock.lock()
    defer { lock.unlock() }
    
    buffer += token
    return extractSentences()
  }
  
  /// Signal that the LLM is done generating. Returns any remaining text that should be spoken.
  func onDone() -> [String] {
    lock.lock()
    defer { lock.unlock() }
    
    var chunks: [String] = []
    let remaining = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
    buffer = ""
    
    if !remaining.isEmpty {
      if let chunk = enqueueChunk(remaining) {
        chunks.append(chunk)
      }
    }
    
    let finalPending = pendingShort.trimmingCharacters(in: .whitespacesAndNewlines)
    pendingShort = ""
    if !finalPending.isEmpty {
      chunks.append(finalPending)
    }
    
    return chunks
  }
  
  /// Resets state for a new session.
  func clear() {
    lock.lock()
    defer { lock.unlock() }
    buffer = ""
    pendingShort = ""
  }
  
  private func extractSentences() -> [String] {
    var sentences: [String] = []
    
    while true {
      guard let boundaryIndex = findSentenceBoundary(in: buffer) else { break }
      
      let sentence = String(buffer[..<boundaryIndex]).trimmingCharacters(in: .whitespacesAndNewlines)
      buffer = String(buffer[boundaryIndex...])
      
      if !sentence.isEmpty {
        if let chunk = enqueueChunk(sentence) {
          sentences.append(chunk)
        }
      }
    }
    
    return sentences
  }
  
  private func enqueueChunk(_ sentence: String) -> String? {
    if !pendingShort.isEmpty {
      pendingShort += " "
    }
    pendingShort += sentence
    
    if pendingShort.count >= Self.minChunkSize {
      let chunk = pendingShort.trimmingCharacters(in: .whitespacesAndNewlines)
      pendingShort = ""
      return chunk
    }
    
    return nil
  }
  
  private func findSentenceBoundary(in text: String) -> String.Index? {
    let punctuations: Set<Character> = [".", "!", "?", ";", ":"]
    var index = text.startIndex
    
    while index < text.endIndex {
      let char = text[index]
      if punctuations.contains(char) {
        let nextIndex = text.index(after: index)
        if nextIndex >= text.endIndex {
          // Punctuation at the end of the current buffer - wait for more tokens
          return nil
        }
        
        let nextChar = text[nextIndex]
        if nextChar.isWhitespace {
          // Check for abbreviations or numbers
          if char == "." && isAbbreviationOrNumber(text: text, dotIndex: index) {
            index = text.index(after: index)
            continue
          }
          // Confirmed sentence boundary - return index after the whitespace
          return text.index(after: nextIndex)
        }
      }
      index = text.index(after: index)
    }
    
    return nil
  }
  
  private func isAbbreviationOrNumber(text: String, dotIndex: String.Index) -> Bool {
    // Extract the word before the dot
    var start = text.index(before: dotIndex)
    while start >= text.startIndex && (text[start].isLetter || text[start].isNumber) {
      start = text.index(before: start)
    }
    
    let wordStart: String.Index
    if start < text.startIndex {
      wordStart = text.startIndex
    } else {
      wordStart = text.index(after: start)
    }
    
    guard wordStart < dotIndex else { return false }
    let wordBeforeDot = String(text[wordStart..<dotIndex])
    
    // Check numbered list: "1.", "23."
    let range = NSRange(location: 0, length: wordBeforeDot.utf16.count)
    if Self.numberedListRegex.firstMatch(in: wordBeforeDot, options: [], range: range) != nil {
      return true
    }
    
    // Check abbreviation
    return Self.abbreviations.contains(wordBeforeDot.lowercased())
  }
}
