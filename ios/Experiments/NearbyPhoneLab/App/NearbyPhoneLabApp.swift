import MultipeerConnectivity
import SwiftUI

@main
struct NearbyPhoneLabApp: App {
    @StateObject private var model = NearbyLabModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            NearbyLabView(model: model)
                .preferredColorScheme(.dark)
                .onChange(of: scenePhase) { _, value in model.sceneChanged(value) }
        }
    }
}

private struct NearbyLabView: View {
    @ObservedObject var model: NearbyLabModel
    @State private var pendingInvite: MCPeerID?
    @State private var trialKind: NearbyTrialJournal.Kind = .appSwitch
    private let lime = Color(red: 0.77, green: 0.95, blue: 0.42)
    private let muted = Color(red: 0.62, green: 0.73, blue: 0.71)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack {
                    Label("NO BONK / RESEARCH", systemImage: "wave.3.right")
                        .font(.caption.bold()).tracking(2).foregroundStyle(lime)
                    Spacer()
                    Text("01").font(.caption.monospaced()).foregroundStyle(muted)
                }
                Text("A little room\nbetween us.")
                    .font(.system(size: 43, weight: .semibold, design: .rounded)).tracking(-1.5)
                Text("An experiment asking whether two paired iPhones can keep measuring their distance while another app is open.")
                    .font(.body).foregroundStyle(muted)
                Label("Research only. This measures one consenting phone. It cannot detect walls, unknown people or hazards.", systemImage: "info.circle")
                    .font(.subheadline).padding(16).background(.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 18))

                measurement
                Text(model.status).font(.subheadline).foregroundStyle(muted).accessibilityIdentifier("sessionStatus")
                trialEvidence

                if model.active {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("THIS PHONE").font(.caption.bold()).tracking(2).foregroundStyle(muted)
                        Text(model.localName).font(.title3.monospaced().bold()).textSelection(.enabled)
                        Text("Partner: \(model.partner)").font(.subheadline)
                        Text("Pairing link: \(model.transportConnected ? "connected" : "disconnected / waiting"). UWB has its own session.")
                            .font(.caption).foregroundStyle(muted)
                    }
                    if !model.peers.isEmpty {
                        VStack(spacing: 10) {
                            ForEach(model.peers, id: \.self) { peer in
                                Button { pendingInvite = peer } label: {
                                    HStack { Image(systemName: "iphone"); Text(peer.displayName); Spacer(); Image(systemName: "arrow.up.right") }
                                        .padding(16).background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 14))
                                }.tint(.white)
                            }
                        }
                    }
                    Button(action: model.enableBackground) {
                        Label(model.backgroundEnabled ? "Live Activity is running" : "Enable background experiment", systemImage: "rectangle.topthird.inset.filled")
                            .frame(maxWidth: .infinity).padding(12)
                    }.buttonStyle(.borderedProminent).tint(lime).foregroundStyle(.black)
                        .disabled(!model.canEnableBackground)
                    Text("Enable on both phones while this app is visible. Then switch apps. A visible Live Activity alone does not prove that ranging continues.")
                        .font(.caption).foregroundStyle(muted)
                    Button(role: .destructive) { model.stop() } label: {
                        Label("Stop experiment", systemImage: "stop.fill").frame(maxWidth: .infinity).padding(12)
                    }.buttonStyle(.bordered)
                } else {
                    VStack(spacing: 12) {
                        Button { model.begin(hosting: true) } label: {
                            Label("Create a pairing session", systemImage: "plus.circle").frame(maxWidth: .infinity).padding(12)
                        }.buttonStyle(.borderedProminent).tint(lime).foregroundStyle(.black)
                        Button { model.begin(hosting: false) } label: {
                            Label("Find my testing partner", systemImage: "magnifyingglass").frame(maxWidth: .infinity).padding(12)
                        }.buttonStyle(.bordered).tint(lime)
                    }.disabled(!model.preciseRanging)
                    Text("One person creates a session. The other finds it. Read the temporary names to each other and approve only the matching phone. Allow Local Network and Nearby Interaction when asked.")
                        .font(.subheadline).foregroundStyle(muted)
                }
                DisclosureGroup("Device capabilities & session notes") {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Precise distance: \(model.preciseRanging ? "available" : "unavailable")\nDirection: \(model.directionSupported ? "available, unused" : "unavailable")\nExtended distance: \(model.extendedDistanceSupported ? "available, disabled" : "unavailable")")
                        Text("Camera assistance is off. No GPS, microphone, server, analytics or saved history. Temporary session tokens travel over an encrypted local pairing link. There is no guaranteed measurement interval.")
                        ForEach(Array(model.events.enumerated()), id: \.offset) { _, event in Text(event).font(.caption.monospaced()) }
                    }.font(.caption).foregroundStyle(muted).padding(.top, 12)
                }.tint(lime)
                Text("NEARBY PHONE LAB • ISOLATED PROTOTYPE").font(.caption2).tracking(1).foregroundStyle(muted)
            }.padding(24)
        }
        .background(Color(red: 0.035, green: 0.075, blue: 0.08))
        .alert(model.invitation == nil ? "Pair with this phone?" : "Allow this testing partner?", isPresented: Binding(get: { pendingInvite != nil || model.invitation != nil }, set: { _ in })) {
            Button("Cancel", role: .cancel) {
                pendingInvite = nil
                model.respondToInvitation(accept: false)
            }
            Button(model.invitation == nil ? "Confirm & invite" : "Approve pairing") {
                if model.invitation != nil { model.respondToInvitation(accept: true) }
                else if let peer = pendingInvite { model.invite(peer) }
                pendingInvite = nil
            }
        } message: {
            Text("Confirm your partner's screen shows \(model.invitation?.peer.displayName ?? pendingInvite?.displayName ?? "this name"). Both phones will share relative position for this session. Either person can stop.")
        }
    }

    private var measurement: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { _ in
            let now = ProcessInfo.processInfo.systemUptime
            let distance = model.state.freshDistance(at: now)
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Circle().fill(distance == nil ? muted : lime).frame(width: 7, height: 7)
                    Text(distance == nil ? "DISTANCE UNAVAILABLE" : "FRESH DISTANCE").font(.caption.bold()).tracking(2)
                }.foregroundStyle(distance == nil ? muted : lime)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(distance.map { String(format: "%.1f", $0) } ?? "—")
                        .font(.system(size: 78, weight: .light, design: .rounded)).monospacedDigit()
                        .contentTransition(.numericText())
                    if distance != nil { Text("meters").font(.title3).foregroundStyle(muted) }
                }.accessibilityElement(children: .combine).accessibilityIdentifier("distanceReading")
                HStack {
                    Text("\(model.state.sampleCount) samples")
                    Spacer()
                    Text(String(format: "Largest gap %.1f s", model.state.maximumGap))
                }.font(.caption.monospaced()).foregroundStyle(muted)
                Text("Readings expire after 2 seconds. This freshness threshold is an experiment setting, not a delivery guarantee.")
                    .font(.caption).foregroundStyle(muted)
            }.padding(22).frame(maxWidth: .infinity, alignment: .leading)
                .background(lime.opacity(0.055), in: RoundedRectangle(cornerRadius: 26))
                .overlay(RoundedRectangle(cornerRadius: 26).stroke(lime.opacity(0.18)))
        }
    }

    private var trialEvidence: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { _ in
            let snapshot = model.trialSnapshot
            VStack(alignment: .leading, spacing: 14) {
                Text("BACKGROUND EVIDENCE").font(.caption.bold()).tracking(2).foregroundStyle(lime)
                if model.active {
                    Picker("Planned trial", selection: $trialKind) {
                        ForEach(NearbyTrialJournal.Kind.allCases, id: \.self) { kind in
                            Text(kind.rawValue).tag(kind)
                        }
                    }.pickerStyle(.segmented)
                    Button { model.armTrial(trialKind) } label: {
                        Label("Arm next background interval", systemImage: "record.circle")
                            .frame(maxWidth: .infinity).padding(10)
                    }.buttonStyle(.bordered).tint(lime).disabled(!model.canArmTrial)
                    Text("First pair and enable the Live Activity. Choose your planned action, arm, then switch apps or lock. The label is your plan; the app cannot verify a lock from background notifications.")
                        .font(.caption).foregroundStyle(muted)
                }
                if let armed = snapshot.armed {
                    Text("Armed: \(armed.rawValue). Waiting for the next background notification.")
                        .font(.subheadline).foregroundStyle(lime)
                }
                if snapshot.current != nil {
                    Text("Interval in progress. Return to freeze its report.").font(.subheadline)
                }
                if snapshot.reports.isEmpty {
                    Text("No completed trial. A fresh foreground distance or visible Live Activity is not background evidence.")
                        .font(.subheadline).foregroundStyle(muted)
                }
                ForEach(snapshot.reports.reversed()) { report in
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Trial \(report.id) · \(report.kind.rawValue) (planned)").font(.subheadline.bold())
                        Text(report.endReason?.rawValue ?? "Incomplete").font(.caption).foregroundStyle(muted)
                        Text(String(format: "%.1f s interval · %d distance callbacks\n%d after first second · %d unavailable",
                                    report.duration ?? 0, report.distanceCallbacks,
                                    report.callbacksAfterOneSecond, report.unavailableCallbacks))
                            .font(.caption.monospaced())
                        Text(report.lastDistanceAgeAtEnd.map { String(format: "Last distance age at end: %.1f s", $0) }
                             ?? "No valid distance callback in this interval.")
                            .font(.caption.monospaced()).foregroundStyle(muted)
                        Text(String(format: "Longest silence: %.1f s · %d suspensions · %d resumptions",
                                    report.largestSilence, report.suspensions, report.resumptions))
                            .font(.caption.monospaced()).foregroundStyle(muted)
                    }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
                        .background(.white.opacity(0.045), in: RoundedRectangle(cornerRadius: 14))
                }
                Text("Frozen at foreground return or earlier Stop/failure. Counts are callback receipts, not sensor acquisition times or proof of Live Activity display. Six reports stay in memory until new pairing or app exit.")
                    .font(.caption).foregroundStyle(muted)
            }.padding(20).background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 22))
                .accessibilityIdentifier("backgroundEvidence")
        }
    }
}
