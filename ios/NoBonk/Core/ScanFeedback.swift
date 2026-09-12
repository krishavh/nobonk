import Foundation

/// Freshness uses monotonic camera-callback receipt time, not UI delivery time
/// or a wall clock. It does not measure the camera sensor's capture timestamp.
struct ScanFeedback: Equatable {
    enum State { case paused, preparing, waiting, recent, delayed }
    let state: State
    let detail: String
    var recent: Bool { state == .recent }
    var badge: String {
        switch state {
        case .paused: "CAMERA PAUSED"
        case .preparing: "PREPARING"
        case .waiting: "WAITING FOR ANALYSIS"
        case .recent: "RECENT SCAN"
        case .delayed: "ANALYSIS DELAYED"
        }
    }
    static func isFresh(receivedAt: Double?, now: Double) -> Bool {
        guard let receivedAt, receivedAt.isFinite, now.isFinite else { return false }
        return now >= receivedAt && now - receivedAt < 5
    }
    static func make(running: Bool, starting: Bool, receivedAt: Double?, now: Double,
                     boxes: [PersonBox], pausedMessage: String) -> Self {
        if starting { return Self(state: .preparing, detail: "Preparing detection · you can stop at any time") }
        guard running else { return Self(state: .paused, detail: pausedMessage) }
        guard receivedAt != nil else { return Self(state: .waiting, detail: "Camera open · waiting for the first analysis") }
        guard isFresh(receivedAt: receivedAt, now: now) else {
            return Self(state: .delayed, detail: "Analysis is delayed · keep looking up")
        }
        let usable = boxes.filter(\.usable)
        let detail: String
        if usable.isEmpty { detail = "No selected objects recognized · keep looking up" }
        else if usable.count == 1 { detail = "\(usable[0].label.capitalized) detected" }
        else { detail = "\(usable.count) detections · " + Array(Set(usable.map(\.label))).sorted().prefix(3).joined(separator: ", ") }
        return Self(state: .recent, detail: detail)
    }
}
