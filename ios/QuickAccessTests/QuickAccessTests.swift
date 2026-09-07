import AppIntents
import XCTest
@testable import NoBonk

final class QuickAccessTests: XCTestCase {
    func testOpeningCannotAcknowledgeFirstUseNotice() async throws {
        let defaults = UserDefaults.standard
        let key = "safetyNoticeVersion"
        let original = defaults.object(forKey: key)
        defer { if let original { defaults.set(original, forKey: key) } else { defaults.removeObject(forKey: key) } }
        defaults.set(0, forKey: key)
        _ = try await OpenNoBonkIntent().perform()
        XCTAssertEqual(defaults.integer(forKey: key), 0)
        let gate = SafetyGate(acknowledgedVersion: defaults.integer(forKey: key))
        XCTAssertEqual(gate.screen, .fullNotice)
    }

    func testOpeningPreservesReturningUsersReminder() async throws {
        var gate = SafetyGate(acknowledgedVersion: SafetyGate.noticeVersion)
        gate.continueFromReminder()
        gate.leaveForeground()
        _ = try await OpenNoBonkIntent().perform()
        XCTAssertEqual(gate.screen, .reminder)
        XCTAssertEqual(OpenNoBonkIntent().target, .setup)
    }

    func testQuickAccessRequiresLocalUnlockAndForeground() {
        XCTAssertEqual(OpenNoBonkIntent.authenticationPolicy, .requiresLocalDeviceAuthentication)
        XCTAssertTrue(OpenNoBonkIntent.openAppWhenRun)
    }
}
