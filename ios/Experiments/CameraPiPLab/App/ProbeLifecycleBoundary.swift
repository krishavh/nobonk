import Foundation

/// Notification delivery freezes the journal synchronously. UI work is a separate
/// callback and may be delayed without changing the captured lifecycle boundary.
final class ProbeLifecycleBoundary: @unchecked Sendable {
    private let center: NotificationCenter
    private let observations: [NSObjectProtocol]
    init(journal: ProbeJournal, center: NotificationCenter = .default,
         background: Notification.Name, foreground: Notification.Name,
         clock: @escaping @Sendable () -> Double = { ProcessInfo.processInfo.systemUptime },
         onTransition: @escaping @Sendable (Bool) -> Void) {
        self.center = center
        observations = [(background, true), (foreground, false)].map { name, entering in
            center.addObserver(forName: name, object: nil, queue: nil) { _ in
                journal.background(entering, now: clock())
                onTransition(entering)
            }
        }
    }
    deinit { for observation in observations { center.removeObserver(observation) } }
}
