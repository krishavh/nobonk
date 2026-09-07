import SwiftUI

@main
struct NoBonkApp: App {
    var body: some Scene { WindowGroup { NoBonkView().preferredColorScheme(.dark) } }
}

private let night = Color(red: 0.035, green: 0.055, blue: 0.09)
private let mint = Color(red: 0.2, green: 0.85, blue: 0.65)

struct NoBonkView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var camera = CameraModel()
    @State private var gate = SafetyGate(acknowledgedVersion: UserDefaults.standard.integer(forKey: "safetyNoticeVersion"))
    @State private var checked = false
    @State private var showFull = false
    var body: some View {
        ZStack {
            night.ignoresSafeArea()
            if gate.screen == .scanning { scanning } else { notice }
        }
        .tint(mint)
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { camera.stop() }
            if phase == .background { gate.leaveForeground(); showFull = false; checked = false }
        }
        .sheet(isPresented: $showFull) {
            NavigationStack {
                ScrollView { Text(SafetyCopy.full).font(.body).padding(24) }
                    .navigationTitle("Safety notice")
                    .toolbar { Button("Done") { showFull = false } }
            }
        }
    }
    private var brand: some View {
        HStack(spacing: 12) {
            Image("BrandIcon").resizable().frame(width: 44, height: 44).clipShape(RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 2) {
                Text("NOBONK").font(.headline).tracking(3)
                Text("A little more awareness.").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Text("iPHONE\nPREVIEW").font(.system(size: 9, weight: .bold)).tracking(1).foregroundStyle(mint)
        }
    }
    private var notice: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                brand
                VStack(alignment: .leading, spacing: 12) {
                    Label("KEEP LOOKING UP", systemImage: "exclamationmark.triangle.fill").font(.caption.bold()).tracking(1.5).foregroundStyle(.yellow)
                    Text(gate.screen == .fullNotice ? "A helper. Never a safety device." : "Your attention comes first.")
                        .font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    Text(gate.screen == .fullNotice ? SafetyCopy.full : SafetyCopy.reminder).font(.body).lineSpacing(5)
                    if gate.screen == .fullNotice {
                        Toggle(SafetyCopy.acknowledgment, isOn: $checked).toggleStyle(.switch).padding(.top, 8)
                    }
                }.padding(20).background(Color.yellow.opacity(0.08), in: RoundedRectangle(cornerRadius: 22))
                    .overlay(RoundedRectangle(cornerRadius: 22).stroke(Color.yellow.opacity(0.45)))
                Button(gate.screen == .fullNotice ? "I understand — continue" : "OK — continue") {
                    if gate.screen == .fullNotice {
                        guard gate.acceptFullNotice(checked: checked) else { return }
                        UserDefaults.standard.set(SafetyGate.noticeVersion, forKey: "safetyNoticeVersion")
                    } else { gate.continueFromReminder() }
                }.buttonStyle(PrimaryButton()).disabled(gate.screen == .fullNotice && !checked)
                Button("Read the full safety notice") { showFull = true }.frame(maxWidth: .infinity)
                Label("People detection, on this iPhone", systemImage: "person.crop.rectangle").font(.headline)
                Text("This early iPhone build detects people only. It does not yet detect cars, pets, walls or ground hazards. Camera frames are processed on-device, never saved or uploaded.").foregroundStyle(.secondary)
                Label("Keep NoBonk open to scan", systemImage: "iphone").font(.headline)
                Text("Scanning stops when you leave the app or the camera is interrupted. No background camera scanning is included.").foregroundStyle(.secondary)
                Text("BY KRISHAV").font(.caption.bold()).tracking(3).foregroundStyle(mint).padding(.top)
            }.padding(22)
        }
    }
    private var scanning: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                brand
                HStack {
                    Circle().fill(camera.running ? mint : .gray).frame(width: 7,height: 7)
                    Text(camera.status).font(.subheadline.bold())
                }.accessibilityElement(children: .combine)
                ZStack {
                    CameraPreview(session: camera.engine.session)
                    GeometryReader { geometry in
                        ForEach(camera.boxes) { box in
                            RoundedRectangle(cornerRadius: 8).stroke(mint, lineWidth: 2)
                                .frame(width: geometry.size.width * box.width, height: geometry.size.height * box.height)
                                .position(x: geometry.size.width * (box.x + box.width/2), y: geometry.size.height * (box.y + box.height/2))
                        }
                    }.allowsHitTesting(false).accessibilityHidden(true)
                    if !camera.running {
                        VStack(spacing: 8) {
                            Image(systemName: "camera").font(.largeTitle)
                            Text("Not scanning").font(.title3.bold())
                            Text("Tap Start when you are in a safe place.").font(.caption)
                        }.padding().background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
                    }
                    TimelineView(.periodic(from: .now, by: 0.5)) { tick in
                        if tick.date < camera.alertUntil {
                            VStack { Label("Person ahead — look up", systemImage: "exclamationmark.triangle.fill").font(.headline).padding().background(Color.orange, in: Capsule()); Spacer() }.padding(12)
                        }
                    }.allowsHitTesting(false)
                }.aspectRatio(9/16, contentMode: .fit).frame(maxHeight: 450).clipShape(RoundedRectangle(cornerRadius: 24))
                Text("PEOPLE ONLY · FOREGROUND ONLY").font(.caption2.bold()).tracking(1.5).foregroundStyle(mint)
                Text("No alert does not mean the path is clear.").font(.subheadline).foregroundStyle(.secondary)
                HStack {
                    Toggle("Sound", isOn: $camera.sound)
                    Toggle("Haptics", isOn: $camera.haptics)
                }.font(.subheadline).padding().background(.white.opacity(0.04), in: RoundedRectangle(cornerRadius: 16))
                Button(camera.running ? "Stop scanning" : "Start scanning") {
                    if camera.running { camera.stop() } else { camera.start() }
                }.buttonStyle(PrimaryButton())
                if camera.denied {
                    Button("Open camera settings") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                }
                HStack {
                    Button("Safety & privacy") { camera.stop(); showFull = true }
                    Spacer()
                    Text("BY KRISHAV").font(.caption.bold()).tracking(2).foregroundStyle(mint)
                }.font(.caption).padding(.bottom)
            }.padding(20)
        }
    }
}

private struct PrimaryButton: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.headline).frame(maxWidth: .infinity).padding(.vertical, 18)
            .background(mint.opacity(enabled ? (configuration.isPressed ? 0.7 : 1) : 0.25), in: RoundedRectangle(cornerRadius: 16))
            .foregroundStyle(enabled ? Color.black : .gray)
    }
}
