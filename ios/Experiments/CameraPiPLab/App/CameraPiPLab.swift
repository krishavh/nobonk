import AVFoundation
import AVKit
import OSLog
import SwiftUI

@main
struct CameraPiPLabApp: App {
    @StateObject private var model = PiPProbeModel()
    var body: some Scene { WindowGroup { ProbeView(model: model) } }
}

@MainActor
final class PiPProbeModel: NSObject, ObservableObject, AVPictureInPictureControllerDelegate,
    AVPictureInPictureSampleBufferPlaybackDelegate {
    let displayLayer = AVSampleBufferDisplayLayer()
    private let camera = CaptureProbe()
    private let logger = Logger(subsystem: "ai.genwhy.nobonk.camerapiplab", category: "PiP")
    private var controller: AVPictureInPictureController?
    private var timer: Timer?
    private var observations: [NSObjectProtocol] = []
    private var timebase: CMTimebase?
    private var wantsCapture = false
    private var startedClock = false
    private var audioSessionActive = false
    private var lastUIRefresh = 0.0
    private var lastPlaybackPaused = true
    @Published var snapshot = ProbeSnapshot()
    @Published var acknowledged = false
    @Published var permissionMessage = ""
    @Published var pipStatus = "Not started"
    @Published var possible = false
    @Published var pipActive = false
    @Published var renderedCount = 0
    @Published var lastFrameAge = "No frames"
    @Published var running = false
    @Published var lifecycle = "Foreground"

    override init() {
        super.init()
        displayLayer.videoGravity = .resizeAspect
        var clock: CMTimebase?
        if CMTimebaseCreateWithSourceClock(allocator: kCFAllocatorDefault,
                                          sourceClock: CMClockGetHostTimeClock(), timebaseOut: &clock) == noErr {
            timebase = clock
            displayLayer.controlTimebase = clock
        }
        if AVPictureInPictureController.isPictureInPictureSupported() {
            controller = AVPictureInPictureController(contentSource: .init(sampleBufferDisplayLayer: displayLayer, playbackDelegate: self))
            controller?.delegate = self
            controller?.canStartPictureInPictureAutomaticallyFromInline = false
            controller?.requiresLinearPlayback = true
        } else { pipStatus = "PiP unsupported on this device" }

        observations.append(NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.enteredBackground() }
        })
        observations.append(NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.enteredForeground() }
        })
        timer = Timer.scheduledTimer(withTimeInterval: 1.0 / 15, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }
    isolated deinit {
        timer?.invalidate()
        camera.stop(message: "Probe closed")
        for observation in observations { NotificationCenter.default.removeObserver(observation) }
    }
    func startCamera() {
        guard acknowledged, !wantsCapture, UIApplication.shared.applicationState == .active else { return }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            permissionMessage = ""
            wantsCapture = true
            running = true
            renderedCount = 0
            startedClock = false
            displayLayer.sampleBufferRenderer.flush()
            camera.start()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                Task { @MainActor in
                    self?.permissionMessage = granted ? "Camera allowed. Tap Start camera again." : "Camera denied. Enable it in Settings."
                }
            }
        default:
            permissionMessage = "Camera denied. Enable it in Settings."
        }
    }
    func startPiP() {
        guard wantsCapture, UIApplication.shared.applicationState == .active else { return }
        guard let controller else { pipStatus = "PiP unsupported"; return }
        do {
            // Standard visible video-playback session. No microphone, audio engine,
            // silent samples, or recording exists in this experiment.
            let audio = AVAudioSession.sharedInstance()
            try audio.setCategory(.playback, mode: .moviePlayback, options: [.mixWithOthers])
            try audio.setActive(true)
            audioSessionActive = true
            controller.invalidatePlaybackState()
            guard controller.isPictureInPicturePossible else {
                pipStatus = "PiP not possible yet. Keep camera visible, then retry."
                deactivateAudio()
                return
            }
            pipStatus = "Requesting visible PiP…"
            controller.startPictureInPicture()
        } catch { pipStatus = "Video session failed: \(error.localizedDescription)"; deactivateAudio() }
    }
    func stop() {
        wantsCapture = false
        running = false
        camera.stop()
        if controller?.isPictureInPictureActive == true { controller?.stopPictureInPicture() }
        controller?.invalidatePlaybackState()
        displayLayer.sampleBufferRenderer.flush(removingDisplayedImage: true)
        startedClock = false
        deactivateAudio()
        refresh(forceUI: true)
    }
    private func deactivateAudio() {
        guard audioSessionActive else { return }
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        audioSessionActive = false
    }
    private func enteredBackground() {
        lifecycle = "Background"
        camera.journal.background(true, now: ProcessInfo.processInfo.systemUptime)
        logger.notice("HOME/LOCK: PiP active=\(self.controller?.isPictureInPictureActive ?? false), count=\(self.camera.journal.snapshot().frameCount)")
        // Only the explicit, visible PiP trial intentionally leaves capture requested.
        // The OS may interrupt it; never automatically start/retry camera in background.
        if controller?.isPictureInPictureActive != true {
            stop()
            pipStatus = "Stopped on leaving: no active PiP trial"
        }
    }
    private func enteredForeground() {
        camera.journal.background(false, now: ProcessInfo.processInfo.systemUptime)
        lifecycle = "Foreground again"
        let result = camera.journal.snapshot()
        logger.notice("RETURN: backgroundFrames=\(result.backgroundFrames), after1s=\(result.backgroundFramesAfterOneSecond), duration=\(result.lastBackgroundDuration)")
        refresh(forceUI: true)
    }
    private func refresh(forceUI: Bool = false) {
        if let (token, sample) = camera.journal.takeLatest(), wantsCapture, camera.journal.accepts(token) {
            let renderer = displayLayer.sampleBufferRenderer
            if renderer.status == .failed { renderer.flush(); startedClock = false }
            if renderer.isReadyForMoreMediaData {
                if !startedClock, let timebase {
                    CMTimebaseSetTime(timebase, time: CMSampleBufferGetPresentationTimeStamp(sample))
                    CMTimebaseSetRate(timebase, rate: 1)
                    startedClock = true
                }
                renderer.enqueue(sample)
                renderedCount += 1
            }
        }
        let now = ProcessInfo.processInfo.systemUptime
        guard forceUI || now - lastUIRefresh >= 0.25 else { return }
        lastUIRefresh = now
        snapshot = camera.journal.snapshot()
        possible = controller?.isPictureInPicturePossible ?? false
        pipActive = controller?.isPictureInPictureActive ?? false
        lastFrameAge = snapshot.lastArrival > 0 ? String(format: "%.2f s", now - snapshot.lastArrival) : "No frames"
        let paused = !snapshot.active || snapshot.lastArrival == 0 || now - snapshot.lastArrival > 1
        if paused != lastPlaybackPaused {
            lastPlaybackPaused = paused
            controller?.invalidatePlaybackState()
        }
        if wantsCapture && !snapshot.active {
            wantsCapture = false
            running = false
            controller?.stopPictureInPicture()
            deactivateAudio()
        }
    }
    nonisolated func pictureInPictureControllerDidStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            guard self.wantsCapture else { self.controller?.stopPictureInPicture(); return }
            self.pipStatus = "PiP visible — now press Home and watch for frozen frames"
            self.pipActive = true
            self.logger.notice("PiP started")
        }
    }
    nonisolated func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        let message = error.localizedDescription
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.pipStatus = "PiP failed: \(message)"
            self.pipActive = false
            self.deactivateAudio()
            self.logger.notice("PiP failed: \(message, privacy: .public)")
        }
    }
    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        camera.stop(message: "PiP ended") // Invalidate before waiting for the UI actor.
        Task { @MainActor [weak self] in
            self?.pipActive = false
            self?.pipStatus = "PiP ended — camera stopped; report retained"
            self?.stop()
        }
    }
    nonisolated func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        completionHandler(true) // The single existing probe scene is the restoration UI.
    }
    nonisolated func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, setPlaying playing: Bool) {
        // Play cannot restart the camera from a background PiP control. Reopen and Start.
        if !playing { camera.stop(message: "Paused from PiP") }
        Task { @MainActor [weak self] in
            guard let self else { return }
            if !playing { self.stop() }
            else if !self.wantsCapture { self.pipStatus = "Reopen the app and tap Start camera" }
            self.controller?.invalidatePlaybackState()
        }
    }
    nonisolated func pictureInPictureControllerTimeRangeForPlayback(_ pictureInPictureController: AVPictureInPictureController) -> CMTimeRange {
        let s = camera.journal.snapshot()
        return s.active && s.frameCount > 0 ? CMTimeRange(start: .zero, duration: .positiveInfinity) : .invalid
    }
    nonisolated func pictureInPictureControllerIsPlaybackPaused(_ pictureInPictureController: AVPictureInPictureController) -> Bool {
        let s = camera.journal.snapshot()
        return !s.active || s.lastArrival == 0 || ProcessInfo.processInfo.systemUptime - s.lastArrival > 1
    }
    nonisolated func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, didTransitionToRenderSize newRenderSize: CMVideoDimensions) {}
    nonisolated func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, skipByInterval skipInterval: CMTime, completion: @escaping () -> Void) { completion() }
    nonisolated func pictureInPictureControllerShouldProhibitBackgroundAudioPlayback(_ pictureInPictureController: AVPictureInPictureController) -> Bool { true }
}

private struct ProbeView: View {
    @ObservedObject var model: PiPProbeModel
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("A camera experiment, not obstacle detection.")
                        .font(.headline).foregroundStyle(.orange)
                    Text("A floating picture can freeze. Only fresh frame timestamps and the return report count as evidence. Stay still in a safe place while testing.")
                    Toggle("I understand this is only a camera/PiP test", isOn: $model.acknowledged)
                        .disabled(model.running)
                    SamplePreview(layer: model.displayLayer)
                        .frame(height: 290).background(.black)
                        .clipShape(RoundedRectangle(cornerRadius: 18))
                    HStack {
                        Button("Start camera", systemImage: "camera") { model.startCamera() }
                            .disabled(!model.acknowledged || model.running)
                        Button("Stop", systemImage: "stop.fill", role: .destructive) { model.stop() }
                            .disabled(!model.running && !model.pipActive)
                    }.buttonStyle(.borderedProminent)
                    Button("Open visible PiP", systemImage: "pip.enter") { model.startPiP() }
                        .buttonStyle(.bordered).disabled(!model.running || model.pipActive)
                    Text(model.permissionMessage.isEmpty ? model.pipStatus : model.permissionMessage)
                        .font(.subheadline).foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 8) {
                        metric("Capture", model.snapshot.status)
                        metric("PiP", "possible: \(model.possible) · active: \(model.pipActive)")
                        metric("Multitasking camera", "supported: \(model.snapshot.supported) · enabled: \(model.snapshot.enabled)")
                        metric("Fresh camera frames", String(model.snapshot.frameCount))
                        metric("Submitted for display", String(model.renderedCount))
                        metric("Last camera PTS", String(format: "%.3f", model.snapshot.lastPTS))
                        metric("Last frame age", model.lastFrameAge)
                        metric("Longest observed gap", String(format: "%.3f s", model.snapshot.longestGap))
                        metric("Interruption", model.snapshot.interruption)
                        metric("Last interruption reason", model.snapshot.interruptionReason.map(String.init) ?? "None")
                        metric("App state", model.lifecycle)
                        Divider()
                        Text("Last Home / lock trial").font(.headline)
                        metric("Background duration", String(format: "%.1f s", model.snapshot.lastBackgroundDuration))
                        metric("Background frames", String(model.snapshot.backgroundFrames))
                        metric("Frames after first 1 s", String(model.snapshot.backgroundFramesAfterOneSecond))
                        metric("Frame age when returning", model.snapshot.frameAgeAtReturn < 0 ? "Not measured" : String(format: "%.2f s", model.snapshot.frameAgeAtReturn))
                    }.font(.system(.caption, design: .monospaced)).padding()
                        .frame(maxWidth: .infinity, alignment: .leading).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
                    Text("Open PiP first. Press Home for 15 seconds while moving a clock in front of the camera; then return. Repeat with lock separately. A few grace-period frames do not establish continuing capture. Stop ends capture and clears the image.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Text("No microphone · no saved frames · no network. iOS may resume an interrupted session when you return; the background report is retained.")
                        .font(.caption).foregroundStyle(.secondary)
                }.padding()
            }.navigationTitle("Camera PiP Lab").preferredColorScheme(.dark)
        }
    }
    private func metric(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) { Text(label).foregroundStyle(.secondary); Text(value).textSelection(.enabled) }
    }
}

private struct SamplePreview: UIViewRepresentable {
    let layer: AVSampleBufferDisplayLayer
    final class HostView: UIView {
        var sampleLayer: AVSampleBufferDisplayLayer?
        override func layoutSubviews() {
            super.layoutSubviews()
            CATransaction.begin(); CATransaction.setDisableActions(true)
            sampleLayer?.frame = bounds
            CATransaction.commit()
        }
    }
    func makeUIView(context: Context) -> HostView {
        let view = HostView(); view.sampleLayer = layer; view.layer.addSublayer(layer); return view
    }
    func updateUIView(_ uiView: HostView, context: Context) {}
}
