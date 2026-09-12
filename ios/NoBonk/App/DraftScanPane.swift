import SwiftUI

/// User-selected text stays in parent-owned session memory. No inbox or clipboard polling.
struct DraftScanPane: View {
    @Binding var text: String
    let canMessage: Bool
    let compact: Bool
    let onMessages: () -> Void
    let onShare: () -> Void
    let onHelp: () -> Void

    @FocusState private var editing: Bool
    @State private var showHelp = false
    @State private var mode: Mode

    enum Mode: String, CaseIterable {
        case write = "Write"
        case read = "Read"
    }

    init(text: Binding<String>, canMessage: Bool, compact: Bool,
         onMessages: @escaping () -> Void, onShare: @escaping () -> Void,
         onHelp: @escaping () -> Void, initialMode: Mode = .write) {
        self._text = text
        self.canMessage = canMessage
        self.compact = compact
        self.onMessages = onMessages
        self.onShare = onShare
        self.onHelp = onHelp
        self._mode = State(initialValue: initialMode)
    }

    private var hasText: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 6 : 12) {
            if !compact {
                HStack(spacing: 8) {
                    Label(mode == .write ? "Your draft" : "Read selected text", systemImage: mode == .write ? "square.and.pencil" : "text.book.closed").font(.headline)
                    Spacer()
                    Button { editing = false; onHelp(); showHelp = true } label: {
                        Image(systemName: "info.circle").frame(width: 44, height: 44)
                    }.accessibilityLabel("How drafting and sending work")
                    if editing && mode == .write {
                        Button("Done") { editing = false }.frame(minWidth: 44, minHeight: 44)
                            .accessibilityIdentifier("draft.dismissKeyboard")
                    }
                }
                Text(mode == .write ? "Write here with your camera in view. Pause to send when you're ready." : "Paste text below to read. NoBonk cannot access live inboxes.")
                    .font(.subheadline).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            ZStack(alignment: .topLeading) {
                if mode == .write {
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
                } else {
                    ScrollView {
                        Text(text.isEmpty ? "No text pasted yet." : text)
                            .font(.body)
                            .textSelection(.enabled)
                            .padding(8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(minHeight: 66, maxHeight: .infinity)
                    .accessibilityLabel("Pasted text for reading")
                    .accessibilityIdentifier("draft.reader")
                }
            }.background(.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(.white.opacity(0.1)))

            if compact {
                HStack(spacing: 8) {
                    Picker("Text mode", selection: $mode) {
                        ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 120, height: 44)
                    .accessibilityIdentifier("draft.mode")

                    Spacer(minLength: 0)

                    if mode == .read {
                        PasteButton(payloadType: String.self) { values in
                            let pasted = values.filter { !$0.isEmpty }.joined(separator: "\n\n")
                            guard !pasted.isEmpty else { return }
                            if text.isEmpty {
                                text = pasted
                            } else {
                                text += "\n\n" + pasted
                            }
                        }
                        .labelStyle(.iconOnly)
                        .frame(width: 44, height: 44)
                        .accessibilityLabel("Paste text from clipboard")
                        .accessibilityIdentifier("draft.paste")

                        Button { text = "" } label: {
                            Image(systemName: "trash").frame(width: 44, height: 44)
                        }.disabled(text.isEmpty)
                        .accessibilityLabel("Clear message draft")
                        .accessibilityIdentifier("draft.clear")

                        Button { editing = false; onHelp(); showHelp = true } label: {
                            Image(systemName: "info.circle").frame(width: 44, height: 44)
                        }.accessibilityLabel("How drafting and sending work")
                    } else {
                        Button { editing = false; onShare() } label: {
                            Image(systemName: "square.and.arrow.up").frame(width: 44, height: 44)
                        }.disabled(!hasText)
                        .accessibilityLabel("Pause scanning and share draft")
                        .accessibilityIdentifier("draft.share")

                        if editing && mode == .write {
                            Button("Done") { editing = false }.frame(minWidth: 44, minHeight: 44)
                                .accessibilityIdentifier("draft.dismissKeyboard")
                        } else {
                            Button { editing = false; onHelp(); showHelp = true } label: {
                                Image(systemName: "info.circle").frame(width: 44, height: 44)
                            }.accessibilityLabel("How drafting and sending work")
                        }
                    }
                }
            } else {
                HStack(spacing: 8) {
                    Picker("Text mode", selection: $mode) {
                        ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 120, height: 44)
                    .accessibilityIdentifier("draft.mode")

                    Spacer()

                    if mode == .read {
                        PasteButton(payloadType: String.self) { values in
                            let pasted = values.filter { !$0.isEmpty }.joined(separator: "\n\n")
                            guard !pasted.isEmpty else { return }
                            if text.isEmpty {
                                text = pasted
                            } else {
                                text += "\n\n" + pasted
                            }
                        }
                        .labelStyle(.iconOnly)
                        .frame(width: 44, height: 44)
                        .accessibilityLabel("Paste text from clipboard")
                        .accessibilityIdentifier("draft.paste")

                        Button { text = "" } label: {
                            Image(systemName: "trash").frame(width: 44, height: 44)
                        }.disabled(text.isEmpty)
                        .accessibilityLabel("Clear message draft")
                        .accessibilityIdentifier("draft.clear")

                        Button { editing = false; onHelp(); showHelp = true } label: {
                            Image(systemName: "info.circle").frame(width: 44, height: 44)
                        }.accessibilityLabel("How drafting and sending work")
                    } else {
                        Button { editing = false; onShare() } label: {
                            Image(systemName: "square.and.arrow.up").frame(width: 44, height: 44)
                        }.disabled(!hasText)
                        .accessibilityLabel("Pause scanning and share draft")
                        .accessibilityIdentifier("draft.share")

                        if editing && mode == .write {
                            Button("Done") { editing = false }.frame(minWidth: 44, minHeight: 44)
                                .accessibilityIdentifier("draft.dismissKeyboard")
                        } else {
                            Button { editing = false; onHelp(); showHelp = true } label: {
                                Image(systemName: "info.circle").frame(width: 44, height: 44)
                            }.accessibilityLabel("How drafting and sending work")
                        }
                    }
                }
            }

            if !compact && canMessage && mode == .write {
                Button { editing = false; onMessages() } label: {
                    Label("Pause & Messages", systemImage: "message.fill")
                        .font(.subheadline.weight(.semibold)).frame(minHeight: 44)
                }.disabled(!hasText).accessibilityIdentifier("draft.messages")
            }

            if !compact {
                HStack {
                    Text(mode == .write ? "Kept in this session · no inbox access" : "Selected text only · not live inbox")
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
                            Text("Messages opens Apple's composer with your draft. Share opens available sharing options, which may include WhatsApp if installed. You choose the recipient and send. Both pause scanning; return and tap Start to scan again.")
                            Text("NoBonk cannot display your Messages, WhatsApp or Instagram inbox from their native apps. NoBonk doesn't save or sync drafts; they stay in memory until cleared or the app process ends. Your keyboard and the app you share with have their own privacy policies.")
                            Text("Read Mode: Paste text you have copied from other apps to read it here. NoBonk does not automatically read your clipboard or access live inboxes.")
                            Button("Clear this draft") { text = ""; showHelp = false }
                                .disabled(text.isEmpty).buttonStyle(.bordered)
                        }.padding(24)
                    }.navigationTitle("Draft & scan")
                        .toolbar { Button("Done") { showHelp = false } }
                }
            }
            .onChange(of: mode) { _, _ in editing = false }
    }
}
