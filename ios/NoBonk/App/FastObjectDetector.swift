import CoreVideo
import CryptoKit
import Foundation
import OnnxRuntimeBindings
import OSLog

struct DetectorTiming: Sendable {
    let setupMS: Double, preprocessMS: Double, inferenceMS: Double, decodeMS: Double
    let configuration: String
}

enum FastDetectorError: LocalizedError {
    case missingAsset, checksum, outputContract, pixels
    var errorDescription: String? {
        switch self {
        case .missingAsset: "Fast Objects is not installed in this build. Choose People."
        case .checksum: "Fast model verification failed. Choose People."
        case .outputContract: "Fast model returned an invalid result. Scanning stopped."
        case .pixels: "Fast camera format unavailable. Choose People."
        }
    }
}

/// Owned exclusively by CameraEngine's serial queue, including session creation,
/// reusable input memory, run and decoding. No network access or camera frames on disk.
final class FastObjectDetector {
    static let runtimeVersion = "1.24.2"
    static var assetURL: URL? { Bundle.main.url(forResource: "yolo26n_416", withExtension: "onnx", subdirectory: "Models") }
    private let environment: ORTEnv
    private var session: ORTSession?
    private let inputData: NSMutableData
    private let input: ORTValue
    private let signs = OSSignposter(subsystem: "ai.genwhy.nobonk", category: "FastDetector")
    private let logger = Logger(subsystem: "ai.genwhy.nobonk", category: "FastDetector")
    private(set) var configuration: String
    private(set) var setupMS = 0.0

    init(modelURL: URL? = FastObjectDetector.assetURL, preferCoreML: Bool = true) throws {
        let started = ProcessInfo.processInfo.systemUptime
        let signposter = OSSignposter(subsystem: "ai.genwhy.nobonk", category: "FastDetector")
        let interval = signposter.beginInterval("FastModelSetup")
        defer { signposter.endInterval("FastModelSetup", interval) }
        guard let url = modelURL else { throw FastDetectorError.missingAsset }
        let bytes = try Data(contentsOf: url, options: [.mappedIfSafe])
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        guard digest == FastModelContract.sha256 else { throw FastDetectorError.checksum }
        let env = try ORTEnv(loggingLevel: .warning)
        environment = env
        let count = 3 * FastModelContract.edge * FastModelContract.edge
        inputData = NSMutableData(length: count * MemoryLayout<Float>.stride)!
        input = try ORTValue(tensorData: inputData, elementType: .float, shape: [1, 3, 416, 416])
        let data = inputData.mutableBytes.bindMemory(to: Float.self, capacity: count)
        data.initialize(repeating: Float(114) / 255, count: count)

        func makeSession(coreML: Bool) throws -> ORTSession {
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(2)
            try options.setGraphOptimizationLevel(.all)
            try options.addConfigEntry(withKey: "session.intra_op.allow_spinning", value: "0")
            if coreML {
                let os = ProcessInfo.processInfo.operatingSystemVersion
                let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent("NoBonkFast/\(Self.runtimeVersion)/\(digest)/\(os.majorVersion).\(os.minorVersion).\(os.patchVersion)/MLProgram-ALL-static")
                try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
                try options.appendExecutionProvider("CoreML", providerOptions: [
                    "ModelFormat": "MLProgram", "MLComputeUnits": "ALL", "RequireStaticInputShapes": "1",
                    "EnableOnSubgraphs": "0", "ModelCacheDirectory": cache.path
                ])
            }
            return try ORTSession(env: env, modelPath: url.path, sessionOptions: options)
        }
        if preferCoreML && ORTIsCoreMLExecutionProviderAvailable() {
            do {
                session = try makeSession(coreML: true)
                configuration = "Core ML preferred · CPU fallback"
            } catch {
                session = try makeSession(coreML: false)
                configuration = "CPU · Core ML setup unavailable"
            }
        } else {
            session = try makeSession(coreML: false)
            configuration = preferCoreML ? "CPU · Core ML provider unavailable" : "CPU · explicit validation configuration"
        }
        guard try session?.inputNames() == [FastModelContract.inputName],
              try session?.outputNames() == [FastModelContract.outputName] else { throw FastDetectorError.outputContract }
        do { _ = try checkedOutput() }
        catch {
            // A provider can initialize successfully but fail on its first graph run.
            // Retry the SAME verified graph on CPU; never silently change detector scope.
            guard configuration.hasPrefix("Core ML") else { throw error }
            try NativeSessionOwnership.discardAndReplace(&session) { try makeSession(coreML: false) }
            configuration = "CPU · Core ML warm-up unavailable"
            _ = try checkedOutput()
        }
        setupMS = (ProcessInfo.processInfo.systemUptime - started) * 1000
        logger.notice("Fast setup ms=\(self.setupMS), configured=\(self.configuration, privacy: .public). Provider choice is not GPU/ANE execution proof.")
    }

    func detect(_ pixels: CVPixelBuffer, rotation: FastPixelRotation, inspectRawOutput: ((UnsafeBufferPointer<Float>) -> Void)? = nil) throws -> ([FastDetection], DetectorTiming) {
        guard CVPixelBufferGetPixelFormatType(pixels) == kCVPixelFormatType_32BGRA,
              CVPixelBufferLockBaseAddress(pixels, .readOnly) == kCVReturnSuccess else { throw FastDetectorError.pixels }
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixels) else { throw FastDetectorError.pixels }
        let started = ProcessInfo.processInfo.systemUptime
        let preprocess = signs.beginInterval("FastPreprocess")
        let transform: FastLetterbox
        do {
            transform = try FastPreprocessor.fill(
                bgra: UnsafeBufferPointer(start: base.assumingMemoryBound(to: UInt8.self), count: CVPixelBufferGetDataSize(pixels)),
                width: CVPixelBufferGetWidth(pixels), height: CVPixelBufferGetHeight(pixels), rowBytes: CVPixelBufferGetBytesPerRow(pixels),
                rotation: rotation,
                into: UnsafeMutableBufferPointer(start: inputData.mutableBytes.assumingMemoryBound(to: Float.self), count: inputData.length / 4))
        } catch { signs.endInterval("FastPreprocess", preprocess); throw error }
        signs.endInterval("FastPreprocess", preprocess)
        let prepared = ProcessInfo.processInfo.systemUptime
        let inference = signs.beginInterval("FastInference")
        let output: ORTValue
        do { output = try checkedOutput() }
        catch { signs.endInterval("FastInference", inference); throw error }
        signs.endInterval("FastInference", inference)
        let inferred = ProcessInfo.processInfo.systemUptime
        let decode = signs.beginInterval("FastDecode")
        defer { signs.endInterval("FastDecode", decode) }
        // tensorData is a non-owning view of ORT-allocated memory. Keeping its
        // NSMutableData wrapper alive does not keep the owning ORTValue alive.
        let detections = try withExtendedLifetime(output) {
            let data = try output.tensorData()
            let values = UnsafeBufferPointer(start: data.bytes.assumingMemoryBound(to: Float.self), count: data.length / 4)
            inspectRawOutput?(values)
            return try FastDecoder.decode(values, shape: [1, 84, 3549], transform: transform)
        }
        let decoded = ProcessInfo.processInfo.systemUptime
        return (detections, DetectorTiming(setupMS: setupMS, preprocessMS: (prepared - started) * 1000,
                                           inferenceMS: (inferred - prepared) * 1000, decodeMS: (decoded - inferred) * 1000,
                                           configuration: configuration))
    }
    private func checkedOutput() throws -> ORTValue {
        guard let session else { throw FastDetectorError.outputContract }
        let outputs = try session.run(withInputs: [FastModelContract.inputName: input],
                                      outputNames: [FastModelContract.outputName], runOptions: nil)
        guard let output = outputs[FastModelContract.outputName] else { throw FastDetectorError.outputContract }
        let info = try output.tensorTypeAndShapeInfo()
        guard info.elementType == .float, info.shape.map(\.intValue) == [1, 84, 3549] else { throw FastDetectorError.outputContract }
        let data = try output.tensorData()
        guard data.length == FastModelContract.channels * FastModelContract.anchors * 4 else { throw FastDetectorError.outputContract }
        let values = UnsafeBufferPointer(start: data.bytes.assumingMemoryBound(to: Float.self), count: data.length / 4)
        guard values.allSatisfy(\.isFinite) else { throw FastDetectorError.outputContract }
        return output
    }
}
