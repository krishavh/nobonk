import MessageUI
import SwiftUI

/// Apple's own compose UI, prefilled with the draft the user chose to hand off.
/// NoBonk cannot read inboxes or changes made inside this system composer.
/// Sending remains an explicit action inside the system composer.
struct MessageComposer: UIViewControllerRepresentable {
    var body = ""
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
        controller.body = body
        controller.messageComposeDelegate = context.coordinator
        return controller
    }
    func updateUIViewController(_ controller: MFMessageComposeViewController, context: Context) {}
}

/// The user chooses the recipient/app and confirms sending in Apple's share UI.
/// Presenting this sheet never sends text automatically.
struct MessageShareSheet: UIViewControllerRepresentable {
    let text: String
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [text], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
