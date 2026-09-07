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
    @State private var expandedCamera = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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
        GeometryReader { screen in
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    brand
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "eye.fill").foregroundStyle(.yellow)
                        Text("Keep looking up. No alert does not mean a clear path.")
                            .font(.subheadline).foregroundStyle(.white.opacity(0.8))
                    }.padding(14).background(.yellow.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
                    cameraCard
                        .frame(height: expandedCamera ? min(screen.size.height * 0.72, 650) : min(screen.size.height * 0.42, 360))
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(camera.running ? "Your scan, your pace." : "Find your comfortable setup.")
                                .font(.title3.bold())
                            Text("People only · Keep NoBonk open")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 4)
                        Image(systemName: "slider.horizontal.3").foregroundStyle(mint)
                    }
                    VStack(alignment: .leading, spacing: 14) {
                        HStack {
                            Text("WHEN TO CUE").font(.caption2.bold()).tracking(1.7)
                            Spacer()
                            Text("Image size, not distance").font(.caption2).foregroundStyle(.secondary)
                        }
                        Picker("Cue sensitivity", selection: $camera.sensitivity) {
                            ForEach(AlertSensitivity.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                        }.pickerStyle(.segmented)
                        Text("Earlier notices smaller people in the center of the view. Try each setting while standing still.")
                            .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        Divider().overlay(.white.opacity(0.06))
                        HStack(spacing: 20) {
                            Toggle(isOn: $camera.sound) { Label("Sound", systemImage: "speaker.wave.2") }
                            Toggle(isOn: $camera.haptics) { Label("Haptics", systemImage: "waveform") }
                        }.font(.caption.bold())
                    }.padding(16).background(.white.opacity(0.045), in: RoundedRectangle(cornerRadius: 22))
                    if camera.denied {
                        Button("Open camera settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }.buttonStyle(.bordered).frame(maxWidth: .infinity)
                    }
                    DisclosureGroup {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("All frames stay on this phone. Apple Vision chooses the available computing hardware; NoBonk adapts the analysis pace to the work and temperature.")
                            if camera.analysisMilliseconds > 0 {
                                Text("Recent analysis: \(camera.analysisMilliseconds) ms · target up to \(camera.analysisRate) frames/s")
                                    .monospacedDigit().foregroundStyle(mint)
                            }
                            Text("The compact camera leaves room for NoBonk’s controls. iPhone does not let this app scan behind other apps. Switching apps stops the camera.")
                        }.font(.caption).foregroundStyle(.secondary).padding(.top, 8)
                    } label: {
                        Label("On this iPhone", systemImage: "iphone.gen3.radiowaves.left.and.right")
                            .font(.subheadline)
                    }.padding(.horizontal, 4)
                    HStack {
                        Button("Safety & privacy") { camera.stop(); showFull = true }
                        Spacer()
                        Text("BY KRISHAV").font(.caption2.bold()).tracking(2).foregroundStyle(mint)
                    }.font(.caption).padding(.vertical, 6)
                }.padding(20)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Button {
                    if camera.running || camera.starting { camera.stop() }
                    else if camera.denied, let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    else { camera.start() }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: camera.running || camera.starting ? "stop.fill" : "play.fill")
                        Text(camera.running || camera.starting ? "Stop scanning" : (camera.denied ? "Open camera settings" : "Start scanning"))
                        Spacer()
                        Text(camera.running ? "LIVE" : (camera.starting ? "STARTING" : (camera.denied ? "ACCESS OFF" : "READY")))
                            .font(.caption2.bold()).tracking(1.5)
                    }
                }
                .buttonStyle(PrimaryButton(isStop: camera.running || camera.starting))
                .padding(.horizontal, 20).padding(.top, 10).padding(.bottom, 8)
                .background(night.opacity(0.98))
            }
        }
    }
    private var cameraCard: some View {
        ZStack {
            Color.black
            CameraPreview(session: camera.engine.session)
            GeometryReader { geometry in
                ForEach(camera.boxes) { box in
                    let rect = PreviewGeometry.rect(for: box, width: geometry.size.width,
                                                    height: geometry.size.height, imageAspect: camera.previewAspect)
                    RoundedRectangle(cornerRadius: 10).stroke(mint, lineWidth: 2)
                        .frame(width: rect.width, height: rect.height)
                        .position(x: rect.midX, y: rect.midY)
                }
            }.allowsHitTesting(false).accessibilityHidden(true)
            if !camera.running {
                VStack(spacing: 12) {
                    ZStack {
                        Circle().fill(mint.opacity(0.09)).frame(width: 66, height: 66)
                        if camera.starting { ProgressView().tint(mint) }
                        else { Image(systemName: "viewfinder").font(.system(size: 30, weight: .light)).foregroundStyle(mint) }
                    }
                    Text(camera.starting ? "Opening your camera" : "A little more awareness.").font(.headline)
                    Text(camera.starting ? "You can stop at any time." : "Stand still. Set up. Look up.")
                        .font(.caption).foregroundStyle(.secondary)
                }.padding(22).frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(LinearGradient(colors: [mint.opacity(0.08), night], startPoint: .topLeading, endPoint: .bottomTrailing))
            }
            VStack {
                HStack(spacing: 8) {
                    Circle().fill(camera.running ? mint : .gray).frame(width: 6, height: 6)
                    Text(camera.running ? "LIVE VIEW" : "CAMERA PAUSED").font(.system(size: 10, weight: .bold)).tracking(1.5)
                    Spacer()
                    Button {
                        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) { expandedCamera.toggle() }
                    } label: {
                        Image(systemName: expandedCamera ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right")
                            .font(.system(size: 13, weight: .semibold)).frame(width: 44, height: 44)
                            .background(.black.opacity(0.55), in: Circle())
                    }.accessibilityLabel(expandedCamera ? "Compact camera" : "Expand camera")
                }.padding(.leading, 16).padding(.trailing, 6).padding(.top, 4)
                Spacer()
                Text(camera.status).font(.caption.weight(.medium)).multilineTextAlignment(.center)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(.black.opacity(0.72), in: Capsule()).padding(12)
            }
            TimelineView(.periodic(from: .now, by: 0.5)) { tick in
                if tick.date < camera.alertUntil {
                    VStack {
                        Label("Person ahead — look up", systemImage: "exclamationmark.triangle.fill")
                            .font(.subheadline.bold()).foregroundStyle(.black).padding(12)
                            .background(Color.orange, in: RoundedRectangle(cornerRadius: 14))
                        Spacer()
                    }.padding(.horizontal, 12).padding(.top, 56)
                }
            }.allowsHitTesting(false)
        }
        .clipShape(RoundedRectangle(cornerRadius: 26))
        .overlay(RoundedRectangle(cornerRadius: 26).stroke(.white.opacity(0.12), lineWidth: 1))
    }

}

private struct PrimaryButton: ButtonStyle {
    var isStop = false
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.headline).frame(maxWidth: .infinity).padding(.vertical, 18)
            .padding(.horizontal, 20)
            .background((isStop ? Color(red: 1, green: 0.38, blue: 0.46) : mint).opacity(enabled ? (configuration.isPressed ? 0.7 : 1) : 0.25), in: RoundedRectangle(cornerRadius: 16))
            .foregroundStyle(enabled ? Color.black : .gray)
    }
}
