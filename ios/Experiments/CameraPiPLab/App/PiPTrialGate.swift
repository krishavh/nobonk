import Foundation

/// A distinct controller identity belongs to exactly one camera generation.
/// Callbacks are checked both on receipt and after their hop to the UI actor.
final class PiPTrialGate: @unchecked Sendable {
    struct Binding: Equatable, Sendable { let generation: UInt64; let attempt: UUID }
    private enum Phase { case idle, starting, active, stopping, finished }
    private let lock = NSLock()
    private var identity: ObjectIdentifier?
    private var generation: UInt64 = 0
    private var attempt = UUID()
    private var phase: Phase = .finished
    var teardownPending: Bool {
        lock.lock(); defer { lock.unlock() }
        return phase == .stopping
    }
    @discardableResult
    func install(_ id: ObjectIdentifier, generation: UInt64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard phase == .idle || phase == .finished else { return false }
        identity = id; self.generation = generation; attempt = UUID(); phase = .idle
        return true
    }
    func binding(for id: ObjectIdentifier) -> Binding? {
        lock.lock(); defer { lock.unlock() }
        return identity == id && phase != .finished ? Binding(generation: generation, attempt: attempt) : nil
    }
    func accepts(_ binding: Binding) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return attempt == binding.attempt && phase != .finished
    }
    func requestStart(_ binding: Binding) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard attempt == binding.attempt, phase == .idle else { return false }
        phase = .starting; return true
    }
    func didStart(_ binding: Binding) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard attempt == binding.attempt, phase == .starting else { return false }
        phase = .active; return true
    }
    /// True means Start must remain unavailable until a matching terminal callback.
    func requestStop() -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard phase == .starting || phase == .active || phase == .stopping else { return false }
        phase = .stopping; return true
    }
    func finish(_ binding: Binding) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard attempt == binding.attempt, phase != .finished else { return false }
        phase = .finished; return true
    }
}
