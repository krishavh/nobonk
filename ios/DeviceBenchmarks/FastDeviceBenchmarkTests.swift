import CoreVideo
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
