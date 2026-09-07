import Foundation

/// Extracted from the SHA-pinned graph, not from the filename or a different checkpoint.
enum FastModelContract {
    static let sha256 = "9931a595afd6c780bdbe5ef12e99d34526b6f30dbc7641cf920572798e4ed96d"
    static let inputName = "images", outputName = "output0"
    static let edge = 416, channels = 84, anchors = 3549
    static let selectedClasses = [0, 1, 2, 3, 5, 7, 16, 15]
    static let names = [0: "person", 1: "bicycle", 2: "car", 3: "motorcycle", 5: "bus", 7: "truck", 15: "cat", 16: "dog"]
    static let confidence: Float = 0.4
    static let iou: Float = 0.45
}

enum DetectorMode: String, CaseIterable, Sendable {
    case visionPeople = "People"
    case fastObjects = "Fast Objects"
    var summary: String {
        switch self {
        case .visionPeople: "Apple Vision · people only"
        case .fastObjects: "People, bicycles, vehicles, cats & dogs"
        }
    }
}

struct FastLetterbox: Equatable, Sendable {
    let width: Int, height: Int, edge: Int
    let scale: Float, padX: Float, padY: Float
    init?(width: Int, height: Int, edge: Int = FastModelContract.edge) {
        guard width > 0, height > 0, edge > 0 else { return nil }
        self.width = width; self.height = height; self.edge = edge
        scale = Float(edge) / Float(max(width, height))
        padX = max(0, (Float(edge) - Float(width) * scale) / 2)
        padY = max(0, (Float(edge) - Float(height) * scale) / 2)
    }
    func normalized(left: Float, top: Float, right: Float, bottom: Float) -> PreviewRect? {
        guard [left, top, right, bottom].allSatisfy(\.isFinite) else { return nil }
        let x1 = min(1, max(0, (left - padX) / (Float(width) * scale)))
        let x2 = min(1, max(0, (right - padX) / (Float(width) * scale)))
        let y1 = min(1, max(0, (top - padY) / (Float(height) * scale)))
        let y2 = min(1, max(0, (bottom - padY) / (Float(height) * scale)))
        guard x2 > x1, y2 > y1 else { return nil }
        return PreviewRect(x: Double(x1), y: Double(y1), width: Double(x2 - x1), height: Double(y2 - y1))
    }
}

struct FastDetection: Equatable, Sendable {
    let anchor: Int, classID: Int
    let confidence: Float
    let rect: PreviewRect
    var label: String { FastModelContract.names[classID] ?? "unmapped" }
}

enum FastDecodeError: Error { case wrongShape, invalidTransform }

enum FastDecoder {
    /// This exact graph has a channel-major raw head [1,84,3549], not end-to-end NMS.
    /// Choose the best supported class at each anchor, then apply class-aware NMS.
    static func decode(_ values: UnsafeBufferPointer<Float>, shape: [Int], transform: FastLetterbox) throws -> [FastDetection] {
        guard shape == [1, FastModelContract.channels, FastModelContract.anchors],
              values.count == FastModelContract.channels * FastModelContract.anchors else { throw FastDecodeError.wrongShape }
        let count = FastModelContract.anchors
        var boxes: [FastDetection] = []
        for anchor in 0..<count {
            var score: Float = 0, classID = -1
            for candidate in FastModelContract.selectedClasses {
                let value = values[(4 + candidate) * count + anchor]
                if value.isFinite && value > score { score = value; classID = candidate }
            }
            guard score >= FastModelContract.confidence, score <= 1 else { continue }
            let x = values[anchor], y = values[count + anchor]
            let w = values[2 * count + anchor], h = values[3 * count + anchor]
            guard w.isFinite, h.isFinite, w > 0, h > 0,
                  let rect = transform.normalized(left: x - w / 2, top: y - h / 2, right: x + w / 2, bottom: y + h / 2) else { continue }
            boxes.append(FastDetection(anchor: anchor, classID: classID, confidence: score, rect: rect))
        }
        return suppress(boxes, threshold: FastModelContract.iou)
    }
    static func suppress(_ boxes: [FastDetection], threshold: Float) -> [FastDetection] {
        let threshold = threshold.isNaN ? 0 : min(1, max(0, threshold))
        // Android's groupBy preserves first-seen class order; stable score ties
        // preserve anchor order. Explicit indices make the Swift ordering equally deterministic.
        var order: [Int] = []
        var groups: [Int: [(Int, FastDetection)]] = [:]
        for (index, box) in boxes.enumerated() {
            if groups[box.classID] == nil { order.append(box.classID) }
            groups[box.classID, default: []].append((index, box))
        }
        return order.flatMap { id -> [FastDetection] in
            let sorted = groups[id]!.sorted {
                $0.1.confidence == $1.1.confidence ? $0.0 < $1.0 : $0.1.confidence > $1.1.confidence
            }.map(\.1)
            var retained: [FastDetection] = []
            for box in sorted where !retained.contains(where: { iou($0.rect, box.rect) > Double(threshold) }) {
                retained.append(box)
            }
            return retained
        }
    }
    static func iou(_ a: PreviewRect, _ b: PreviewRect) -> Double {
        let width = max(0, min(a.x + a.width, b.x + b.width) - max(a.x, b.x))
        let height = max(0, min(a.y + a.height, b.y + b.height) - max(a.y, b.y))
        let intersection = width * height
        let union = a.width * a.height + b.width * b.height - intersection
        return union > 0 ? intersection / union : 0
    }
}
