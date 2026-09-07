import XCTest
import CoreGraphics
@testable import MessagesCameraLabCore

final class MessageCameraJournalTests: XCTestCase {
    private func start(_ journal: MessageCameraJournal, at now: Double = 10) -> MessageCameraToken {
        let activation = journal.activate(at: now)
        journal.acknowledge(activation: activation)
        return journal.begin(at: now)!
    }
    func testEveryActivationRequiresAcknowledgmentAndExplicitStart() {
        let j = MessageCameraJournal()
        XCTAssertNil(j.begin(at: 1))
        let activation = j.activate(at: 1)
        XCTAssertNil(j.begin(at: 2))
        j.acknowledge(activation: activation)
        let old = j.begin(at: 3)!
        j.deactivate(at: 4, reason: "Dismissed")
        let next = j.activate(at: 5)
        XCTAssertNotEqual(activation, next)
        j.acknowledge(activation: activation) // delayed old UI action
        XCTAssertNil(j.begin(at: 6))
        XCTAssertFalse(j.accepts(old))
        j.acknowledge(activation: next)
        XCTAssertNotNil(j.begin(at: 7))
    }
    func testDuplicateActivationDoesNotResetOrRestartRun() {
        let j = MessageCameraJournal(); let token = start(j)
        XCTAssertEqual(j.activate(at: 11), token.activation)
        XCTAssertTrue(j.accepts(token)); XCTAssertNil(j.begin(at: 12))
    }
    func testPermissionGrantAfterStopCannotStartCapture() {
        let j = MessageCameraJournal(); let token = start(j)
        j.stop(at: 11, reason: "Stop")
        XCTAssertFalse(j.markStarting(token))
        XCTAssertFalse(j.receiveFrame(token, at: 12, pts: 1))
        XCTAssertNil(j.snapshot().current)
    }
    func testOldFrameInferenceAndFailureCannotAffectRestart() {
        let j = MessageCameraJournal(); let old = start(j)
        j.stop(at: 11, reason: "Stop")
        let new = j.begin(at: 12)!
        XCTAssertFalse(j.receiveFrame(old, at: 13, pts: 1))
        XCTAssertFalse(j.finishInference(old, at: 13, people: 5))
        XCTAssertNil(j.stop(at: 13, reason: "Old failure", token: old))
        XCTAssertTrue(j.accepts(new)); XCTAssertEqual(j.snapshot().current?.frames, 0)
    }
    func testLifecycleTimestampCutsOffReceiptThatWinsLockFirst() {
        let j = MessageCameraJournal(); let token = start(j)
        XCTAssertTrue(j.receiveFrame(token, at: 11, pts: 1))
        XCTAssertTrue(j.finishInference(token, at: 11.1, people: 1))
        XCTAssertTrue(j.receiveFrame(token, at: 12, pts: 2))
        XCTAssertTrue(j.finishInference(token, at: 12.1, people: 3))
        // Boundary was captured before those second callbacks won the lock.
        j.deactivate(at: 12, reason: "Host background")
        let report = j.snapshot().completed.last!
        XCTAssertEqual(report.frames, 1); XCTAssertEqual(report.inferences, 1)
        XCTAssertEqual(report.people, 1); XCTAssertEqual(report.lastFrame, 11)
        XCTAssertFalse(j.receiveFrame(token, at: 20, pts: 3))
        _ = j.activate(at: 21)
        XCTAssertEqual(j.snapshot().completed.last?.frames, 1)
        XCTAssertFalse(j.finishInference(token, at: 22, people: 2))
    }
    func testReturnedForegroundFramesAreNotFrozenTrialEvidence() {
        let j = MessageCameraJournal(); let old = start(j)
        _ = j.receiveFrame(old, at: 11, pts: 1)
        j.deactivate(at: 12, reason: "Messages inactive")
        let new = start(j, at: 20)
        _ = j.receiveFrame(new, at: 21, pts: 8)
        XCTAssertEqual(j.snapshot().completed.last?.lastFrame, 11)
        XCTAssertEqual(j.snapshot().current?.lastFrame, 21)
    }
    func testSessionRunningDoesNotImplyFreshCameraOrPeopleResults() {
        let j = MessageCameraJournal(); let token = start(j)
        XCTAssertTrue(j.markStarting(token))
        XCTAssertFalse(j.snapshot().frameIsFresh(at: 10.5))
        _ = j.receiveFrame(token, at: 11, pts: 1)
        XCTAssertTrue(j.snapshot().frameIsFresh(at: 11.5))
        XCTAssertFalse(j.snapshot().inferenceIsFresh(at: 11.5))
        _ = j.finishInference(token, at: 11.1, people: 0)
        XCTAssertTrue(j.snapshot().inferenceIsFresh(at: 11.5))
        XCTAssertFalse(j.snapshot().frameIsFresh(at: 12.6))
        XCTAssertFalse(j.snapshot().inferenceIsFresh(at: 12.6))
        XCTAssertFalse(j.snapshot().frameIsFresh(at: 10))
    }
    func testDuplicateAndOutOfOrderFrameTimesAreRejected() {
        let j = MessageCameraJournal(); let token = start(j)
        XCTAssertFalse(j.receiveFrame(token, at: 9, pts: 1))
        XCTAssertFalse(j.receiveFrame(token, at: .nan, pts: 1))
        XCTAssertTrue(j.receiveFrame(token, at: 11, pts: 2))
        XCTAssertFalse(j.receiveFrame(token, at: 12, pts: 2))
        XCTAssertFalse(j.receiveFrame(token, at: 10.5, pts: 3))
        XCTAssertEqual(j.snapshot().current?.frames, 1)
    }
    func testResetCancelsPendingWorkAndRequiresNewAcknowledgment() {
        let j = MessageCameraJournal(); let old = start(j)
        j.reset(at: 11)
        XCTAssertFalse(j.accepts(old)); XCTAssertTrue(j.snapshot().completed.isEmpty)
        XCTAssertNil(j.begin(at: 12))
        j.acknowledge(activation: j.snapshot().activation)
        let new = j.begin(at: 13)!
        XCTAssertNotEqual(old, new)
        XCTAssertFalse(j.markStarting(old))
    }
    func testRepeatedStopDoesNotAppendOrRewriteFrozenTrial() {
        let j = MessageCameraJournal(); let token = start(j)
        j.stop(at: 11, reason: "Stop", token: token)
        j.deactivate(at: 12, reason: "Dismissed")
        j.stop(at: 13, reason: "Stop again")
        XCTAssertEqual(j.snapshot().completed.count, 1)
        XCTAssertEqual(j.snapshot().completed[0].ended, 11)
        XCTAssertEqual(j.snapshot().completed[0].endReason, "Stop")
    }
    func testRealNotificationBoundaryFreezesBeforeUIOrResumedFrames() {
        let j = MessageCameraJournal(); let token = start(j)
        _ = j.receiveFrame(token, at: 11, pts: 1)
        let center = NotificationCenter()
        let stop = Notification.Name("test.host.stop"), resume = Notification.Name("test.host.resume")
        let boundary = MessageCameraBoundary(center: center, journal: j, stopNames: [stop], resumeName: resume,
            now: { 12 }, stopped: { stopped in
                XCTAssertEqual(stopped, token)
                // An actual observer executes after the synchronous journal freeze.
                XCTAssertFalse(j.accepts(token))
                XCTAssertEqual(j.snapshot().completed.last?.frames, 1)
            }, resumed: {
                XCTAssertNil(j.snapshot().current)
                XCTAssertFalse(j.snapshot().acknowledged)
            })
        withExtendedLifetime(boundary) {
            center.post(name: stop, object: nil)
            XCTAssertFalse(j.receiveFrame(token, at: 13, pts: 2))
            _ = j.activate(at: 14) // visible callback while the host is still inactive
            XCTAssertFalse(j.snapshot().active)
            center.post(name: resume, object: nil)
            let activation = j.activate(at: 15)
            XCTAssertNil(j.begin(at: 16))
            j.acknowledge(activation: activation)
            XCTAssertNotNil(j.begin(at: 17))
        }
    }
    func testBoundedMetadataStopsTrialAndInvalidatesLaterWork() {
        let j = MessageCameraJournal(); let token = start(j)
        for index in 0..<10_800 {
            XCTAssertTrue(j.receiveFrame(token, at: 11 + Double(index), pts: Double(index)))
        }
        XCTAssertFalse(j.receiveFrame(token, at: 11_000, pts: 10_800))
        XCTAssertFalse(j.accepts(token))
        XCTAssertEqual(j.snapshot().completed.last?.frames, 10_800)
        XCTAssertTrue(j.snapshot().completed.last?.endReason?.contains("metadata limit") == true)
        XCTAssertTrue(j.snapshot().phase.contains("metadata limit"))
        XCTAssertNil(j.snapshot().current)
        XCTAssertFalse(j.finishInference(token, at: 11_001, people: 1))
    }
    func testFrozenHistoryIsBoundedWithoutReusingRunIdentity() {
        let j = MessageCameraJournal()
        var tokens: [MessageCameraToken] = []
        for index in 0..<8 {
            let token = start(j, at: Double(index * 10))
            tokens.append(token)
            j.stop(at: Double(index * 10 + 1), reason: "Stopped")
        }
        XCTAssertEqual(j.snapshot().completed.count, 6)
        XCTAssertEqual(j.snapshot().completed.first?.token, tokens[2])
        XCTAssertNotEqual(tokens[0], tokens[7])
    }
    func testCameraAndStopMustBothRemainInsideVisibleRegion() {
        let viewport = CGRect(x: 0, y: 0, width: 360, height: 240)
        let preview = CGRect(x: 14, y: 50, width: 332, height: 100)
        let stop = CGRect(x: 180, y: 170, width: 166, height: 44)
        XCTAssertTrue(MessageCameraVisibility.permitsScanning(preview: preview, stop: stop, viewport: viewport))
        XCTAssertFalse(MessageCameraVisibility.permitsScanning(preview: preview.offsetBy(dx: 0, dy: -60), stop: stop, viewport: viewport))
        XCTAssertFalse(MessageCameraVisibility.permitsScanning(preview: preview, stop: stop.offsetBy(dx: 0, dy: 80), viewport: viewport))
        XCTAssertFalse(MessageCameraVisibility.permitsScanning(preview: CGRect(x: 14, y: 50, width: 332, height: 20), stop: stop, viewport: viewport))
        XCTAssertFalse(MessageCameraVisibility.permitsScanning(preview: preview, stop: stop, viewport: .null))
    }
}
