import Foundation

struct MessageCameraToken: Equatable, Sendable {
    let activation: UInt64
    let run: UInt64
}

struct MessageCameraTrial: Sendable {
    let token: MessageCameraToken
    let began: TimeInterval
    var ended: TimeInterval?
    var endReason: String?
    var frames: Int = 0
    var firstFrame: TimeInterval?
    var lastFrame: TimeInterval?
    var lastPTS: TimeInterval?
    var inferences: Int = 0
    var lastInference: TimeInterval?
    var people: Int?
}

struct MessageCameraSnapshot: Sendable {
    let activation: UInt64
    let active: Bool
    let acknowledged: Bool
    let phase: String
    let current: MessageCameraTrial?
    let completed: [MessageCameraTrial]

    func frameIsFresh(at now: TimeInterval) -> Bool {
        guard active, let time = current?.lastFrame else { return false }
        return now >= time && now - time <= 1.5
    }
    func inferenceIsFresh(at now: TimeInterval) -> Bool {
        guard frameIsFresh(at: now), let time = current?.lastInference else { return false }
        return now >= time && now - time <= 1.5
    }
}

/// Synchronous gate and metadata-only journal. The lifecycle caller freezes it
/// before any UI hop or camera teardown. Immutable tokens bind async work to its
/// producer. No images, message contents, contacts or wall-clock dates are stored.
final class MessageCameraJournal: @unchecked Sendable {
    private struct Frame { let received: TimeInterval; let pts: TimeInterval }
    private struct Inference { let completed: TimeInterval; let people: Int }
    private let lock = NSLock()
    private var activation: UInt64 = 0
    private var nextRun: UInt64 = 0
    private var active = false
    private var hostActive = true
    private var acknowledged = false
    private var phase = "Open this extension in Messages"
    private var current: MessageCameraTrial?
    private var frames: [Frame] = []
    private var inferences: [Inference] = []
    private var completed: [MessageCameraTrial] = []
    private let maximumFrames = 10_800

    @discardableResult func activate(at now: TimeInterval) -> UInt64 {
        locked {
            guard hostActive, !active else { return activation }
            activation &+= 1
            active = true; acknowledged = false
            phase = "Read and acknowledge before starting"
            return activation
        }
    }
    func acknowledge(activation expected: UInt64) {
        locked {
            guard active, activation == expected else { return }
            acknowledged = true; phase = "Ready — camera is off"
        }
    }
    func begin(at now: TimeInterval) -> MessageCameraToken? {
        locked {
            guard active, acknowledged, current == nil else { return nil }
            nextRun &+= 1
            let token = MessageCameraToken(activation: activation, run: nextRun)
            current = MessageCameraTrial(token: token, began: now)
            frames.removeAll(keepingCapacity: true); inferences.removeAll(keepingCapacity: true)
            phase = "Checking camera permission"
            return token
        }
    }
    func accepts(_ token: MessageCameraToken) -> Bool {
        locked { active && current?.token == token }
    }
    @discardableResult func markStarting(_ token: MessageCameraToken) -> Bool {
        locked {
            guard active, current?.token == token else { return false }
            phase = "Starting — waiting for a real frame"
            return true
        }
    }
    @discardableResult func receiveFrame(_ token: MessageCameraToken, at receipt: TimeInterval, pts: TimeInterval) -> Bool {
        locked {
            guard active, let trial = current, trial.token == token,
                  receipt.isFinite, pts.isFinite, receipt >= trial.began,
                  receipt >= (frames.last?.received ?? trial.began),
                  pts > (frames.last?.pts ?? -.infinity) else { return false }
            guard frames.count < maximumFrames else {
                let reason = "Trial reached the metadata limit — start another trial"
                freeze(at: receipt, reason: reason)
                phase = reason
                return false
            }
            frames.append(Frame(received: receipt, pts: pts))
            current?.frames = frames.count
            if current?.firstFrame == nil { current?.firstFrame = receipt }
            current?.lastFrame = receipt; current?.lastPTS = pts
            phase = "Receiving camera frames"
            return true
        }
    }
    @discardableResult func finishInference(_ token: MessageCameraToken, at now: TimeInterval, people: Int) -> Bool {
        locked {
            guard active, let trial = current, trial.token == token,
                  now.isFinite, now >= (trial.lastFrame ?? .infinity), people >= 0 else { return false }
            inferences.append(Inference(completed: now, people: people))
            current?.inferences = inferences.count
            current?.lastInference = now; current?.people = people
            return true
        }
    }
    /// An optional expected token prevents an old producer's failure from ending
    /// a new session. Omitting it is only for synchronous user/lifecycle actions.
    @discardableResult func stop(at now: TimeInterval, reason: String, token: MessageCameraToken? = nil) -> MessageCameraToken? {
        locked {
            if let token, current?.token != token { return nil }
            let stopped = current?.token
            freeze(at: now, reason: reason)
            phase = reason
            return stopped
        }
    }
    @discardableResult func deactivate(at now: TimeInterval, reason: String) -> MessageCameraToken? {
        locked {
            let stopped = current?.token
            freeze(at: now, reason: reason)
            active = false; acknowledged = false; phase = reason
            return stopped
        }
    }
    @discardableResult func suspendHost(at now: TimeInterval, reason: String) -> MessageCameraToken? {
        locked {
            let stopped = current?.token
            freeze(at: now, reason: reason)
            hostActive = false; active = false; acknowledged = false; phase = reason
            return stopped
        }
    }
    func resumeHost() { locked { hostActive = true } }
    func reset(at now: TimeInterval) {
        locked {
            freeze(at: now, reason: "Diagnostics reset")
            completed.removeAll()
            // Start tokens never repeat after clearing the displayed history.
            acknowledged = false; phase = "Read and acknowledge before starting"
        }
    }
    func snapshot() -> MessageCameraSnapshot {
        locked { MessageCameraSnapshot(activation: activation, active: active,
                                      acknowledged: acknowledged, phase: phase,
                                      current: current, completed: completed) }
    }
    private func freeze(at now: TimeInterval, reason: String) {
        guard var trial = current else { return }
        let end = max(trial.began, now)
        // A callback may acquire this lock after the lifecycle method captured
        // its timestamp, but before that method acquires the lock. Rebuild at the
        // boundary so such receipts cannot appear as pre-dismissal evidence.
        let acceptedFrames = frames.filter { $0.received < end }
        let acceptedInferences = inferences.filter { $0.completed < end }
        trial.ended = end; trial.endReason = reason
        trial.frames = acceptedFrames.count; trial.firstFrame = acceptedFrames.first?.received
        trial.lastFrame = acceptedFrames.last?.received; trial.lastPTS = acceptedFrames.last?.pts
        trial.inferences = acceptedInferences.count
        trial.lastInference = acceptedInferences.last?.completed; trial.people = acceptedInferences.last?.people
        completed.append(trial)
        if completed.count > 6 { completed.removeFirst(completed.count - 6) }
        current = nil
        frames.removeAll(keepingCapacity: true); inferences.removeAll(keepingCapacity: true)
    }
    private func locked<T>(_ work: () -> T) -> T {
        lock.lock(); defer { lock.unlock() }; return work()
    }
}
