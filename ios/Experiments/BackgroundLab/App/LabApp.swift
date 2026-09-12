import SwiftUI
@preconcurrency import BackgroundTasks
import Vision
import CoreML
import OSLog

// An isolated developer experiment. No camera, microphone, network or private API.
// The finite workload is real Vision processing of generated, non-person frames.
@main
struct LabApp: App {
    @StateObject private var probe = ProcessingProbe()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("NoBonk / Background Lab").font(.largeTitle.bold())
                    Text("TEST FRAMES ONLY").font(.headline).foregroundStyle(.orange)
                    Text("This measures background CPU processing. It never opens a camera or detects your surroundings.")
                    Text("Tap Start, then switch apps. Return to inspect actual completed work. System cancellation and resource limits are expected outcomes.")
                    Text(probe.status).font(.headline).accessibilityIdentifier("probeStatus")
                    ProgressView(value: Double(probe.completed), total: Double(probe.targetCount))
                    Text("Analyzed: \(probe.completed) / \(probe.targetCount)\nWhile scene was backgrounded: \(probe.backgroundCompleted)")
                        .monospacedDigit().accessibilityIdentifier("probeCounts")
                    Button("Start test-frame analysis") { probe.start() }
                        .buttonStyle(.borderedProminent).disabled(probe.active)
                    Button("Run foreground baseline") { probe.startBaseline() }
                        .buttonStyle(.bordered).disabled(probe.active)
                    Button("Stop and cancel") { probe.stop() }
                        .buttonStyle(.bordered).disabled(!probe.active)
                    Text("No dummy audio, VoIP, location or camera background modes. No automatic retry. GPU not requested. Counts are local and reset with the next test.").font(.footnote)
                }.padding(24)
            }
            .onChange(of: phase) { _, value in probe.sceneChanged(background: value == .background) }
        }
    }
}

final class ProbeRun: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
}
final class ProbeLifecycle: @unchecked Sendable {
    private let lock = NSLock()
    private var background = false
    func setBackground(_ value: Bool) { lock.lock(); background = value; lock.unlock() }
    var isBackground: Bool { lock.lock(); defer { lock.unlock() }; return background }
}

@MainActor
final class ProcessingProbe: ObservableObject {
    static let frameCount = 2400
    @Published var status = "Ready. No task requested."
    @Published var active = false
    @Published var targetCount = ProcessingProbe.frameCount
    @Published var completed = 0
    @Published var backgroundCompleted = 0
    let lifecycle = ProbeLifecycle()
    private var identifier: String?
    private var currentRun: ProbeRun?
    private var baseline = false
    private let log = Logger(subsystem: "ai.genwhy.nobonk.backgroundlab", category: "Probe")

    func start() {
        guard !active else { return }
        let id = "ai.genwhy.nobonk.backgroundlab.analysis." + UUID().uuidString
        let run = ProbeRun()
        identifier = id; currentRun = run; completed = 0; backgroundCompleted = 0
        baseline = false; targetCount = Self.frameCount
        // Continued-processing handlers may be registered after ordinary launch.
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: .main) { [weak self] rawTask in
            guard let task = rawTask as? BGContinuedProcessingTask else { rawTask.setTaskCompleted(success: false); return }
            MainActor.assumeIsolated {
                guard let self, self.identifier == id, !run.isCancelled else {
                    task.setTaskCompleted(success: false); return
                }
                self.execute(task, id: id, run: run)
            }
        }
        guard registered else { status = "Task registration unavailable"; identifier = nil; currentRun = nil; return }
        let request = BGContinuedProcessingTaskRequest(identifier: id, title: "Analyze test frames", subtitle: "No camera; finite Vision workload")
        request.strategy = .fail // No surprise queued work after the user's request.
        request.requiredResources = [] // Default CPU/network resource class; no network used.
        do {
            active = true; status = "Requested. Waiting for actual launch."
            try BGTaskScheduler.shared.submit(request)
            log.notice("Submitted finite CPU analysis request")
        } catch {
            run.cancel(); active = false; identifier = nil; currentRun = nil
            let error = error as NSError
            status = "System declined: \(error.domain) code \(error.code). No test ran."
            log.notice("Submission declined: \(error.domain, privacy: .public) code \(error.code)")
        }
    }
    func sceneChanged(background: Bool) {
        lifecycle.setBackground(background)
        if background && baseline && active { stop() }
    }
    func startBaseline() {
        guard !active else { return }
        let id = "foreground-" + UUID().uuidString
        let run = ProbeRun()
        identifier = id; currentRun = run; completed = 0; backgroundCompleted = 0
        baseline = true; targetCount = 100; active = true
        execute(nil, id: id, run: run)
    }
    func stop() {
        currentRun?.cancel()
        if let identifier, !baseline { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier) }
        // The worker owns final completion; stale UI callbacks are rejected by id.
        identifier = nil; currentRun = nil; active = false; status = "Stopped. Pending results discarded."
        log.notice("User Stop")
    }
    private func execute(_ task: BGContinuedProcessingTask?, id: String, run: ProbeRun) {
        task?.progress.totalUnitCount = Int64(targetCount)
        task?.expirationHandler = { run.cancel() }
        status = baseline ? "Foreground baseline only; leaving the app cancels it" : "Granted: running real Vision work on generated test frames"
        let lifecycle = self.lifecycle
        let count = targetCount
        let log = self.log
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let request = VNDetectHumanRectanglesRequest()
            // Intentionally restrict the experiment to CPU to avoid inferring a
            // background GPU permission from a successful foreground run.
            request.upperBodyOnly = false
            var done = 0, background = 0
            var failure: String?
            do {
                let stages = try request.supportedComputeStageDevices
                guard !stages.isEmpty else { throw NSError(domain: "CPUConfiguration", code: 1) }
                for (stage, devices) in stages {
                    guard let cpu = devices.first(where: { if case .cpu = $0 { return true }; return false }) else {
                        throw NSError(domain: "CPUConfiguration", code: 2)
                    }
                    request.setComputeDevice(cpu, for: stage)
                }
            } catch { failure = "CPU configuration unavailable: \(error)" }
            let started = ProcessInfo.processInfo.systemUptime
            for index in 0..<count {
                if run.isCancelled || failure != nil { break }
                do {
                    try autoreleasepool {
                        let buffer = try Self.fixture(index)
                        try VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .up).perform([request])
                    }
                    guard !run.isCancelled else { break }
                    done += 1
                    if lifecycle.isBackground { background += 1 }
                    task?.progress.completedUnitCount = Int64(done)
                    if done % 25 == 0 || done == count {
                        let snapshotDone = done, snapshotBackground = background
                        DispatchQueue.main.async {
                            guard let self, self.identifier == id, !run.isCancelled else { return }
                            self.completed = snapshotDone; self.backgroundCompleted = snapshotBackground
                        }
                    }
                    if done % 200 == 0 { log.notice("Completed \(done) test frames; \(background) while backgrounded") }
                } catch { failure = String(describing: error); break }
            }
            let success = done == count && failure == nil && !run.isCancelled
            task?.setTaskCompleted(success: success)
            let finalDone = done, finalBackground = background
            let elapsed = ProcessInfo.processInfo.systemUptime - started
            let finalStatus = success ? "Completed \(done) frames in \(Int(elapsed)) seconds." : (failure == nil ? "Expired or cancelled after \(done) frames." : "Vision failed: \(failure!)")
            log.notice("Finished success=\(success), frames=\(done), background frames=\(background)")
            DispatchQueue.main.async {
                guard let self, self.identifier == id else { return }
                self.completed = finalDone; self.backgroundCompleted = finalBackground
                self.active = false; self.identifier = nil; self.currentRun = nil; self.status = finalStatus
            }
        }
    }
    nonisolated private static func fixture(_ index: Int) throws -> CVPixelBuffer {
        var result: CVPixelBuffer?
        let error = CVPixelBufferCreate(nil, 640, 360, kCVPixelFormatType_32BGRA, nil, &result)
        guard error == kCVReturnSuccess, let buffer = result else { throw NSError(domain: "Fixture", code: Int(error)) }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { throw NSError(domain: "Fixture", code: -1) }
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        for y in 0..<360 {
            let row = base.advanced(by: y * stride).assumingMemoryBound(to: UInt8.self)
            for x in 0..<640 {
                let value = UInt8(((x / 32 + y / 32 + index) % 2) == 0 ? 48 : 192)
                row[x * 4] = value; row[x * 4 + 1] = value; row[x * 4 + 2] = value; row[x * 4 + 3] = 255
            }
        }
        return buffer
    }
}
