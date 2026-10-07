import Foundation
import XCTest
@testable import EchoSenseiOS

final class CurrencyRecognitionTests: XCTestCase {
  private let twenty = CurrencyDecisionResult(
    kind: .identified,
    spokenText: "20 US Dollars.",
    code: "USD",
    denomination: 20
  )

  // Keep these scenarios and timestamps aligned with Android CurrencyRecognitionTest.
  func testContinuousScanRequiresRepeatedMatchingIdentifications() {
    var stabilizer = CurrencyScanStabilizer(requiredMatches: 2)

    XCTAssertNil(stabilizer.observe(twenty, now: time(1)))
    XCTAssertEqual(stabilizer.observe(twenty, now: time(2)), "20 US Dollars.")
  }

  func testContinuousScanDebouncesDenominationThatRemainsInView() {
    var stabilizer = CurrencyScanStabilizer(requiredMatches: 2, duplicateDebounce: 5)

    XCTAssertNil(stabilizer.observe(twenty, now: time(1)))
    XCTAssertEqual(stabilizer.observe(twenty, now: time(2)), "20 US Dollars.")
    // Elapsed debounce time alone must not repeat an announcement.
    XCTAssertNil(stabilizer.observe(twenty, now: time(20)))
  }

  func testContinuousScanCanAnnounceSameDenominationAfterItLeavesView() {
    var stabilizer = CurrencyScanStabilizer(requiredMatches: 2, duplicateDebounce: 5)
    let noNote = CurrencyDecisionResult(kind: .unrecognized, spokenText: "No note.")

    XCTAssertNil(stabilizer.observe(twenty, now: time(1)))
    XCTAssertEqual(stabilizer.observe(twenty, now: time(2)), "20 US Dollars.")
    XCTAssertNil(stabilizer.observe(noNote, now: time(3)))
    // Returning after a gap still requires two matching observations.
    XCTAssertNil(stabilizer.observe(twenty, now: time(7)))
    XCTAssertEqual(stabilizer.observe(twenty, now: time(8)), "20 US Dollars.")
  }

  private func time(_ seconds: TimeInterval) -> Date {
    Date(timeIntervalSince1970: seconds)
  }
}
