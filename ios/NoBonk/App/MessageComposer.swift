import MessageUI
import SwiftUI

/// Apple's own compose UI. NoBonk does not read inboxes, recipients or message text.
/// Sending remains an explicit action inside the system composer.
struct MessageComposer: UIViewControllerRepresentable {
    let finished: () -> Void
    final class Coordinator: NSObject, MFMessageComposeViewControllerDelegate {
        let finished: () -> Void
        init(finished: @escaping () -> Void) { self.finished = finished }
        func messageComposeViewController(_ controller: MFMessageComposeViewController, didFinishWith result: MessageComposeResult) {
            finished()
        }
    }
    func makeCoordinator() -> Coordinator { Coordinator(finished: finished) }
    func makeUIViewController(context: Context) -> MFMessageComposeViewController {
        let controller = MFMessageComposeViewController()
        controller.messageComposeDelegate = context.coordinator
        return controller
    }
    func updateUIViewController(_ controller: MFMessageComposeViewController, context: Context) {}
}
