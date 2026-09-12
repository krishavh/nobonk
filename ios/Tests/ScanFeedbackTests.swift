import XCTest
@testable import NoBonkCore

final class ScanFeedbackTests: XCTestCase {
    private let person = PersonBox(id: 1, x: 0.3, y: 0.1, width: 0.4, height: 0.6, confidence: 0.9)
    private func feedback(_ time: Double?, now: Double = 100, running: Bool = true, starting: Bool = false,
                          boxes: [PersonBox] = []) -> ScanFeedback {
        ScanFeedback.make(running: running, starting: starting, receivedAt: time, now: now,
                          boxes: boxes, pausedMessage: "Paused to share")
    }
    func testNoRecentClaimBeforeAnAnalysis() {
        XCTAssertEqual(feedback(nil, running: false, starting: true).state, .preparing)
        XCTAssertEqual(feedback(nil).state, .waiting)
        XCTAssertFalse(feedback(nil).recent)
    }
    func testEmptyAnalysisIsNotAnAllClear() {
        XCTAssertEqual(feedback(99).state, .recent)
        XCTAssertEqual(feedback(99).detail, "No selected objects recognized · keep looking up")
    }
    func testBoundaryAndInvalidTimesDoNotAuthorizeCues() {
        XCTAssertTrue(ScanFeedback.isFresh(receivedAt: 95.001, now: 100))
        for time: Double? in [nil, 95, 101, .nan, .infinity, -.infinity] {
            XCTAssertFalse(ScanFeedback.isFresh(receivedAt: time, now: 100))
        }
        XCTAssertFalse(ScanFeedback.isFresh(receivedAt: 100, now: .nan))
    }
    func testDelayedResultDiscardsRecognitionAndFreshResultRecovers() {
        XCTAssertEqual(feedback(90, boxes: [person]).state, .delayed)
        XCTAssertFalse(feedback(90, boxes: [person]).detail.contains("Person"))
        XCTAssertEqual(feedback(100, boxes: [person]).detail, "Person detected")
        XCTAssertEqual(feedback(100, boxes: [person]).state, .recent)
    }
    func testStopTakesPriorityOverOldResults() {
        XCTAssertEqual(feedback(100, running: false, boxes: [person]).state, .paused)
        XCTAssertEqual(feedback(100, running: false, boxes: [person]).detail, "Paused to share")
    }
    func testInvalidDetectionNeverAppearsInSummary() {
        let invalid = PersonBox(id: 2, x: -1, y: 0, width: 0.3, height: 0.7, confidence: 0.9)
        XCTAssertEqual(feedback(100, boxes: [invalid, person]).detail, "Person detected")
    }
}
