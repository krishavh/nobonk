import AVFoundation
import Vision
import SwiftUI
import AudioToolbox

// Capture and Vision work stay on one serial queue; UI never receives image data.
final class CameraEngine: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "ai.genwhy.nobonk.camera", qos: .userInitiated)
    private var configured = false
    private var lastFrame = 0.0
    var onBoxes: (([PersonBox]) -> Void)?
    var onState: ((Bool, String) -> Void)?
    func start() {
        queue.async { [self] in
            do {
                if !configured { try configure() }
                session.startRunning()
                onState?(session.isRunning, session.isRunning ? "Scanning for people" : "Camera unavailable")
            } catch { onState?(false, "Camera unavailable. Check camera access in Settings.") }
        }
    }
    func stop() {
        queue.async { [self] in
            if session.isRunning { session.stopRunning() }
            lastFrame = 0
        }
    }
    private func configure() throws {
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else { throw CameraError.unavailable }
        let input = try AVCaptureDeviceInput(device: camera)
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .hd1280x720
        guard session.canAddInput(input) else { throw CameraError.unavailable }
        session.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        guard session.canAddOutput(output) else {
            session.removeInput(input)
            throw CameraError.unavailable
        }
        session.addOutput(output)
        output.setSampleBufferDelegate(self, queue: queue)
        if let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
        configured = true
    }
    func captureOutput(_ output: AVCaptureOutput, didOutput sample: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastFrame >= 0.15, let buffer = CMSampleBufferGetImageBuffer(sample) else { return }
        lastFrame = now
        let request = VNDetectHumanRectanglesRequest()
        request.upperBodyOnly = false
        do {
            try VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .up).perform([request])
            let boxes = (request.results ?? []).enumerated().map { index, item in
                let r = item.boundingBox
                return PersonBox(id: index, x: r.minX, y: 1-r.maxY, width: r.width, height: r.height, confidence: Double(item.confidence))
            }.filter(\.usable)
            onBoxes?(boxes)
        } catch { onBoxes?([]); onState?(false, "Detection interrupted. Tap Start to retry."); session.stopRunning() }
    }
    private enum CameraError: Error { case unavailable }
}

@MainActor
final class CameraModel: ObservableObject {
    let engine = CameraEngine()
    @Published var boxes: [PersonBox] = []
    @Published var status = "Ready when you are"
    @Published var running = false
    @Published var denied = false
    @Published var sound = true
    @Published var haptics = true
    @Published var alertUntil = Date.distantPast
    private var wanted = false
    private var policy = DetectionPolicy()
    private var observers: [NSObjectProtocol] = []
    init() {
        engine.onBoxes = { [weak self] boxes in
            Task { @MainActor in
                guard let self, self.wanted, self.running, UIApplication.shared.applicationState == .active else { return }
                self.boxes = boxes
                if self.policy.evaluate(boxes, time: ProcessInfo.processInfo.systemUptime) == .personAhead {
                    self.alertUntil = Date().addingTimeInterval(2)
                    if self.haptics { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
                    if self.sound { AudioServicesPlaySystemSound(1104) }
                }
            }
        }
        engine.onState = { [weak self] running, status in
            Task { @MainActor in
                guard let self, self.wanted else { return }
                self.running = running
                self.status = status
                if !running { self.boxes = []; self.policy.reset() }
            }
        }
        for name in [AVCaptureSession.wasInterruptedNotification, AVCaptureSession.runtimeErrorNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: engine.session, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.stop(message: "Camera interrupted — tap Start to retry") }
            })
        }
    }
    deinit { for observer in observers { NotificationCenter.default.removeObserver(observer) } }
    func start() {
        guard !wanted else { return }
        denied = false
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            guard UIApplication.shared.applicationState == .active else { return }
            wanted = true; status = "Starting camera…"; policy.reset(); engine.start()
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
    func stop(message: String = "Paused — not scanning") {
        wanted = false; running = false; boxes = []; policy.reset(); alertUntil = .distantPast
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
            if let connection = preview.connection, connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
        }
    }
    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView(); view.preview.session = session; view.preview.videoGravity = .resizeAspect
        return view
    }
    func updateUIView(_ uiView: PreviewView, context: Context) {}
}
