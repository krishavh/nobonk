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
}
