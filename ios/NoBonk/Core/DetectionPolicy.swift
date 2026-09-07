import Foundation

struct PersonBox: Equatable, Sendable, Identifiable {
    let id: Int
    // Normalized coordinates, origin at top left of the portrait camera image.
    let x: Double
    let y: Double
    let width: Double
    let height: Double
    let confidence: Double
    var usable: Bool {
        [x,y,width,height,confidence].allSatisfy(\.isFinite) && x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1.001 && y + height <= 1.001 && confidence >= 0.5
    }
}

struct DetectionPolicy {
    enum Cue: Equatable { case none, personAhead }
    private var lastAlert = -Double.infinity
    private var consecutiveFrames = 0
    // Apparent image size only. This prototype does NOT claim calibrated metres or depth.
    mutating func evaluate(_ boxes: [PersonBox], time: Double) -> Cue {
        guard time.isFinite else { return .none }
        let candidate = boxes.contains { b in
            b.usable && b.height >= 0.42 && b.x < 0.65 && b.x + b.width > 0.35
        }
        consecutiveFrames = candidate ? consecutiveFrames + 1 : 0
        guard consecutiveFrames >= 3, time - lastAlert >= 3 else { return .none }
        lastAlert = time
        return .personAhead
    }
    mutating func reset() { self = Self() }
}
