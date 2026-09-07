import CoreVideo
import Darwin
import Foundation
import XCTest
@testable import NoBonk

/// Explicitly enabled, synthetic, hosted device measurement. This test never opens
/// a camera or changes a permission. It is not a detection-accuracy/FPS test.
final class FastDeviceBenchmarkTests: XCTestCase {
    func testFastDetectorSyntheticDevicePerformance() async throws {
        guard ProcessInfo.processInfo.environment["NOBONK_RUN_DEVICE_BENCH"] == "1" else {
            throw XCTSkip("Opt in with TEST_RUNNER_NOBONK_RUN_DEVICE_BENCH=1; run Release on a physical iPhone.")
        }
        #if targetEnvironment(simulator)
        throw XCTSkip("Physical iPhone only; simulator timings are not phone measurements.")
        #else
        #if DEBUG
        throw XCTSkip("Use Release with ENABLE_TESTABILITY=YES; Debug scalar preprocessing is not representative.")
        #else
        guard FastObjectDetector.assetURL != nil else { throw XCTSkip("The exact SHA-pinned local model must be bundled first.") }
        let data = try await Task.detached(priority: .userInitiated) { try Self.measure() }.value
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
        attachment.name = "NoBonk-Fast-synthetic-device-benchmark.json"
        attachment.lifetime = .keepAlways
        add(attachment)
        let text = String(decoding: data, as: UTF8.self)
        print("NOBONK_DEVICE_BENCH_JSON \(text)")
        let report = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        XCTAssertEqual(report["parity_passed"] as? Bool, true, "Synthetic graph reference parity exceeded smoke-test bounds; inspect JSON.")
        #endif
        #endif
    }

    private struct Reference: Decodable {
        struct Sample: Decodable { let index: Int; let value: Float }
        struct Pattern: Decodable { let name: String; let samples: [Sample] }
        let sha256: String; let runtime: String; let patterns: [Pattern]
    }
    private struct PhaseSamples {
        var prepare: [Double] = [], inference: [Double] = [], decode: [Double] = [], total: [Double] = []
        mutating func add(_ timing: DetectorTiming, totalMS: Double) {
            prepare.append(timing.preprocessMS); inference.append(timing.inferenceMS)
            decode.append(timing.decodeMS); total.append(totalMS)
        }
        var report: [String: Any] {
            ["preprocess_ms": Self.stats(prepare), "inference_ms": Self.stats(inference),
             "decode_ms": Self.stats(decode), "total_ms": Self.stats(total)]
        }
        static func stats(_ values: [Double]) -> [String: Double] {
            let sorted = values.sorted(), n = sorted.count
            precondition(n > 0)
            let median = n.isMultiple(of: 2) ? (sorted[n/2-1] + sorted[n/2]) / 2 : sorted[n/2]
            return ["median": median, "p95": sorted[max(0, Int(ceil(Double(n) * 0.95)) - 1)]]
        }
    }
    private static func measure() throws -> Data {
        let bundle = Bundle(for: FastDeviceBenchmarkTests.self)
        let fixture = bundle.url(forResource: "fast-graph-reference", withExtension: "json")!
        let reference = try JSONDecoder().decode(Reference.self, from: Data(contentsOf: fixture))
        guard reference.sha256 == FastModelContract.sha256 else { throw BenchError.fixture }
        var machine = utsname(); uname(&machine)
        let model = withUnsafeBytes(of: &machine.machine) { bytes in
            String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)
        }
        let before = environment()
        var runs: [[String: Any]] = []
        var allParity = true
        // Each autorelease pool owns its ONLY detector. No session or output is
        // returned; ARC and ObjC temporaries are released before the next run.
        for coreML in [false, true] {
            for creation in 0..<2 {
                let run = try autoreleasepool { try measureSession(coreML: coreML, creation: creation, reference: reference) }
                allParity = allParity && (run["parity_passed"] as? Bool == true)
                runs.append(run)
            }
        }
        return try JSONSerialization.data(withJSONObject: [
            "schema_version": 1, "kind": "synthetic CVPixelBuffer throughput probe; not camera FPS or effectiveness",
            "date_utc": ISO8601DateFormatter().string(from: Date()), "device_model": model,
            "os": ProcessInfo.processInfo.operatingSystemVersionString, "runtime_pinned": FastObjectDetector.runtimeVersion,
            "model_sha256": FastModelContract.sha256, "reference_runtime": reference.runtime,
            "configuration": "Release; same production FastObjectDetector; no camera; no per-frame log",
            "environment_before": before, "environment_after": environment(), "runs": runs, "parity_passed": allParity,
            "cache_note": "First creation may already have OS/model cache. Second creation follows explicit previous-session release; caches are not deleted.",
            "measurement_note": "3 warm frames, then 20 measured frames per session, alternating gray114/RGB gradient. Warm frames inspect fixture samples; measured frames do not. p95 is nearest-rank. No thermal or clock controls."], options: [.sortedKeys])
    }
    private static func measureSession(coreML: Bool, creation: Int, reference: Reference) throws -> [String: Any] {
        let before = environment()
        let setupStart = ProcessInfo.processInfo.systemUptime
        let detector = try FastObjectDetector(preferCoreML: coreML)
        let setupWallMS = (ProcessInfo.processInfo.systemUptime - setupStart) * 1000
        let buffers = try reference.patterns.map { try pixels(pattern: $0.name) }
        guard buffers.count == 2 else { throw BenchError.fixture }
        var maximumBoxDelta: Float = 0, maximumScoreDelta: Float = 0
        var checkedSamples = 0
        for warm in 0..<3 {
            let pattern = warm % 2
            _ = try detector.detect(buffers[pattern], rotation: .upright) { values in
                for sample in reference.patterns[pattern].samples {
                    let delta = abs(values[sample.index] - sample.value)
                    if sample.index < 4 * FastModelContract.anchors { maximumBoxDelta = max(maximumBoxDelta, delta) }
                    else { maximumScoreDelta = max(maximumScoreDelta, delta) }
                    checkedSamples += 1
                }
            }
        }
        var samples = PhaseSamples(), detections = 0
        for frame in 0..<20 {
            try autoreleasepool {
                let start = ProcessInfo.processInfo.systemUptime
                let (boxes,timing) = try detector.detect(buffers[frame % 2], rotation: .upright)
                let total = (ProcessInfo.processInfo.systemUptime - start) * 1000
                samples.add(timing,totalMS: total); detections += boxes.count
            }
        }
        return ["requested": coreML ? "CoreML-preferred" : "CPU-explicit", "configured": detector.configuration,
                "creation": creation == 0 ? "first_in_probe" : "second_cache_eligible", "setup_wall_ms": setupWallMS,
                "setup_internal_ms": detector.setupMS, "warm_frames": 3, "measured_frames": 20, "phases": samples.report,
                "environment_before": before, "environment_after": environment(), "measured_detection_count": detections,
                "checked_reference_samples": checkedSamples, "maximum_box_delta_pixels": maximumBoxDelta,
                "maximum_score_delta": maximumScoreDelta, "parity_passed": maximumBoxDelta <= 1 && maximumScoreDelta <= 0.005]
    }
    private static func pixels(pattern: String) throws -> CVPixelBuffer {
        guard pattern == "gray114" || pattern == "rgbGradient" else { throw BenchError.fixture }
        var pixels: CVPixelBuffer?
        guard CVPixelBufferCreate(kCFAllocatorDefault,416,416,kCVPixelFormatType_32BGRA,nil,&pixels) == kCVReturnSuccess,
              let pixels, CVPixelBufferLockBaseAddress(pixels, []) == kCVReturnSuccess else { throw BenchError.pixels }
        defer { CVPixelBufferUnlockBaseAddress(pixels, []) }
        guard let base = CVPixelBufferGetBaseAddress(pixels) else { throw BenchError.pixels }
        let bytes = base.assumingMemoryBound(to: UInt8.self), stride = CVPixelBufferGetBytesPerRow(pixels)
        for y in 0..<416 { for x in 0..<416 {
            let i = y * stride + x * 4
            if pattern == "gray114" { bytes[i] = 114; bytes[i+1] = 114; bytes[i+2] = 114 }
            else { bytes[i] = UInt8((x+y)%256); bytes[i+1] = UInt8(y%256); bytes[i+2] = UInt8(x%256) }
            bytes[i+3] = 255
        }}
        return pixels
    }
    private static func environment() -> [String: Any] {
        let info = ProcessInfo.processInfo
        let thermal: String
        switch info.thermalState {
        case .nominal: thermal = "nominal"
        case .fair: thermal = "fair"
        case .serious: thermal = "serious"
        case .critical: thermal = "critical"
        @unknown default: thermal = "unknown"
        }
        return ["thermal": thermal, "low_power": info.isLowPowerModeEnabled]
    }
    private enum BenchError: Error { case fixture, pixels }
}

/// Opt-in sustained synthetic run. Production cadence is exercised against live
/// pressure/power readings; camera capture, display cost and energy are excluded.
final class FastSustainedBenchmarkTests: XCTestCase {
    func testFastDetectorAtProductionCadenceForThreeMinutes() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard environment["NOBONK_RUN_SUSTAINED_BENCH"] == "1" else {
            throw XCTSkip("Opt in with TEST_RUNNER_NOBONK_RUN_SUSTAINED_BENCH=1; physical Release only.")
        }
        #if targetEnvironment(simulator)
        throw XCTSkip("Sustained phone probe must not run on Simulator.")
        #else
        #if DEBUG
        throw XCTSkip("Use Release with ENABLE_TESTABILITY=YES.")
        #else
        guard FastObjectDetector.assetURL != nil else { throw XCTSkip("Bundle the exact local model before the sustained probe.") }
        let seconds = Double(environment["NOBONK_SUSTAINED_SECONDS"] ?? "180") ?? 0
        guard seconds.isFinite, (30...600).contains(seconds) else {
            XCTFail("NOBONK_SUSTAINED_SECONDS must be 30...600, default180."); return
        }
        let worker = Task.detached(priority: .userInitiated) { try SustainedFastProbe.run(seconds: seconds) }
        let data = try await withTaskCancellationHandler(operation: { try await worker.value }, onCancel: { worker.cancel() })
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
        attachment.name = "NoBonk-Fast-sustained-device-benchmark.json"
        attachment.lifetime = .keepAlways
        add(attachment)
        print("NOBONK_SUSTAINED_BENCH_JSON \(String(decoding: data, as: UTF8.self))")
        let report = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        XCTAssertGreaterThan(report["admitted_frames"] as? Int ?? 0, 0)
        #endif
        #endif
    }
}

private enum SustainedFastProbe {
    private struct Window {
        let start: Double
        var admitted = 0
        var prepare: [Double] = [], infer: [Double] = [], decode: [Double] = [], total: [Double] = [], intervals: [Double] = []
        var thermalCounts: [String: Int] = [:], lowPowerFrames = 0
        var firstRSS: UInt64?, lastRSS: UInt64?, peakRSS: UInt64?
        // At <=12 admitted frames/s, 30 seconds normally retains <=360 samples.
        // The hard cap also prevents unexpected scheduler behavior growing memory.
        mutating func record(timing: DetectorTiming, totalMS: Double, interval: Double, state: PressureState) {
            admitted += 1
            thermalCounts[state.name, default: 0] += 1
            if state.lowPower { lowPowerFrames += 1 }
            if total.count < 1024 {
                prepare.append(timing.preprocessMS); infer.append(timing.inferenceMS)
                decode.append(timing.decodeMS); total.append(totalMS); intervals.append(interval * 1000)
            }
        }
        mutating func recordRSS(_ value: UInt64?) {
            guard let value else { return }
            if firstRSS == nil { firstRSS = value }
            lastRSS = value; peakRSS = max(peakRSS ?? value, value)
        }
        func report(end: Double) -> [String: Any] {
            ["start_s": start, "end_s": end, "duration_s": end-start, "admitted_frames": admitted,
             "retained_timing_samples": total.count, "preprocess_ms": stats(prepare), "inference_ms": stats(infer),
             "decode_ms": stats(decode), "total_ms": stats(total), "target_interval_ms": stats(intervals),
             "thermal_admitted_frames": thermalCounts, "low_power_admitted_frames": lowPowerFrames,
             "rss_first_sample_bytes": firstRSS.map { $0 as Any } ?? NSNull(), "rss_last_sample_bytes": lastRSS.map { $0 as Any } ?? NSNull(),
             "sampled_peak_rss_bytes": peakRSS.map { $0 as Any } ?? NSNull()]
        }
    }
    private struct PressureState {
        let pressure: AnalysisCadence.Pressure, name: String, lowPower: Bool
        var report: [String: Any] { ["thermal": name,"low_power": lowPower] }
    }
    static func run(seconds: Double) throws -> Data {
        let before = pressureState(), rssBefore = residentBytes()
        let setupStart = ProcessInfo.processInfo.systemUptime
        let detector = try FastObjectDetector(preferCoreML: true)
        let setupMS = (ProcessInfo.processInfo.systemUptime-setupStart)*1000
        // Exactly portrait dimensions supplied by a rotated 1280x720 capture path.
        // Buffer creation/fill is outside all per-inference timing windows.
        let pixels = try portraitPixels()
        let rssAfterSetup = residentBytes()
        var peakRSS = max(rssBefore ?? 0, rssAfterSetup ?? 0)
        var cadence = AnalysisCadence()
        var windows: [[String: Any]] = []
        var current = Window(start: 0)
        current.recordRSS(rssAfterSetup)
        let started = ProcessInfo.processInfo.systemUptime
        var lastAdmitted = -Double.infinity, lastRSSSample = started, admitted = 0
        while true {
            try Task.checkCancellation()
            let now = ProcessInfo.processInfo.systemUptime, elapsed = now-started
            if elapsed >= seconds { break }
            if elapsed-current.start >= 30 {
                let rss = residentBytes(); current.recordRSS(rss)
                if let rss { peakRSS = max(peakRSS,rss) }
                windows.append(current.report(end: elapsed))
                current = Window(start: elapsed); current.recordRSS(rss)
            }
            let state = pressureState()
            let interval = cadence.interval(pressure: state.pressure, lowPower: state.lowPower)
            guard cadence.begin(at: now, pressure: state.pressure, lowPower: state.lowPower) else {
                // Sleep off Main; no busy spin and no artificial frame backlog.
                Thread.sleep(forTimeInterval: min(0.05, max(0.002, interval-(now-lastAdmitted))))
                continue
            }
            lastAdmitted = now
            try autoreleasepool {
                let frameStart = ProcessInfo.processInfo.systemUptime
                let (_, timing) = try detector.detect(pixels,rotation: .upright)
                let duration = ProcessInfo.processInfo.systemUptime-frameStart
                cadence.complete(duration: duration)
                current.record(timing: timing,totalMS: duration*1000,
                               interval: cadence.interval(pressure: state.pressure,lowPower: state.lowPower),state: state)
            }
            admitted += 1
            let after = ProcessInfo.processInfo.systemUptime
            if after-lastRSSSample >= 1 {
                let rss = residentBytes(); current.recordRSS(rss)
                if let rss { peakRSS = max(peakRSS,rss) }
                lastRSSSample = after
            }
        }
        let elapsed = ProcessInfo.processInfo.systemUptime-started, rssEnd = residentBytes()
        current.recordRSS(rssEnd); if let rssEnd { peakRSS = max(peakRSS,rssEnd) }
        if current.admitted > 0 { windows.append(current.report(end: elapsed)) }
        var machine = utsname(); uname(&machine)
        let model = withUnsafeBytes(of: &machine.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        return try JSONSerialization.data(withJSONObject: [
            "schema_version": 1, "kind": "synthetic portrait buffer at production cadence; not real camera FPS, effectiveness or energy",
            "date_utc": ISO8601DateFormatter().string(from: Date()), "device_model": model,
            "os": ProcessInfo.processInfo.operatingSystemVersionString, "runtime_pinned": FastObjectDetector.runtimeVersion,
            "model_sha256": FastModelContract.sha256, "requested_seconds": seconds, "actual_loop_seconds": elapsed,
            "buffer": "720x1280 BGRA deterministic RGB gradient; reused; no camera", "configured": detector.configuration,
            "setup_wall_ms": setupMS, "setup_internal_ms": detector.setupMS,
            "admitted_frames": admitted, "windows": windows, "environment_before": before.report, "environment_after": pressureState().report,
            "rss_before_setup_bytes": rssBefore.map { $0 as Any } ?? NSNull(), "rss_after_setup_bytes": rssAfterSetup.map { $0 as Any } ?? NSNull(),
            "rss_end_bytes": rssEnd.map { $0 as Any } ?? NSNull(), "sampled_peak_rss_bytes": peakRSS > 0 ? peakRSS as Any : NSNull(),
            "rss_note": "Mach task resident_size for the whole hosted app process, including test/WebKit/UI overhead. Sampled at setup, approximately once per second and window boundaries; may miss transient peaks. RSS is not physical footprint, allocation total or energy.",
            "measurement_note": "One warmed production detector; real thermal/LowPower inputs to production AnalysisCadence, no overrides. Off-main sleeps between admissions, per-frame autoreleasepool, timing arrays capped1024/window. Initial detector setup includes its own synthetic warm-up. p95 nearest-rank. No sustained camera/display/accuracy/battery claim."], options: [.sortedKeys])
    }
    private static func pressureState() -> PressureState {
        let info = ProcessInfo.processInfo
        switch info.thermalState {
        case .nominal: return PressureState(pressure:.normal,name:"nominal",lowPower:info.isLowPowerModeEnabled)
        case .fair: return PressureState(pressure:.warm,name:"fair",lowPower:info.isLowPowerModeEnabled)
        case .serious: return PressureState(pressure:.hot,name:"serious",lowPower:info.isLowPowerModeEnabled)
        case .critical: return PressureState(pressure:.critical,name:"critical",lowPower:info.isLowPowerModeEnabled)
        @unknown default: return PressureState(pressure:.hot,name:"unknown",lowPower:info.isLowPowerModeEnabled)
        }
    }
    private static func residentBytes() -> UInt64? {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
        let status = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self,capacity: Int(count)) {
                task_info(mach_task_self_,task_flavor_t(MACH_TASK_BASIC_INFO),$0,&count)
            }
        }
        return status == KERN_SUCCESS ? UInt64(info.resident_size) : nil
    }
    private static func stats(_ values: [Double]) -> [String: Any] {
        guard !values.isEmpty else { return ["median": NSNull(),"p95": NSNull()] }
        let sorted = values.sorted(), n = sorted.count
        let median = n.isMultiple(of: 2) ? (sorted[n/2-1]+sorted[n/2])/2 : sorted[n/2]
        return ["median":median,"p95":sorted[max(0,Int(ceil(Double(n)*0.95))-1)]]
    }
    private static func portraitPixels() throws -> CVPixelBuffer {
        var optional: CVPixelBuffer?
        guard CVPixelBufferCreate(kCFAllocatorDefault,720,1280,kCVPixelFormatType_32BGRA,nil,&optional) == kCVReturnSuccess,
              let pixels = optional, CVPixelBufferLockBaseAddress(pixels,[]) == kCVReturnSuccess else { throw ProbeError.pixels }
        defer { CVPixelBufferUnlockBaseAddress(pixels,[]) }
        guard let base = CVPixelBufferGetBaseAddress(pixels) else { throw ProbeError.pixels }
        let bytes = base.assumingMemoryBound(to: UInt8.self), stride = CVPixelBufferGetBytesPerRow(pixels)
        for y in 0..<1280 { for x in 0..<720 {
            let i = y*stride+x*4
            bytes[i] = UInt8((x+y)%256); bytes[i+1] = UInt8(y%256); bytes[i+2] = UInt8(x%256); bytes[i+3] = 255
        }}
        return pixels
    }
    private enum ProbeError: Error { case pixels }
}
