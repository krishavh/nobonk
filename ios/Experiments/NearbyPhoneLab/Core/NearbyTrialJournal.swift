import Foundation

/// Receipt instrumentation, not a sensor timestamp or a guarantee of UI delivery.
/// Every mutation, including lifecycle boundaries, is synchronous under this lock.
public final class NearbyTrialJournal: @unchecked Sendable {
    public enum Kind: String, CaseIterable, Sendable {
        case appSwitch = "App switch"
        case lock = "Device lock"
    }
    public enum End: String, Sendable {
        case foreground = "Returned to foreground"
        case stopped = "Stopped"
        case failed = "Session failed"
        case invalidated = "NI invalidated"
        case peerRemoved = "NI peer removed"
        case capacity = "Journal capacity reached"
    }
    public struct Report: Identifiable, Sendable {
        public let id: Int
        public let kind: Kind
        public let startedAt: TimeInterval
        public fileprivate(set) var endedAt: TimeInterval?
        public fileprivate(set) var endReason: End?
        public fileprivate(set) var distanceCallbacks = 0
        public fileprivate(set) var callbacksAfterOneSecond = 0
        public fileprivate(set) var unavailableCallbacks = 0
        public fileprivate(set) var suspensions = 0
        public fileprivate(set) var resumptions = 0
        public fileprivate(set) var lastDistanceAt: TimeInterval?
        public fileprivate(set) var largestSilence: TimeInterval = 0

        public var duration: TimeInterval? { endedAt.map { $0 - startedAt } }
        public var lastDistanceAgeAtEnd: TimeInterval? {
            guard let endedAt, let lastDistanceAt else { return nil }
            return endedAt - lastDistanceAt
        }
    }
    public struct Snapshot: Sendable {
        public let armed: Kind?
        public let current: Report?
        public let reports: [Report]
    }

    private let lock = NSLock()
    private var producer: ObjectIdentifier?
    private var receiving = false
    private var inBackground = false
    private var armed: Kind?
    private var current: Report?
    private var reports: [Report] = []
    private var nextTrial = 0
    private var lastReceipt: TimeInterval?
    private var lastBoundary: TimeInterval = -.infinity
    private enum Event { case distance, unavailable, suspended, resumed }
    private struct Receipt { let event: Event; let time: TimeInterval }
    private var receipts: [Receipt] = []
    private var terminalReceipts: (report: Report, receipts: [Receipt])?

    public init() {}

    /// A new NI object is a new lease. Preserve app lifecycle, reset all evidence.
    public func begin(producer: ObjectIdentifier, receiving: Bool = true) {
        lock.lock(); defer { lock.unlock() }
        self.producer = producer
        self.receiving = receiving
        armed = nil
        current = nil
        reports = []
        nextTrial = 0
        lastReceipt = nil
        receipts = []
        terminalReceipts = nil
    }

    @discardableResult public func arm(_ kind: Kind) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard producer != nil, receiving, !inBackground else { return false }
        armed = kind
        return true
    }

    public func snapshot() -> Snapshot {
        lock.lock(); defer { lock.unlock() }
        return Snapshot(armed: armed, current: current, reports: reports)
    }

    public func background(at time: TimeInterval) {
        lock.lock(); defer { lock.unlock() }
        guard time.isFinite, time >= lastBoundary, !inBackground else { return }
        lastBoundary = time
        inBackground = true
        guard producer != nil, let armed else { return }
        nextTrial += 1
        current = Report(id: nextTrial, kind: armed, startedAt: time)
        receipts = []
        self.armed = nil
    }

    public func foreground(at time: TimeInterval) {
        lock.lock(); defer { lock.unlock() }
        guard time.isFinite, time >= lastBoundary else { return }
        lastBoundary = time
        if inBackground { freeze(at: time, reason: .foreground) }
        // A newer NI termination can also win the lock while the foreground
        // notification is descheduled. Reconcile to the earlier boundary before
        // foreground UI can read the report, then discard all receipt metadata.
        if let terminal = terminalReceipts, let endedAt = terminal.report.endedAt,
           time < endedAt, time >= terminal.report.startedAt,
           reports.last?.id == terminal.report.id {
            reports[reports.count - 1] = finalized(terminal.report, receipts: terminal.receipts, at: time, reason: .foreground)
        }
        terminalReceipts = nil
        inBackground = false
    }

    /// Called before the MainActor hop. NI exposes optional distance but no
    /// sensor acquisition time here, so these are valid-distance callback counts.
    public func receive(hasDistance: Bool, producer: ObjectIdentifier, at time: TimeInterval) {
        lock.lock(); defer { lock.unlock() }
        guard self.producer == producer, receiving, time.isFinite,
              lastReceipt.map({ time >= $0 }) ?? true else { return }
        lastReceipt = time
        append(hasDistance ? .distance : .unavailable, at: time)
    }

    public func suspend(producer: ObjectIdentifier, at time: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        lock.lock(); defer { lock.unlock() }
        guard self.producer == producer, receiving else { return }
        receiving = false
        append(.suspended, at: time)
    }

    public func resume(producer: ObjectIdentifier, at time: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        lock.lock(); defer { lock.unlock() }
        guard self.producer == producer, !receiving else { return }
        receiving = true
        append(.resumed, at: time)
    }

    /// A producer is supplied for framework callbacks so old callbacks cannot
    /// stop a later session. UI Stop has no producer and invalidates the lease.
    public func finish(producer: ObjectIdentifier? = nil, at time: TimeInterval, reason: End) {
        lock.lock(); defer { lock.unlock() }
        if let producer, self.producer != producer { return }
        freeze(at: time, reason: reason)
        self.producer = nil
        receiving = false
        armed = nil
    }

    private func freeze(at time: TimeInterval, reason: End) {
        guard let pending = current, time.isFinite else { return }
        let trial = finalized(pending, receipts: receipts, at: time, reason: reason)
        if inBackground, reason != .foreground { terminalReceipts = (trial, receipts) }
        reports.append(trial)
        reports = Array(reports.suffix(6))
        current = nil
        receipts = []
    }

    private func finalized(_ pending: Report, receipts: [Receipt], at time: TimeInterval, reason: End) -> Report {
        let end = max(time, pending.startedAt)
        var trial = Report(id: pending.id, kind: pending.kind, startedAt: pending.startedAt)
        // The boundary timestamp is captured before acquiring this lock. If a
        // newer callback won the lock first, it must still be excluded. Rebuild
        // from receipt times instead of freezing counters inflated by that race.
        for receipt in receipts.filter({ $0.time < end }).sorted(by: { $0.time < $1.time }) {
            apply(receipt, to: &trial)
        }
        trial.endedAt = end
        trial.endReason = reason
        trial.largestSilence = max(trial.largestSilence, end - (trial.lastDistanceAt ?? trial.startedAt))
        return trial
    }

    private func append(_ event: Event, at time: TimeInterval) {
        guard inBackground, var trial = current, time.isFinite, time >= trial.startedAt else { return }
        // Bound memory for accidentally long trials. Capacity closes the report
        // explicitly; it must never silently truncate and claim full coverage.
        guard receipts.count < 100_000 else { freeze(at: time, reason: .capacity); return }
        let receipt = Receipt(event: event, time: time)
        receipts.append(receipt)
        apply(receipt, to: &trial)
        current = trial
    }

    private func apply(_ receipt: Receipt, to trial: inout Report) {
        switch receipt.event {
        case .distance:
            trial.distanceCallbacks += 1
            if receipt.time - trial.startedAt >= 1 { trial.callbacksAfterOneSecond += 1 }
            trial.largestSilence = max(trial.largestSilence, receipt.time - (trial.lastDistanceAt ?? trial.startedAt))
            trial.lastDistanceAt = receipt.time
        case .unavailable: trial.unavailableCallbacks += 1
        case .suspended: trial.suspensions += 1
        case .resumed: trial.resumptions += 1
        }
    }
}
