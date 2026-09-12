import Foundation

struct PersonBox: Equatable, Sendable, Identifiable {
    let id: Int
    // Normalized coordinates, origin at top left of the portrait camera image.
    let x: Double
    let y: Double
    let width: Double
    let height: Double
    let confidence: Double
    var classID: Int = 0
    var detectorMode: DetectorMode = .visionPeople
    var label: String { FastModelContract.names[classID] ?? "object" }
    var usable: Bool {
        [x,y,width,height,confidence].allSatisfy(\.isFinite) && x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1.001 && y + height <= 1.001 && confidence <= 1 &&
        confidence >= (detectorMode == .visionPeople ? 0.5 : Double(FastModelContract.confidence)) &&
        (detectorMode == .visionPeople ? classID == 0 : FastModelContract.names[classID] != nil)
    }
}

enum AlertSensitivity: String, CaseIterable, Sendable {
    case earlier = "Earlier", balanced = "Balanced", closer = "Closer"
    var minimumHeight: Double {
        switch self { case .earlier: 0.32; case .balanced: 0.42; case .closer: 0.55 }
    }
}

struct DetectionPolicy {
    enum Cue: Equatable { case none, personAhead, objectAhead }
    private var lastAlert = -Double.infinity
    private var consecutiveFrames = 0
    private var candidateClass: Int?
    // Apparent image size only. This prototype does NOT claim calibrated metres or depth.
    mutating func evaluate(_ boxes: [PersonBox], time: Double, sensitivity: AlertSensitivity = .balanced) -> Cue {
        guard time.isFinite else { return .none }
        let candidate = boxes.first { b in
            b.usable && b.height >= sensitivity.minimumHeight && b.x < 0.65 && b.x + b.width > 0.35
        }
        if let candidate {
            consecutiveFrames = candidateClass == candidate.classID ? consecutiveFrames + 1 : 1
            candidateClass = candidate.classID
        } else { consecutiveFrames = 0; candidateClass = nil }
        guard consecutiveFrames >= 3, time - lastAlert >= 3 else { return .none }
        lastAlert = time
        return candidate?.classID == 0 ? .personAhead : .objectAhead
    }
    mutating func reset() { self = Self() }
}


/// Invalidates camera work immediately, even while the serial capture queue is busy.
/// Both the capture queue and Main validate the same token before publishing a result.
final class CaptureGeneration: @unchecked Sendable {
    private let lock = NSLock()
    private var generation: UInt64 = 0
    private var wanted = false
    func begin() -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        generation &+= 1; wanted = true
        return generation
    }
    func stop() {
        lock.lock(); defer { lock.unlock() }
        generation &+= 1; wanted = false
    }
    func accepts(_ token: UInt64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return wanted && generation == token
    }
}


/// Mono PCM WAV generated locally; no file, download, or system-sound dependency.
enum AlertTone {
    static func wav() -> Data {
        let rate = 22050, count = 4410
        var data = Data()
        func text(_ s: String) { data.append(contentsOf: s.utf8) }
        func u16(_ v: UInt16) { var x = v.littleEndian; withUnsafeBytes(of: &x) { data.append(contentsOf: $0) } }
        func u32(_ v: UInt32) { var x = v.littleEndian; withUnsafeBytes(of: &x) { data.append(contentsOf: $0) } }
        text("RIFF"); u32(UInt32(36 + count * 2)); text("WAVEfmt "); u32(16)
        u16(1); u16(1); u32(UInt32(rate)); u32(UInt32(rate * 2)); u16(2); u16(16)
        text("data"); u32(UInt32(count * 2))
        for i in 0..<count {
            let envelope = min(1, Double(i) / 220) * min(1, Double(count - 1 - i) / 440)
            let sample = Int16(sin(2 * .pi * 880 * Double(i) / Double(rate)) * 8000 * envelope)
            u16(UInt16(bitPattern: sample))
        }
        return data
    }
}


/// Matches AVCaptureVideoPreviewLayer.resizeAspect without stretching detections
/// across letterboxing when the user switches between compact and expanded view.
struct PreviewRect: Equatable, Sendable {
    let x: Double, y: Double, width: Double, height: Double
    static let zero = PreviewRect(x: 0, y: 0, width: 0, height: 0)
    var minX: Double { x }
    var minY: Double { y }
    var midX: Double { x + width / 2 }
    var midY: Double { y + height / 2 }
}

enum PreviewGeometry {
    static func fittedRect(width: Double, height: Double, imageAspect: Double) -> PreviewRect {
        guard width.isFinite, height.isFinite, imageAspect.isFinite,
              width > 0, height > 0, imageAspect > 0 else { return .zero }
        let fittedWidth = min(width, height * imageAspect)
        let fittedHeight = fittedWidth / imageAspect
        return PreviewRect(x: (width - fittedWidth) / 2, y: (height - fittedHeight) / 2,
                      width: fittedWidth, height: fittedHeight)
    }
    static func rect(for box: PersonBox, width: Double, height: Double, imageAspect: Double) -> PreviewRect {
        guard box.usable else { return .zero }
        let image = fittedRect(width: width, height: height, imageAspect: imageAspect)
        return PreviewRect(x: image.minX + box.x * image.width, y: image.minY + box.y * image.height,
                      width: box.width * image.width, height: box.height * image.height)
    }
}

/// Pace inference from measured work, not phone model names. The serial camera
/// queue and discard-late-frames setting separately prevent any inference backlog.
struct AnalysisCadence {
    enum Pressure { case normal, warm, hot, critical }
    private(set) var averageDuration = 0.0
    private var lastStart = -Double.infinity
    private var completedCount = 0
    // First request can include cold Vision initialization. Do not let that one
    // sample hold a fast phone at a slow pace for the following several seconds.
    var interval: Double { max(1.0 / 12, completedCount < 2 ? 0 : averageDuration * 1.5) }
    func interval(pressure: Pressure, lowPower: Bool) -> Double {
        let thermalFloor: Double
        switch pressure { case .normal: thermalFloor = 0; case .warm: thermalFloor = 0.15
        case .hot: thermalFloor = 0.35; case .critical: thermalFloor = 0.75 }
        return max(interval, thermalFloor, lowPower ? 0.2 : 0)
    }
    mutating func begin(at time: Double, pressure: Pressure = .normal, lowPower: Bool = false) -> Bool {
        guard time.isFinite, time - lastStart >= interval(pressure: pressure, lowPower: lowPower) else { return false }
        lastStart = time
        return true
    }
    mutating func complete(duration: Double) {
        guard duration.isFinite, duration > 0 else { return }
        completedCount += 1
        averageDuration = completedCount <= 2 ? duration : averageDuration * 0.75 + duration * 0.25
    }
    mutating func reset() { self = Self() }
}
