import AVFoundation
import Foundation
import Vision

/// Only the main actor attaches the layer; only the capture queue configures,
/// starts, stops or runs Vision. The immutable token must be checked at both hops.
final class MessageCameraPreviewHandle: @unchecked Sendable {
    let token: MessageCameraToken
    let session: AVCaptureSession
    init(token: MessageCameraToken, session: AVCaptureSession) {
        self.token = token; self.session = session
    }
}

final class MessageCameraCapture: @unchecked Sendable {
    let journal: MessageCameraJournal
    private let queue = DispatchQueue(label: "ai.genwhy.nobonk.messageslab.capture", qos: .userInitiated)
    private var run: MessageCameraRun?
    private let preview: @Sendable (MessageCameraPreviewHandle) -> Void

    init(journal: MessageCameraJournal, preview: @escaping @Sendable (MessageCameraPreviewHandle) -> Void) {
        self.journal = journal; self.preview = preview
    }
    func start(_ token: MessageCameraToken) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: prepare(token)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard let self, self.journal.accepts(token) else { return }
                if granted { self.prepare(token) }
                else { self.fail(token, reason: "Camera permission denied — camera is off") }
            }
        default: fail(token, reason: "Camera permission unavailable — enable it in Settings, then start again")
        }
    }
    private func prepare(_ token: MessageCameraToken) {
        queue.async { [self] in
            guard journal.markStarting(token) else { return }
            run?.stop()
            let next = MessageCameraRun(token: token, journal: journal, queue: queue, preview: preview)
            run = next
            next.start()
            if !journal.accepts(token) { next.stop(); run = nil }
        }
    }
    func fail(_ token: MessageCameraToken, reason: String) {
        _ = journal.stop(at: ProcessInfo.processInfo.systemUptime, reason: reason, token: token)
        tearDown(token)
    }
    /// Call after the journal has synchronously invalidated the token. Native
    /// stopRunning may block, so teardown is serialized off the UI thread. No
    /// correctness/UI evidence relies on this async work completing before exit.
    func tearDown(_ token: MessageCameraToken?) {
        guard let token else { return }
        queue.async { [self] in
            guard run?.token == token else { return }
            run?.stop(); run = nil
        }
    }
}

private final class MessageCameraRun: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let token: MessageCameraToken
    private let journal: MessageCameraJournal
    private let queue: DispatchQueue
    private let session = AVCaptureSession()
    private let request = VNDetectHumanRectanglesRequest()
    private let preview: @Sendable (MessageCameraPreviewHandle) -> Void
    private var observers: [NSObjectProtocol] = []
    private var lastInference = -Double.infinity

    init(token: MessageCameraToken, journal: MessageCameraJournal, queue: DispatchQueue,
         preview: @escaping @Sendable (MessageCameraPreviewHandle) -> Void) {
        self.token = token; self.journal = journal; self.queue = queue; self.preview = preview
        super.init()
        request.upperBodyOnly = false
        for name in [AVCaptureSession.wasInterruptedNotification, AVCaptureSession.runtimeErrorNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: session, queue: nil) { [weak self] note in
                // Receipt and cancellation occur before dispatch, not when the
                // serialized camera queue eventually handles the interruption.
                let now = ProcessInfo.processInfo.systemUptime
                guard let self else { return }
                let code = (note.userInfo?[AVCaptureSessionInterruptionReasonKey] as? NSNumber)?.intValue
                let reason = name == AVCaptureSession.wasInterruptedNotification
                    ? "Camera interrupted (\(code.map(String.init) ?? "unknown")) — start again"
                    : "Camera runtime error — start again"
                guard self.journal.stop(at: now, reason: reason, token: self.token) != nil else { return }
                self.queue.async { [weak self] in self?.stop() }
            })
        }
    }
    deinit { observers.forEach(NotificationCenter.default.removeObserver) }
    func start() {
        guard journal.accepts(token) else { return }
        do {
            try configure()
            guard journal.accepts(token) else { return }
            preview(MessageCameraPreviewHandle(token: token, session: session))
            session.startRunning()
            guard journal.accepts(token) else { stop(); return }
            if !session.isRunning { fail("Camera could not start in this Messages presentation") }
        } catch { fail("Camera configuration unavailable — start again") }
    }
    func stop() {
        for output in session.outputs.compactMap({ $0 as? AVCaptureVideoDataOutput }) {
            output.setSampleBufferDelegate(nil, queue: nil)
        }
        if session.isRunning { session.stopRunning() }
    }
    private func fail(_ reason: String) {
        _ = journal.stop(at: ProcessInfo.processInfo.systemUptime, reason: reason, token: token)
        stop()
    }
    private func configure() throws {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else { throw CameraFailure.unavailable }
        let input = try AVCaptureDeviceInput(device: device)
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        // Video only: no audio-session setup, microphone input or background mode.
        session.automaticallyConfiguresApplicationAudioSession = false
        session.sessionPreset = session.canSetSessionPreset(.vga640x480) ? .vga640x480 : .medium
        guard session.canAddInput(input), session.canAddOutput(output) else { throw CameraFailure.unavailable }
        session.addInput(input); session.addOutput(output)
        output.setSampleBufferDelegate(self, queue: queue)
        guard let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(90) else { throw CameraFailure.unavailable }
        connection.videoRotationAngle = 90 // physically rotate pixels to portrait
        if device.activeFormat.videoSupportedFrameRateRanges.contains(where: { $0.minFrameRate <= 15 && $0.maxFrameRate >= 15 }) {
            try device.lockForConfiguration()
            device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 15)
            device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 15)
            device.unlockForConfiguration()
        }
    }
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let receipt = ProcessInfo.processInfo.systemUptime
        guard CMSampleBufferDataIsReady(sampleBuffer),
              journal.receiveFrame(token, at: receipt, pts: CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds) else {
            if !journal.accepts(token) { stop() }
            return
        }
        guard receipt - lastInference >= 0.25, journal.accepts(token),
              let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastInference = receipt
        do {
            try VNImageRequestHandler(cvPixelBuffer: pixels, orientation: .up).perform([request])
            let finished = ProcessInfo.processInfo.systemUptime
            let count = request.results?.filter { $0.confidence >= 0.5 }.count ?? 0
            // Stop or dismissal during inference invalidates the result here.
            _ = journal.finishInference(token, at: finished, people: count)
        } catch { fail("People scan unavailable — camera stopped") }
    }
    private enum CameraFailure: Error { case unavailable }
}
