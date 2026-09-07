import CoreMedia
import CoreVideo
import XCTest
@testable import ProbeJournal

final class ProbeJournalTests: XCTestCase {
    private func frame(_ seconds: Double) throws -> CMSampleBuffer {
        var pixel: CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault, 2, 2, kCVPixelFormatType_32BGRA, nil, &pixel), kCVReturnSuccess)
        let image = try XCTUnwrap(pixel)
        var format: CMVideoFormatDescription?
        XCTAssertEqual(CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: image, formatDescriptionOut: &format), noErr)
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: 15), presentationTimeStamp: CMTime(seconds: seconds, preferredTimescale: 600), decodeTimeStamp: .invalid)
        var buffer: CMSampleBuffer?
        XCTAssertEqual(CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: image, formatDescription: try XCTUnwrap(format), sampleTiming: &timing, sampleBufferOut: &buffer), noErr)
        return try XCTUnwrap(buffer)
    }
    func testStopRejectsQueuedFramesAndStateThenAllowsNewSession() throws {
        let journal = ProbeJournal()
        let old = journal.begin()
        journal.append(try frame(1), token: old, now: 1)
        journal.invalidate(message: "Stop")
        journal.append(try frame(2), token: old, now: 2)
        journal.update(old) { $0.status = "Late running callback" }
        XCTAssertNil(journal.takeLatest())
        XCTAssertEqual(journal.snapshot().frameCount, 1)
        XCTAssertEqual(journal.snapshot().status, "Stop")
        let current = journal.begin()
        journal.append(try frame(3), token: old, now: 3)
        XCTAssertEqual(journal.snapshot().frameCount, 0)
        journal.append(try frame(4), token: current, now: 4)
        XCTAssertEqual(journal.takeLatest()?.0, current)
        XCTAssertEqual(journal.snapshot().frameCount, 1)
    }
    func testFrozenFramesDoNotCountAndMailboxOnlyRetainsLatest() throws {
        let journal = ProbeJournal()
        let token = journal.begin()
        journal.append(try frame(2), token: token, now: 2)
        journal.append(try frame(2), token: token, now: 3)
        journal.append(try frame(1), token: token, now: 4)
        XCTAssertEqual(journal.snapshot().frameCount, 1)
        journal.append(try frame(5), token: token, now: 5)
        let sample = try XCTUnwrap(journal.takeLatest()?.1)
        XCTAssertEqual(CMSampleBufferGetPresentationTimeStamp(sample).seconds, 5)
        XCTAssertNil(journal.takeLatest())
        XCTAssertEqual(journal.snapshot().longestGap, 3)
    }
    func testBackgroundReportSeparatesGraceFramesAndFreezesAgeAtReturn() throws {
        let journal = ProbeJournal()
        let token = journal.begin()
        journal.append(try frame(9), token: token, now: 9)
        journal.background(true, now: 10)
        journal.append(try frame(10.2), token: token, now: 10.2)
        journal.append(try frame(11.1), token: token, now: 11.1)
        journal.background(false, now: 25)
        journal.append(try frame(25.1), token: token, now: 25.1)
        let report = journal.snapshot()
        XCTAssertEqual(report.backgroundFrames, 2)
        XCTAssertEqual(report.backgroundFramesAfterOneSecond, 1)
        XCTAssertEqual(report.lastBackgroundDuration, 15)
        XCTAssertEqual(report.frameAgeAtReturn, 13.9, accuracy: 0.0001)
        XCTAssertEqual(report.lastPTS, 25.1, accuracy: 0.0001)
        XCTAssertFalse(report.inBackground)
    }
    func testDelayedPiPStopCannotStopNewTrialOrAcceptOldStartFailure() {
        let journal = ProbeJournal()
        let gate = PiPTrialGate()
        let firstController = NSObject(), secondController = NSObject()
        let firstGeneration = journal.begin()
        XCTAssertTrue(gate.install(ObjectIdentifier(firstController), generation: firstGeneration))
        let oldBinding = gate.binding(for: ObjectIdentifier(firstController))!
        XCTAssertTrue(gate.requestStart(oldBinding))
        XCTAssertTrue(gate.didStart(oldBinding))
        XCTAssertTrue(gate.requestStop())
        journal.invalidate(generation: firstGeneration, message: "User Stop")
        XCTAssertTrue(gate.teardownPending)
        XCTAssertFalse(gate.install(ObjectIdentifier(secondController), generation: 999))
        XCTAssertTrue(gate.finish(oldBinding))
        let nextGeneration = journal.begin()
        XCTAssertTrue(gate.install(ObjectIdentifier(secondController), generation: nextGeneration))
        // A callback already queued before finish must recheck this binding after
        // its UI hop. The old callback's conditional capture stop is also harmless.
        XCTAssertFalse(gate.accepts(oldBinding))
        XCTAssertFalse(gate.didStart(oldBinding))
        XCTAssertFalse(gate.finish(oldBinding)) // delayed failed-to-start/did-stop
        XCTAssertFalse(journal.invalidate(generation: oldBinding.generation, message: "Old PiP stopped"))
        XCTAssertTrue(journal.accepts(nextGeneration))
    }
    func testStopWhilePiPStartingWaitsAndUUIDRejectsReusedObjectIdentity() {
        let gate = PiPTrialGate(), controller = NSObject()
        let identity = ObjectIdentifier(controller)
        XCTAssertTrue(gate.install(identity, generation: 1))
        let old = gate.binding(for: identity)!
        XCTAssertTrue(gate.requestStart(old))
        XCTAssertTrue(gate.requestStop())
        XCTAssertFalse(gate.didStart(old)) // request was cancelled before did-start
        XCTAssertTrue(gate.teardownPending)
        XCTAssertTrue(gate.finish(old))
        XCTAssertTrue(gate.install(identity, generation: 1)) // retry / address reuse
        XCTAssertNotEqual(gate.binding(for: identity), old)
        XCTAssertFalse(gate.accepts(old))
        XCTAssertFalse(gate.finish(old))
    }
    func testOldProducerFrameAndRuntimeErrorCannotInvalidateNewGeneration() throws {
        let journal = ProbeJournal()
        let oldProducer = CaptureEpoch(generation: journal.begin(), journal: journal)
        oldProducer.append(try frame(1), now: 1)
        journal.invalidate(generation: oldProducer.generation, message: "Stopped")
        XCTAssertFalse(oldProducer.fail("Late old runtime error"))
        XCTAssertEqual(journal.snapshot().status, "Stopped")
        let newProducer = CaptureEpoch(generation: journal.begin(), journal: journal)
        // journal.begin has advanced while the old physical session might still
        // be draining its callbacks. Its immutable producer retains the OLD epoch.
        oldProducer.append(try frame(2), now: 2)
        XCTAssertFalse(oldProducer.fail("Deferred runtime error"))
        XCTAssertEqual(journal.snapshot().frameCount, 0)
        XCTAssertTrue(journal.accepts(newProducer.generation))
        newProducer.append(try frame(3), now: 3)
        XCTAssertEqual(journal.snapshot().frameCount, 1)
    }
    func testForegroundNotificationFreezesBeforeDelayedUIAndResumedFrames() throws {
        let journal = ProbeJournal(), center = NotificationCenter()
        let clock = TestClock()
        let background = Notification.Name("test.background"), foreground = Notification.Name("test.foreground")
        let observer = ProbeLifecycleBoundary(journal: journal, center: center,
            background: background, foreground: foreground, clock: { clock.value() },
            onTransition: { _ in /* UI deliberately does no work yet. */ })
        let token = journal.begin()
        journal.append(try frame(9), token: token, now: 9)
        clock.set(10); center.post(name: background, object: nil)
        journal.append(try frame(11), token: token, now: 11)
        clock.set(25); center.post(name: foreground, object: nil)
        // This frame arrives before the UI reads the return report, as a resumed
        // capture callback could. Notification delivery has already frozen it.
        journal.append(try frame(25.1), token: token, now: 25.1)
        let report = journal.snapshot()
        XCTAssertEqual(report.backgroundFrames, 1)
        XCTAssertEqual(report.backgroundFramesAfterOneSecond, 1)
        XCTAssertEqual(report.frameAgeAtReturn, 14)
        XCTAssertEqual(report.lastBackgroundDuration, 15)
        XCTAssertEqual(report.frameCount, 3)
        withExtendedLifetime(observer) {}
    }
}

private final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var now = 0.0
    func set(_ value: Double) { lock.lock(); now = value; lock.unlock() }
    func value() -> Double { lock.lock(); defer { lock.unlock() }; return now }
}
