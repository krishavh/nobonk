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
                Text("An experiment in measuring the distance between two paired iPhones, even while another app is open.")
                    .font(.body).foregroundStyle(muted)
                Label("Research only. This measures one consenting phone. It cannot detect walls, unknown people or hazards.", systemImage: "info.circle")
                    .font(.subheadline).padding(16).background(.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 18))

                measurement
                Text(model.status).font(.subheadline).foregroundStyle(muted).accessibilityIdentifier("sessionStatus")

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
}
