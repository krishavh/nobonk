import Foundation
import CoreVideo
import OnnxRuntimeBindings

// Runs the production Swift preprocessor, ORT runtime and decoder on synthetic
// pixels. No camera/device access; Mac timings are NOT iPhone benchmarks.
struct Reference: Decodable {
    struct Sample: Decodable { let index: Int; let value: Float }
    struct Pattern: Decodable { let name: String; let samples: [Sample] }
    let sha256: String; let runtime: String; let patterns: [Pattern]
}
guard CommandLine.arguments.count == 3 else {
    fatalError("Usage: NoBonkFastProbe /path/to/verified.onnx /path/to/fast-graph-reference.json")
}
let reference = try JSONDecoder().decode(Reference.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2])))
precondition(reference.sha256 == FastModelContract.sha256)
var report: [[String: Any]] = []
for preferCoreML in [false, true] {
    let detector = try FastObjectDetector(modelURL: URL(fileURLWithPath: CommandLine.arguments[1]), preferCoreML: preferCoreML)
    for pattern in reference.patterns {
        var optional: CVPixelBuffer?
        precondition(CVPixelBufferCreate(kCFAllocatorDefault,416,416,kCVPixelFormatType_32BGRA,nil,&optional) == kCVReturnSuccess)
        let pixels = optional!
        CVPixelBufferLockBaseAddress(pixels, [])
        let bytes = CVPixelBufferGetBaseAddress(pixels)!.assumingMemoryBound(to: UInt8.self)
        let stride = CVPixelBufferGetBytesPerRow(pixels)
        for y in 0..<416 { for x in 0..<416 {
            let i = y * stride + x * 4
            if pattern.name == "gray114" { bytes[i] = 114; bytes[i+1] = 114; bytes[i+2] = 114 }
            else { bytes[i] = UInt8((x+y)%256); bytes[i+1] = UInt8(y%256); bytes[i+2] = UInt8(x%256) }
            bytes[i+3] = 255
        }}
        CVPixelBufferUnlockBaseAddress(pixels, [])
        var boxDelta: Float = 0, scoreDelta: Float = 0
        let (detections,timing) = try detector.detect(pixels,rotation: .upright) { values in
            for sample in pattern.samples {
                let delta = abs(values[sample.index] - sample.value)
                if sample.index < 4 * FastModelContract.anchors { boxDelta = max(boxDelta,delta) }
                else { scoreDelta = max(scoreDelta,delta) }
            }
        }
        // Numerical smoke bounds, not a validation of obstacle-detection accuracy.
        precondition(boxDelta <= 1 && scoreDelta <= 0.005, "Synthetic graph parity outside exploratory bounds")
        report.append(["pattern":pattern.name,"configured":timing.configuration,"reference_runtime":reference.runtime,
                       "runtime":ORTVersion(),"sample_count":pattern.samples.count,"maximum_box_delta_pixels":boxDelta,
                       "maximum_score_delta":scoreDelta,"decoded_count":detections.count,
                       "setup_ms":timing.setupMS,"preprocess_ms":timing.preprocessMS,"inference_ms":timing.inferenceMS,"decode_ms":timing.decodeMS])
    }
}
print(String(data:try JSONSerialization.data(withJSONObject:report,options:[.prettyPrinted,.sortedKeys]),encoding:.utf8)!)
