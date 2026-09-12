import Foundation

struct SafetyGate {
    static let noticeVersion = 2
    enum Screen: Equatable { case fullNotice, reminder, scanning }
    private(set) var screen: Screen
    init(acknowledgedVersion: Int) {
        screen = acknowledgedVersion == Self.noticeVersion ? .reminder : .fullNotice
    }
    mutating func acceptFullNotice(checked: Bool) -> Bool {
        guard screen == .fullNotice, checked else { return false }
        screen = .scanning
        return true
    }
    mutating func continueFromReminder() {
        guard screen == .reminder else { return }
        screen = .scanning
    }
    mutating func leaveForeground() {
        if screen == .scanning { screen = .reminder }
    }
}

enum SafetyCopy {
    static let full = "NoBonk is an experimental student-built tool intended to offer extra awareness cues. It can miss or misidentify hazards, give late or incorrect alerts, and stop detecting when the camera is blocked or the app is interrupted. No alert does not mean the path is clear. Always look up and pay attention to your surroundings. Never rely on NoBonk for crossing roads, driving, cycling, or navigating dangerous areas. It is not a certified safety device or a substitute for your own judgment."
    static let acknowledgment = "I understand that NoBonk may fail to warn me and does not replace staying aware of my surroundings."
    static let reminder = "NoBonk can miss hazards and no alert does not mean the path is clear. Keep looking up — this is a helper, not a replacement for your attention."
}
