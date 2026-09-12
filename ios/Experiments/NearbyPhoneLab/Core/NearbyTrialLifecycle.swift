import Foundation

/// Notification delivery establishes the boundary before any asynchronous UI
/// work. ScenePhase alone arrives too late to protect a frozen background report.
public final class NearbyTrialLifecycle: @unchecked Sendable {
    private let center: NotificationCenter
    private let observations: [NSObjectProtocol]

    public init(journal: NearbyTrialJournal, center: NotificationCenter = .default,
                background: Notification.Name, foreground: Notification.Name,
                clock: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.center = center
        observations = [
            center.addObserver(forName: background, object: nil, queue: nil) { _ in
                journal.background(at: clock())
            },
            center.addObserver(forName: foreground, object: nil, queue: nil) { _ in
                journal.foreground(at: clock())
            }
        ]
    }

    deinit { for observation in observations { center.removeObserver(observation) } }
}
