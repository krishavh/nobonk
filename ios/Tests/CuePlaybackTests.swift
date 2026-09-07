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
        let output = FakeCueOutput(); output.duration = 0.12
        let queue = DispatchQueue(label: "test.cue.replacement")
        let controller = CuePlaybackController(queue: queue) { output }
        let first = expectation(description: "first")
        controller.play { ok in XCTAssertTrue(ok); first.fulfill() }
        wait(for: [first], timeout: 2)
        let second = expectation(description: "second")
        queue.asyncAfter(deadline: .now() + 0.08) { controller.play { _ in second.fulfill() } }
        wait(for: [second], timeout: 2)
        let beforeSecondEnd = expectation(description: "old completion passed")
        queue.asyncAfter(deadline: .now() + 0.11) {
            XCTAssertEqual(output.snapshot().filter { $0 == "stop" }.count, 0)
            beforeSecondEnd.fulfill()
        }
        wait(for: [beforeSecondEnd], timeout: 2)
        controller.stop(); queue.sync {}
        XCTAssertEqual(output.snapshot().last, "stop")
    }
}
