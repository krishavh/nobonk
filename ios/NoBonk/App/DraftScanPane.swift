import SwiftUI

/// A local editor below the camera, not an inbox or a background messaging app.
/// Text belongs to the parent view's memory and leaves only via explicit handoff.
struct DraftScanPane: View {
    @Binding var text: String
    let canMessage: Bool
    let compact: Bool
    let onMessages: () -> Void
    let onShare: () -> Void
    let onHelp: () -> Void
    @FocusState private var editing: Bool
    @State private var showHelp = false
    private var hasText: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 6 : 12) {
            HStack(spacing: 8) {
                Label("Your draft", systemImage: "square.and.pencil").font(.headline)
                Spacer()
                Button { editing = false; onHelp(); showHelp = true } label: {
                    Image(systemName: "info.circle").frame(width: 44, height: 44)
                }.accessibilityLabel("How drafting and sending work")
                if editing {
                    Button("Done") { editing = false }.frame(minWidth: 44, minHeight: 44)
                        .accessibilityIdentifier("draft.dismissKeyboard")
                }
            }
            if !compact {
                Text("Write here with your camera in view. Pause to send when you’re ready.")
                    .font(.subheadline).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ZStack(alignment: .topLeading) {
                TextEditor(text: $text)
                    .font(.body).focused($editing).scrollContentBackground(.hidden)
                    .padding(8).frame(minHeight: 66)
                    .accessibilityLabel("Message draft")
                    .accessibilityIdentifier("draft.editor")
                if text.isEmpty {
                    Text("What would you like to say?")
                        .font(.body).foregroundStyle(.secondary)
                        .padding(.horizontal, 13).padding(.top, 16)
                        .allowsHitTesting(false).accessibilityHidden(true)
                }
            }.background(.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(.white.opacity(0.1)))
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { handoffButtons }
                VStack(alignment: .leading, spacing: 4) { handoffButtons }
            }
            if !compact {
                HStack {
                    Text("Kept in this session · no inbox access")
                        .font(.caption).foregroundStyle(.secondary)
                    Spacer(minLength: 4)
                    Button("Clear") { text = "" }.disabled(text.isEmpty).frame(minHeight: 44)
                        .accessibilityLabel("Clear message draft")
                }
            }
        }.padding(compact ? 10 : 16)
            .background(Color.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 22))
            .sheet(isPresented: $showHelp) {
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 18) {
                            Label("Draft here. Send when stopped.", systemImage: "square.and.pencil")
                                .font(.title2.bold())
                            Text("The camera stays visible above this editor. Keep looking up; NoBonk can miss hazards. Stop somewhere safe before sending.")
                            Text("Messages opens Apple’s composer with your draft. Share opens available sharing options, which may include WhatsApp if installed. You choose the recipient and send. Both pause scanning; return and tap Start to scan again.")
                            Text("NoBonk cannot display your Messages, WhatsApp or Instagram inbox from their native apps. NoBonk doesn’t save or sync drafts; they stay in memory until cleared or the app process ends. Your keyboard and the app you share with have their own privacy policies.")
                            Button("Clear this draft") { text = ""; showHelp = false }
                                .disabled(text.isEmpty).buttonStyle(.bordered)
                        }.padding(24)
                    }.navigationTitle("Draft & scan")
                        .toolbar { Button("Done") { showHelp = false } }
                }
            }
    }

    @ViewBuilder private var handoffButtons: some View {
        if canMessage && !compact {
            Button { editing = false; onMessages() } label: {
                Label("Pause & Messages", systemImage: "message.fill")
                    .font(.subheadline.weight(.semibold)).frame(minHeight: 44)
            }.disabled(!hasText).accessibilityIdentifier("draft.messages")
        }
        Button { editing = false; onShare() } label: {
            Label("Pause & share", systemImage: "square.and.arrow.up")
                .font(.subheadline.weight(.semibold)).frame(minHeight: 44)
        }.disabled(!hasText).accessibilityIdentifier("draft.share")
    }
}
