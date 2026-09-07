import XCTest
@testable import NoBonkCore

final class CoreTests: XCTestCase {
    func testFirstInstallAndUpgradeRequireCheckedNotice() {
        for version in [0,1,3] {
            var gate = SafetyGate(acknowledgedVersion: version)
            gate.continueFromReminder()
            XCTAssertEqual(gate.screen, .fullNotice)
            XCTAssertFalse(gate.acceptFullNotice(checked: false))
            XCTAssertTrue(gate.acceptFullNotice(checked: true))
            XCTAssertEqual(gate.screen, .scanning)
        }
    }
    func testEveryReturnRequiresReminder() {
        var gate = SafetyGate(acknowledgedVersion: 2)
        XCTAssertEqual(gate.screen, .reminder)
        gate.continueFromReminder()
        gate.leaveForeground()
        XCTAssertEqual(gate.screen, .reminder)
        gate.continueFromReminder()
        gate.leaveForeground()
        XCTAssertEqual(gate.screen, .reminder)
    }
    func testReadingDoesNotAcknowledge() {
        let gate = SafetyGate(acknowledgedVersion: 2)
        _ = SafetyCopy.full
        XCTAssertEqual(gate.screen, .reminder)
    }
    func testAlertRequiresPersistentCentralPersonAndCooldown() {
        var policy = DetectionPolicy()
        let center = PersonBox(id: 0, x: 0.4, y: 0.1, width: 0.2, height: 0.7, confidence: 0.9)
        XCTAssertEqual(policy.evaluate([center], time: 0), .none)
        XCTAssertEqual(policy.evaluate([center], time: 0.2), .none)
        XCTAssertEqual(policy.evaluate([center], time: 0.4), .personAhead)
        XCTAssertEqual(policy.evaluate([center], time: 1), .none)
        XCTAssertEqual(policy.evaluate([center], time: 3.5), .personAhead)
        XCTAssertEqual(policy.evaluate([], time: 4), .none)
        XCTAssertEqual(policy.evaluate([center], time: 7), .none)
    }
    func testInvalidAndPeripheralBoxesNeverAlert() {
        let invalid = PersonBox(id: 0, x: .nan, y: 0, width: 0.3, height: 0.8, confidence: 1)
        let peripheral = PersonBox(id: 1, x: 0.01, y: 0.1, width: 0.1, height: 0.8, confidence: 1)
        var policy = DetectionPolicy()
        for i in 0..<10 { XCTAssertEqual(policy.evaluate([invalid,peripheral], time: Double(i)), .none) }
    }
    func testStoppedAndSupersededCameraCallbacksAreRejected() {
        let gate = CaptureGeneration()
        let first = gate.begin()
        XCTAssertTrue(gate.accepts(first))
        gate.stop()
        XCTAssertFalse(gate.accepts(first))
        let second = gate.begin()
        XCTAssertFalse(gate.accepts(first))
        XCTAssertTrue(gate.accepts(second))
        gate.stop()
        XCTAssertFalse(gate.accepts(second))
    }
    func testFailedStartCanBeginAnotherSession() {
        let gate = CaptureGeneration()
        let failed = gate.begin()
        gate.stop()
        let retry = gate.begin()
        XCTAssertTrue(gate.accepts(retry))
        XCTAssertFalse(gate.accepts(failed))
    }
    func testCameraInvalidationAcrossQueues() {
        let gate = CaptureGeneration()
        let captured = gate.begin()
        let done = expectation(description: "late camera result")
        gate.stop()
        _ = gate.begin()
        DispatchQueue.global().async {
            XCTAssertFalse(gate.accepts(captured))
            done.fulfill()
        }
        wait(for: [done], timeout: 2)
    }
    func testAlertToneHasValidBoundedPcmPayload() {
        let bytes = [UInt8](AlertTone.wav())
        XCTAssertEqual(String(bytes: bytes[0..<4], encoding: .ascii), "RIFF")
        XCTAssertEqual(String(bytes: bytes[8..<12], encoding: .ascii), "WAVE")
        XCTAssertEqual(bytes.count, 44 + 4410 * 2)
        let samples = stride(from: 44, to: bytes.count, by: 2).map {
            Int16(bitPattern: UInt16(bytes[$0]) | UInt16(bytes[$0 + 1]) << 8)
        }
        XCTAssertEqual(samples.first, 0)
        XCTAssertEqual(samples.last, 0)
        XCTAssertTrue(samples.contains { $0 > 1000 })
        XCTAssertLessThanOrEqual(samples.map { abs(Int($0)) }.max()!, 8000)
    }

    func testCompactPreviewBoxesRespectLetterboxing() {
        let box = PersonBox(id: 0, x: 0.4, y: 0.1, width: 0.2, height: 0.7, confidence: 0.9)
        let rect = PreviewGeometry.rect(for: box, width: 400, height: 400, imageAspect: 9.0 / 16)
        XCTAssertEqual(rect.minX, 177.5, accuracy: 0.001)
        XCTAssertEqual(rect.minY, 40, accuracy: 0.001)
        XCTAssertEqual(rect.width, 45, accuracy: 0.001)
        XCTAssertEqual(rect.height, 280, accuracy: 0.001)
        let expanded = PreviewGeometry.fittedRect(width: 400, height: 800, imageAspect: 9.0 / 16)
        XCTAssertEqual(expanded.width, 400)
        XCTAssertEqual(expanded.height, 400 * 16.0 / 9, accuracy: 0.001)
        XCTAssertEqual(expanded.midY, 400, accuracy: 0.001)
    }
    func testPreviewRejectsInvalidGeometry() {
        XCTAssertEqual(PreviewGeometry.fittedRect(width: .nan, height: 400, imageAspect: 1), .zero)
        XCTAssertEqual(PreviewGeometry.fittedRect(width: 400, height: 0, imageAspect: 1), .zero)
        XCTAssertEqual(PreviewGeometry.fittedRect(width: 400, height: 400, imageAspect: .infinity), .zero)
    }
    func testSensitivityChangesWhichPersonCanCue() {
        let person = PersonBox(id: 0, x: 0.4, y: 0.1, width: 0.2, height: 0.38, confidence: 0.9)
        for sensitivity in AlertSensitivity.allCases {
            var policy = DetectionPolicy()
            _ = policy.evaluate([person], time: 0, sensitivity: sensitivity)
            _ = policy.evaluate([person], time: 0.2, sensitivity: sensitivity)
            XCTAssertEqual(policy.evaluate([person], time: 0.4, sensitivity: sensitivity),
                           sensitivity == .earlier ? .personAhead : .none)
        }
    }
    func testCadenceAdaptsToMeasuredWorkWithoutBursting() {
        var cadence = AnalysisCadence()
        XCTAssertTrue(cadence.begin(at: 0))
        XCTAssertFalse(cadence.begin(at: 0.07))
        XCTAssertTrue(cadence.begin(at: 0.084))
        cadence.complete(duration: 0.2)
        cadence.complete(duration: 0.2)
        XCTAssertEqual(cadence.interval, 0.3, accuracy: 0.001)
        XCTAssertFalse(cadence.begin(at: 0.2))
        XCTAssertTrue(cadence.begin(at: 0.4))
        cadence.complete(duration: .nan)
        XCTAssertEqual(cadence.interval, 0.3, accuracy: 0.001)
        cadence.reset()
        XCTAssertTrue(cadence.begin(at: 0.41))
        XCTAssertEqual(cadence.interval, 1.0 / 12, accuracy: 0.001)
    }
    func testCadenceRespondsToHeatAndLowPowerMode() {
        let cadence = AnalysisCadence()
        XCTAssertEqual(cadence.interval(pressure: .normal, lowPower: true), 0.2)
        XCTAssertEqual(cadence.interval(pressure: .hot, lowPower: false), 0.35)
        XCTAssertEqual(cadence.interval(pressure: .critical, lowPower: false), 0.75)
        var slow = AnalysisCadence()
        slow.complete(duration: 0.8)
        slow.complete(duration: 0.8)
        XCTAssertEqual(slow.interval(pressure: .hot, lowPower: true), 1.2, accuracy: 0.001)
    }

    func testColdVisionInitializationDoesNotThrottleFollowingFastFrames() {
        var cadence = AnalysisCadence()
        cadence.complete(duration: 2.0)
        XCTAssertEqual(cadence.interval, 1.0 / 12, accuracy: 0.001)
        cadence.complete(duration: 0.02)
        XCTAssertEqual(cadence.interval, 1.0 / 12, accuracy: 0.001)
        cadence.complete(duration: 0.02)
        XCTAssertEqual(cadence.averageDuration, 0.02, accuracy: 0.001)
    }

}
