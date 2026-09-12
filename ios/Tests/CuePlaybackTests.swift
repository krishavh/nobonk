import XCTest
@testable import NoBonkCore

private final class FakeCueOutput: CueAudioOutput, @unchecked Sendable {
    let lock = NSLock()
    var events: [String] = []
    var duration: TimeInterval = 0.1
    var activationEntered: (() -> Void)?
    var activationWait: DispatchSemaphore?
    var failActivation = false
    var allowPlayback = true
    func append(_ event: String) { lock.lock(); events.append(event); lock.unlock() }
    func snapshot() -> [String] { lock.lock(); defer { lock.unlock() }; return events }
    func prepare() throws { append("prepare") }
    func activate() throws {
        append("activate"); activationEntered?(); activationWait?.wait()
        if failActivation { throw NSError(domain: "test", code: 1) }
    }
    func play() -> Bool { append("play"); return allowPlayback }
    func stopAndDeactivate() { append("stop") }
}

final class CuePlaybackTests: XCTestCase {
    func testStopDuringBlockingActivationNeverPlays() {
        let output = FakeCueOutput()
        let entered = expectation(description: "activation entered")
        let activationBarrier = DispatchSemaphore(value: 0)
        output.activationEntered = { entered.fulfill() }; output.activationWait = activationBarrier
        let queue = DispatchQueue(label: "test.cue.stop")
        let controller = CuePlaybackController(queue: queue) { output }
        controller.play { _ in XCTFail("Canceled cue must not publish") }
        wait(for: [entered], timeout: 2)
        controller.stop(); activationBarrier.signal()
        queue.sync {}
        XCTAssertFalse(output.snapshot().contains("play"))
        XCTAssertEqual(output.snapshot().last, "stop")
    }
    func testActivationFailureDoesNotClaimPlaybackAndCleansUp() {
        let output = FakeCueOutput(); output.failActivation = true
        let result = expectation(description: "failure reported")
        let queue = DispatchQueue(label: "test.cue.failure")
        let controller = CuePlaybackController(queue: queue) { output }
        controller.play { ok in XCTAssertFalse(ok); result.fulfill() }
        wait(for: [result], timeout: 2); queue.sync {}
        XCTAssertEqual(output.snapshot(), ["prepare", "activate", "stop"])
    }
    func testFailedPlayRestoresAudio() {
        let output = FakeCueOutput(); output.allowPlayback = false
        let result = expectation(description: "play refused")
        let queue = DispatchQueue(label: "test.cue.refused")
        let controller = CuePlaybackController(queue: queue) { output }
        controller.play { ok in XCTAssertFalse(ok); result.fulfill() }
        wait(for: [result], timeout: 2); queue.sync {}
        XCTAssertEqual(output.snapshot(), ["prepare", "activate", "play", "stop"])
    }
    func testOldCueCompletionCannotStopNewCue() {
        // Run delayed callbacks manually: even a callback dequeued before cancel
        // must not stop a later cue. Wall-clock sleeps make this race test flaky.
        final class Pending: @unchecked Sendable { var bodies: [@Sendable () -> Void] = [] }
        let pending = Pending()
        let output = FakeCueOutput()
        let queue = DispatchQueue(label: "test.cue.replacement")
        let controller = CuePlaybackController(queue: queue, scheduleFinish: { _, body in
            pending.bodies.append(body)
            return DispatchWorkItem(block: body)
        }) { output }
        controller.play(); queue.sync {}
        controller.play(); queue.sync {}
        queue.sync {
            XCTAssertEqual(pending.bodies.count, 2)
            pending.bodies[0]()
            XCTAssertEqual(output.snapshot().filter { $0 == "stop" }.count, 0)
            pending.bodies[1]()
            XCTAssertEqual(output.snapshot().filter { $0 == "stop" }.count, 1)
        }
        controller.stop(); queue.sync {}
        XCTAssertEqual(output.snapshot().last, "stop")
    }
}
