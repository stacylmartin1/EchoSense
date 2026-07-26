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

import Foundation
import PDFKit
import UIKit

struct DocumentTextExtractor {
  func extractText(from url: URL) -> [String]? {
    guard let document = PDFDocument(url: url), document.pageCount > 0 else {
      return nil
    }

    let pages = (0..<document.pageCount).compactMap { index in
      document.page(at: index)?.string?.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    let totalLength = pages.reduce(0) { $0 + $1.count }
    return totalLength > 50 ? pages : nil
  }

  func renderPages(from url: URL, maxPages: Int = 3) -> [UIImage] {
    guard let document = PDFDocument(url: url), document.pageCount > 0 else {
      return []
    }

    return (0..<min(maxPages, document.pageCount)).compactMap { index in
      guard let page = document.page(at: index) else { return nil }
      let bounds = page.bounds(for: .mediaBox)
      let scale: CGFloat = 2
      let size = CGSize(width: bounds.width * scale, height: bounds.height * scale)

      UIGraphicsBeginImageContextWithOptions(size, true, 1)
      defer { UIGraphicsEndImageContext() }

      guard let context = UIGraphicsGetCurrentContext() else { return nil }
      UIColor.white.set()
      context.fill(CGRect(origin: .zero, size: size))
      context.saveGState()
      context.scaleBy(x: scale, y: scale)
      page.draw(with: .mediaBox, to: context)
      context.restoreGState()
      return UIGraphicsGetImageFromCurrentImageContext()
    }
  }
}
