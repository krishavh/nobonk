import AVFoundation
import Foundation
import OSLog

/// The owner serializes run creation/teardown. Each producer has immutable identity,
/// so an old notification or queued sample cannot be attributed to a newer Start.
final class CaptureProbe: @unchecked Sendable {
    let journal = ProbeJournal()
    private let queue = DispatchQueue(label: "ai.genwhy.nobonk.camerapiplab.capture", qos: .userInitiated)
    private var run: CaptureRun?

    @discardableResult
    func start() -> UInt64 {
        let generation = journal.begin()
        queue.async { [self] in
            guard journal.accepts(generation) else { return }
            run?.stop()
            let next = CaptureRun(generation: generation, journal: journal, queue: queue)
            run = next
            next.start()
        }
        return generation
    }
    func stop(generation: UInt64? = nil, message: String = "Stopped by user") {
        let stopped = generation ?? journal.snapshot().generation
        guard journal.invalidate(generation: stopped, message: message) else { return }
        queue.async { [self] in
            guard run?.generation == stopped else { return }
            run?.stop()
            run = nil
        }
    }
}

/// A new session AND output delegate per run. Immutable generation is captured by
/// every notification and frame callback; none reads the current owner's token.
private final class CaptureRun: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    private let epoch: CaptureEpoch
    var generation: UInt64 { epoch.generation }
    private let journal: ProbeJournal
    private let queue: DispatchQueue
    private let session = AVCaptureSession()
    private let logger = Logger(subsystem: "ai.genwhy.nobonk.camerapiplab", category: "Capture")
    private var observers: [NSObjectProtocol] = []
    private var lastLog = 0.0

    init(generation: UInt64, journal: ProbeJournal, queue: DispatchQueue) {
        self.epoch = CaptureEpoch(generation: generation, journal: journal)
        self.journal = journal; self.queue = queue
        super.init()
        for name in [AVCaptureSession.wasInterruptedNotification,
                     AVCaptureSession.interruptionEndedNotification,
                     AVCaptureSession.runtimeErrorNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: session, queue: nil) { [weak self] note in
                guard let self else { return }
                let reason = (note.userInfo?[AVCaptureSessionInterruptionReasonKey] as? NSNumber)?.intValue
                let error = (note.userInfo?[AVCaptureSessionErrorKey] as? NSError)?.localizedDescription
                let description = name == AVCaptureSession.wasInterruptedNotification
                    ? "Interrupted: reason \(reason.map(String.init) ?? "unknown")"
                    : (name == AVCaptureSession.runtimeErrorNotification ? "Runtime error: \(error ?? "unknown")" : "Interruption ended; OS may resume")
                self.queue.async { [weak self] in
                    guard let self, self.journal.accepts(self.generation) else { return }
                    self.journal.update(self.generation) {
                        if let reason { $0.interruptionReason = reason }
                        $0.interruption = description
                    }
                    self.logger.notice("run=\(self.generation) \(description, privacy: .public)")
                    if name == AVCaptureSession.runtimeErrorNotification,
                       self.epoch.fail("Camera error — tap Start to retry") {
                        self.session.stopRunning()
                    }
                }
            })
        }
    }
    deinit { for observer in observers { NotificationCenter.default.removeObserver(observer) } }
    func start() {
        guard journal.accepts(generation) else { return }
        do {
            try configure()
            // Observe the default; no entitlement or enable override.
            let supported = session.isMultitaskingCameraAccessSupported
            let enabled = session.isMultitaskingCameraAccessEnabled
            journal.update(generation) { $0.supported = supported; $0.enabled = enabled }
            logger.notice("run=\(self.generation) supported=\(supported), enabled=\(enabled)")
            guard journal.accepts(generation) else { return }
            session.startRunning()
            guard journal.accepts(generation) else { session.stopRunning(); return }
            journal.update(generation) { $0.status = session.isRunning ? "Camera session running" : "Camera did not start" }
            if !session.isRunning { epoch.fail("Camera did not start — tap Start again") }
        } catch { epoch.fail("Camera unavailable: \(error.localizedDescription)") }
    }
    func stop() {
        if session.isRunning { session.stopRunning() }
        for output in session.outputs.compactMap({ $0 as? AVCaptureVideoDataOutput }) {
            output.setSampleBufferDelegate(nil, queue: nil)
        }
        logger.notice("run=\(self.generation) producer stopped")
    }
    private func configure() throws {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            throw ProbeError.noCamera
        }
        let input = try AVCaptureDeviceInput(device: device)
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = session.canSetSessionPreset(.vga640x480) ? .vga640x480 : .medium
        guard session.canAddInput(input) else { throw ProbeError.noCamera }
        session.addInput(input)
        guard session.canAddOutput(output) else { session.removeInput(input); throw ProbeError.noCamera }
        session.addOutput(output)
        output.setSampleBufferDelegate(self, queue: queue)
        if let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
        }
        if device.activeFormat.videoSupportedFrameRateRanges.contains(where: { $0.minFrameRate <= 15 && $0.maxFrameRate >= 15 }) {
            do {
                try device.lockForConfiguration()
                device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 15)
                device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 15)
                device.unlockForConfiguration()
            } catch { /* Default cadence remains a valid probe. */ }
        }
    }
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard journal.accepts(generation), CMSampleBufferDataIsReady(sampleBuffer) else { return }
        let now = ProcessInfo.processInfo.systemUptime
        epoch.append(sampleBuffer, now: now)
        if now - lastLog >= 1 {
            lastLog = now
            let s = journal.snapshot()
            logger.notice("run=\(self.generation) frames=\(s.frameCount) pts=\(s.lastPTS) background=\(s.inBackground) backgroundFrames=\(s.backgroundFrames) after1s=\(s.backgroundFramesAfterOneSecond)")
        }
    }
    private enum ProbeError: LocalizedError {
        case noCamera
        var errorDescription: String? { "Rear camera unavailable" }
    }
}
