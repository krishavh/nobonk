import CoreMedia
import Foundation

struct ProbeSnapshot: Sendable {
    var generation: UInt64 = 0
    var active = false
    var frameCount = 0
    var lastPTS = 0.0
    var lastArrival = 0.0
    var longestGap = 0.0
    var backgroundStart = 0.0
    var backgroundFrames = 0
    var backgroundFramesAfterOneSecond = 0
    var lastBackgroundDuration = 0.0
    var frameAgeAtReturn = -1.0
    var inBackground = false
    var supported = false
    var enabled = false
    var status = "Not started"
    var interruption = "None observed"
    var interruptionReason: Int?
}

/// All cross-queue state and the one-frame mailbox are protected by this lock.
/// Invalidating a generation synchronously prevents Stop from leaking old frames.
final class ProbeJournal: @unchecked Sendable {
    private let lock = NSLock()
    private var value = ProbeSnapshot()
    private var latest: CMSampleBuffer?

    func begin() -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        let next = value.generation &+ 1
        value = ProbeSnapshot(generation: next, active: true, status: "Starting…")
        latest = nil
        return next
    }
    func accepts(_ token: UInt64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return value.active && value.generation == token
    }
    @discardableResult
    func invalidate(generation: UInt64? = nil, message: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if let generation, (!value.active || value.generation != generation) { return false }
        value.active = false
        value.generation &+= 1
        value.status = message
        latest = nil
        return true
    }
    func snapshot() -> ProbeSnapshot {
        lock.lock(); defer { lock.unlock() }
        return value
    }
    func update(_ token: UInt64, _ change: (inout ProbeSnapshot) -> Void) {
        lock.lock(); defer { lock.unlock() }
        guard value.active, value.generation == token else { return }
        change(&value)
    }
    func background(_ entering: Bool, now: Double) {
        lock.lock(); defer { lock.unlock() }
        if entering {
            value.backgroundStart = now
            value.backgroundFrames = 0
            value.backgroundFramesAfterOneSecond = 0
            value.lastBackgroundDuration = 0
            value.frameAgeAtReturn = -1
        } else if value.inBackground {
            value.lastBackgroundDuration = now - value.backgroundStart
            value.frameAgeAtReturn = value.lastArrival > 0 ? now - value.lastArrival : -1
        }
        value.inBackground = entering
    }
    func append(_ sample: CMSampleBuffer, token: UInt64, now: Double) {
        lock.lock(); defer { lock.unlock() }
        guard value.active, value.generation == token else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample).seconds
        guard pts.isFinite, value.frameCount == 0 || pts > value.lastPTS else { return }
        if value.lastArrival > 0 { value.longestGap = max(value.longestGap, now - value.lastArrival) }
        value.frameCount += 1
        value.lastPTS = pts
        value.lastArrival = now
        if value.inBackground {
            value.backgroundFrames += 1
            if now - value.backgroundStart >= 1 { value.backgroundFramesAfterOneSecond += 1 }
        }
        latest = sample
    }
    func takeLatest() -> (UInt64, CMSampleBuffer)? {
        lock.lock(); defer { lock.unlock() }
        guard value.active, let sample = latest else { latest = nil; return nil }
        latest = nil
        return (value.generation, sample)
    }
}
