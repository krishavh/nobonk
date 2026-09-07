import AVFoundation
import Foundation
import OSLog

/// Session configuration/start/stop and capture callbacks share one serial queue.
final class CaptureProbe: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let journal = ProbeJournal()
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "ai.genwhy.nobonk.camerapiplab.capture", qos: .userInitiated)
    private let logger = Logger(subsystem: "ai.genwhy.nobonk.camerapiplab", category: "Capture")
    private var configured = false
    private var token: UInt64 = 0
    private var observers: [NSObjectProtocol] = []
    private var lastLog = 0.0

    override init() {
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
                    guard let self else { return }
                    self.journal.update(self.token) {
                        if let reason { $0.interruptionReason = reason }
                        $0.interruption = description
                    }
                    self.logger.notice("\(description, privacy: .public)")
                    if name == AVCaptureSession.runtimeErrorNotification {
                        self.journal.invalidate(message: "Camera error — Stop, then retry")
                        self.session.stopRunning()
                    }
                }
            })
        }
    }
    deinit { for observer in observers { NotificationCenter.default.removeObserver(observer) } }

    func start() {
        let next = journal.begin()
        queue.async { [self] in
            guard journal.accepts(next) else { return }
            token = next
            lastLog = 0
            do {
                if !configured { try configure() }
                // Deliberately observe the default. No entitlement or enable override.
                let supported = session.isMultitaskingCameraAccessSupported
                let enabled = session.isMultitaskingCameraAccessEnabled
                journal.update(next) { $0.supported = supported; $0.enabled = enabled }
                logger.notice("Capability supported=\(supported), enabled=\(enabled)")
                guard journal.accepts(next) else { return }
                session.startRunning()
                guard journal.accepts(next) else { session.stopRunning(); return }
                journal.update(next) { $0.status = session.isRunning ? "Camera session running" : "Camera did not start" }
                if !session.isRunning { journal.invalidate(message: "Camera did not start — tap Start again") }
            } catch {
                if journal.accepts(next) { journal.invalidate(message: "Camera unavailable: \(error.localizedDescription)") }
            }
        }
    }
    func stop(message: String = "Stopped by user") {
        journal.invalidate(message: message)
        queue.async { [self] in
            if session.isRunning { session.stopRunning() }
            logger.notice("Stopped; old frame generation invalidated")
        }
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
            } catch { /* Default capture cadence remains a valid probe. */ }
        }
        configured = true
    }
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard journal.accepts(token), CMSampleBufferDataIsReady(sampleBuffer) else { return }
        let now = ProcessInfo.processInfo.systemUptime
        journal.append(sampleBuffer, token: token, now: now)
        if now - lastLog >= 1 {
            lastLog = now
            let s = journal.snapshot()
            logger.notice("frames=\(s.frameCount) pts=\(s.lastPTS) background=\(s.inBackground) backgroundFrames=\(s.backgroundFrames) after1s=\(s.backgroundFramesAfterOneSecond)")
        }
    }
    private enum ProbeError: LocalizedError {
        case noCamera
        var errorDescription: String? { "Rear camera unavailable" }
    }
}
