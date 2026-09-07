import AVFoundation
import Vision
import SwiftUI
import OSLog


// Capture and Vision work stay on one serial queue; UI never receives image data.
final class CameraEngine: NSObject {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "ai.genwhy.nobonk.camera", qos: .userInitiated)
    private var configured = false
    private var videoOutput: AVCaptureVideoDataOutput?
    private var frameReceiver: CameraFrameReceiver?
    private var fastDetector: FastObjectDetector?
    private var cadence = AnalysisCadence()
    private let humanRequest: VNDetectHumanRectanglesRequest = {
        let request = VNDetectHumanRectanglesRequest()
        request.upperBodyOnly = false
        // Leave compute-device assignment at Apple's default; do not force CPU,
        // or promise GPU/Neural Engine use that Vision has not reported.
        return request
    }()
    let generation = CaptureGeneration()
    private var imageOrientation: CGImagePropertyOrientation = .up
    private var firstResultPending = false
    private var startedAt = 0.0
    private let diagnostics = Logger(subsystem: "ai.genwhy.nobonk", category: "CameraAccess")
    var onBoxes: ((UInt64, [PersonBox], Double, Double, Double) -> Void)?
    var onState: ((UInt64, Bool, String) -> Void)?
    var onTiming: ((UInt64, DetectorTiming) -> Void)?
    func start(token: UInt64, mode: DetectorMode) {
        queue.async { [self] in
            guard generation.accepts(token) else { return }
            startedAt = ProcessInfo.processInfo.systemUptime
            firstResultPending = true
            do {
                if !configured { try configure() }
                if mode == .fastObjects, fastDetector == nil { fastDetector = try FastObjectDetector() }
                guard generation.accepts(token) else { return }
                try configureFormat(mode)
                let receiver = CameraFrameReceiver(owner: self, token: token, mode: mode, orientation: imageOrientation)
                frameReceiver = receiver
                videoOutput?.setSampleBufferDelegate(receiver, queue: queue)
                #if DEBUG
                diagnostics.notice("Camera capability: multitasking supported=\(self.session.isMultitaskingCameraAccessSupported), enabled=\(self.session.isMultitaskingCameraAccessEnabled)")
                #endif
                guard generation.accepts(token) else { return }
                session.startRunning()
                guard generation.accepts(token) else { session.stopRunning(); return }
                onState?(token, session.isRunning, session.isRunning ? "Scanning · \(mode.rawValue)" : "Camera unavailable — tap Start to retry")
            } catch { onState?(token, false, (error as? FastDetectorError)?.localizedDescription ?? "Camera unavailable. Check access or choose People, then tap Start.") }
        }
    }
    func stop() {
        generation.stop()
        #if DEBUG
        diagnostics.notice("Scan stopped; pending results invalidated")
        #endif
        queue.async { [self] in
            if session.isRunning { session.stopRunning() }
            videoOutput?.setSampleBufferDelegate(nil, queue: nil)
            frameReceiver = nil
            cadence.reset()
        }
    }
    private func configure() throws {
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else { throw CameraError.unavailable }
        let input = try AVCaptureDeviceInput(device: camera)
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = session.canSetSessionPreset(.hd1280x720) ? .hd1280x720 : .high
        guard session.canAddInput(input) else { throw CameraError.unavailable }
        session.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        guard session.canAddOutput(output) else {
            session.removeInput(input)
            throw CameraError.unavailable
        }
        session.addOutput(output)
        videoOutput = output
        if let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
            imageOrientation = .up
        } else { imageOrientation = .right } // unrotated rear-camera buffer → portrait Vision coordinates
        configured = true
    }
    private func configureFormat(_ mode: DetectorMode) throws {
        guard let output = videoOutput else { throw CameraError.unavailable }
        let formats = output.availableVideoPixelFormatTypes
        let format: OSType = mode == .fastObjects ? kCVPixelFormatType_32BGRA :
            (formats.contains(kCVPixelFormatType_420YpCbCr8BiPlanarFullRange) ? kCVPixelFormatType_420YpCbCr8BiPlanarFullRange :
             (formats.contains(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange) ? kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange : kCVPixelFormatType_32BGRA))
        guard formats.contains(format) else { throw FastDetectorError.pixels }
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: format]
    }
    fileprivate func process(_ sample: CMSampleBuffer, token: UInt64, mode: DetectorMode, orientation: CGImagePropertyOrientation) {
        guard generation.accepts(token), session.isRunning else { return }
        let now = ProcessInfo.processInfo.systemUptime
        let pressure: AnalysisCadence.Pressure
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: pressure = .normal
        case .fair: pressure = .warm
        case .serious: pressure = .hot
        case .critical: pressure = .critical
        @unknown default: pressure = .hot
        }
        let lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        guard cadence.begin(at: now, pressure: pressure, lowPower: lowPower),
              let buffer = CMSampleBufferGetImageBuffer(sample) else { return }
        do {
            let boxes: [PersonBox] = try autoreleasepool {
                if mode == .fastObjects {
                    guard let fastDetector else { throw FastDetectorError.missingAsset }
                    let (detections, timing) = try fastDetector.detect(buffer, rotation: orientation == .up ? .upright : .clockwise90)
                    if generation.accepts(token) { onTiming?(token, timing) }
                    return detections.map { item in
                        PersonBox(id: item.anchor, x: item.rect.x, y: item.rect.y, width: item.rect.width,
                                  height: item.rect.height, confidence: Double(item.confidence), classID: item.classID, detectorMode: .fastObjects)
                    }.filter(\.usable)
                }
                try VNImageRequestHandler(cvPixelBuffer: buffer, orientation: orientation).perform([humanRequest])
                return (humanRequest.results ?? []).enumerated().map { index, item in
                    let r = item.boundingBox
                    return PersonBox(id: index, x: r.minX, y: 1-r.maxY, width: r.width, height: r.height, confidence: Double(item.confidence))
                }.filter(\.usable)
            }
            cadence.complete(duration: ProcessInfo.processInfo.systemUptime - now)
            let width = Double(CVPixelBufferGetWidth(buffer)), height = Double(CVPixelBufferGetHeight(buffer))
            let aspect = orientation == .up ? width / height : height / width
            if generation.accepts(token) {
                #if DEBUG
                if firstResultPending {
                    firstResultPending = false
                    let elapsed = ProcessInfo.processInfo.systemUptime - startedAt
                    diagnostics.notice("First analyzed frame after Start: \(elapsed, format: .fixed(precision: 3)) seconds")
                }
                #endif
                onBoxes?(token, boxes, aspect, cadence.averageDuration,
                         cadence.interval(pressure: pressure, lowPower: lowPower))
            }
        } catch { onState?(token, false, "Detection interrupted. Tap Start to retry."); session.stopRunning() }
    }
    private enum CameraError: Error { case unavailable }
}

/// AVCapture callbacks retain their originating scan's immutable token and mode.
/// A queued frame from before Stop cannot be relabeled as a new scan after restart.
private final class CameraFrameReceiver: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    weak var owner: CameraEngine?
    let token: UInt64, mode: DetectorMode, orientation: CGImagePropertyOrientation
    init(owner: CameraEngine, token: UInt64, mode: DetectorMode, orientation: CGImagePropertyOrientation) {
        self.owner = owner; self.token = token; self.mode = mode; self.orientation = orientation
    }
    func captureOutput(_ output: AVCaptureOutput, didOutput sample: CMSampleBuffer, from connection: AVCaptureConnection) {
        owner?.process(sample, token: token, mode: mode, orientation: orientation)
    }
}

@MainActor
final class CameraModel: ObservableObject {
    let engine = CameraEngine()
    @Published var boxes: [PersonBox] = []
    @Published var status = "Ready when you are"
    @Published var running = false
    @Published var starting = false
    @Published var denied = false
    @Published var detectorMode: DetectorMode = .visionPeople {
        didSet { if oldValue != detectorMode { stop(message: "Mode changed — tap Start"); detectorTiming = nil } }
    }
    @Published var detectorTiming: DetectorTiming?
    var fastModelAvailable: Bool { FastObjectDetector.assetURL != nil }
    @Published var sound = true { didSet { if !sound { audio?.stop() } } }
    @Published var haptics = true
    @Published var sensitivity: AlertSensitivity = .balanced { didSet { policy.reset() } }
    @Published var previewAspect = 9.0 / 16
    @Published var analysisMilliseconds = 0
    @Published var analysisRate = 0
    @Published var alertUntil = Date.distantPast
    @Published var alertText = "Person ahead — look up"
    private var wanted = false
    private var audio: AVAudioPlayer?
    private var policy = DetectionPolicy()
    private var observers: [NSObjectProtocol] = []
    init() {
        engine.onBoxes = { [weak self] token, boxes, aspect, duration, interval in
            Task { @MainActor in
                guard let self, self.engine.generation.accepts(token), self.wanted, self.running, UIApplication.shared.applicationState == .active else { return }
                self.boxes = boxes
                self.previewAspect = aspect
                self.analysisMilliseconds = Int((duration * 1000).rounded())
                self.analysisRate = Int((1 / interval).rounded())
                let cue = self.policy.evaluate(boxes, time: ProcessInfo.processInfo.systemUptime, sensitivity: self.sensitivity)
                if cue != .none {
                    self.alertText = cue == .personAhead ? "Person ahead — look up" : "Object in view — look up"
                    self.alertUntil = Date().addingTimeInterval(2)
                    if self.haptics { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
                    if self.sound { self.playCue() }
                }
            }
        }
        engine.onTiming = { [weak self] token, timing in
            Task { @MainActor in
                guard let self, self.engine.generation.accepts(token), self.wanted else { return }
                self.detectorTiming = timing
            }
        }
        engine.onState = { [weak self] token, running, status in
            Task { @MainActor in
                guard let self, self.engine.generation.accepts(token), self.wanted else { return }
                self.starting = false
                if running {
                    self.running = true; self.status = status
                    // Avoid auto-lock silently ending a deliberately started scan.
                    // Explicit locking, leaving the app and Stop still end capture.
                    UIApplication.shared.isIdleTimerDisabled = true
                }
                else { self.stop(message: status) } // failed start must release the retry latch
            }
        }
        for name in [AVCaptureSession.wasInterruptedNotification, AVCaptureSession.runtimeErrorNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: engine.session, queue: .main) { [weak self] _ in
                Task { @MainActor in
                    guard let self, self.wanted else { return }
                    self.stop(message: "Camera interrupted — tap Start to retry")
                }
            })
        }
    }
    deinit { engine.stop(); audio?.stop(); for observer in observers { NotificationCenter.default.removeObserver(observer) } }
    func start() {
        guard !wanted else { return }
        denied = false
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            guard UIApplication.shared.applicationState == .active else { return }
            wanted = true; starting = true
            status = detectorMode == .fastObjects ? "Preparing Fast Objects on this phone…" : "Starting camera…"
            policy.reset(); engine.start(token: engine.generation.begin(), mode: detectorMode)
        case .notDetermined:
            status = "Camera permission needed"
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                Task { @MainActor in
                    guard let self else { return }
                    // No capture is started by a delayed permission result; user taps Start.
                    self.denied = !granted
                    self.status = granted ? "Camera enabled — tap Start" : "Camera access is off"
                }
            }
        default: denied = true; status = "Camera access is off"
        }
    }
    /// A short local tone owned by this session, so Stop and Sound-off can cancel it.
    private func playCue() {
        do {
            if audio == nil { audio = try AVAudioPlayer(data: AlertTone.wav()) }
            audio?.currentTime = 0
            audio?.play()
        } catch { /* Visual and haptic cues remain available if audio cannot start. */ }
    }
    func stop(message: String = "Paused — not scanning") {
        UIApplication.shared.isIdleTimerDisabled = false
        wanted = false; starting = false; running = false; boxes = []; policy.reset(); alertUntil = .distantPast
        audio?.stop()
        analysisMilliseconds = 0; analysisRate = 0
        status = message; engine.stop()
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
        override func layoutSubviews() {
            super.layoutSubviews()
            if let connection = preview.connection {
                if connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
                else if connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
            }
        }
    }
    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView(); view.preview.session = session; view.preview.videoGravity = .resizeAspect
        return view
    }
    func updateUIView(_ uiView: PreviewView, context: Context) {}
}
