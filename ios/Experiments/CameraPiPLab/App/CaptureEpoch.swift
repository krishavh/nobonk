import CoreMedia

/// Immutable producer binding shared by one session, its delegate and observers.
struct CaptureEpoch: Sendable {
    let generation: UInt64
    let journal: ProbeJournal
    func append(_ sample: CMSampleBuffer, now: Double) {
        journal.append(sample, token: generation, now: now)
    }
    @discardableResult
    func fail(_ message: String) -> Bool {
        journal.invalidate(generation: generation, message: message)
    }
}
