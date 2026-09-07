import Foundation

/// NotificationCenter's synchronous delivery closes the journal at host-event
/// receipt, before an actor hop or a queued camera callback can resume the UI.
final class MessageCameraBoundary: @unchecked Sendable {
    private let center: NotificationCenter
    private var observers: [NSObjectProtocol] = []
    init(center: NotificationCenter = .default, journal: MessageCameraJournal,
         stopNames: [Notification.Name], resumeName: Notification.Name,
         now: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
         stopped: @escaping @Sendable (MessageCameraToken?) -> Void,
         resumed: @escaping @Sendable () -> Void) {
        self.center = center
        for name in stopNames {
            observers.append(center.addObserver(forName: name, object: nil, queue: nil) { _ in
                let receipt = now()
                let token = journal.suspendHost(at: receipt, reason: "Messages host inactive — camera stopped")
                stopped(token)
            })
        }
        observers.append(center.addObserver(forName: resumeName, object: nil, queue: nil) { _ in
            journal.resumeHost()
            resumed() // explicit UI activation/acknowledgment still required
        })
    }
    deinit { observers.forEach(center.removeObserver) }
}
