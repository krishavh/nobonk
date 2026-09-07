import Foundation

/// Pure session state. Times are monotonic seconds, not wall-clock dates.
public struct LabSessionState {
    public enum Phase: String { case idle, pairing, ranging, suspended, failed, stopped }
    public private(set) var generation: UInt64 = 0
    public private(set) var phase: Phase = .idle
    public private(set) var distance: Float?
    public private(set) var lastMeasurementTime: TimeInterval?
    public private(set) var sampleCount = 0
    public private(set) var maximumGap: TimeInterval = 0
    public let freshnessWindow: TimeInterval = 2
    private var lastAcceptedSampleTime: TimeInterval?

    public init() {}

    @discardableResult public mutating func begin() -> UInt64 {
        generation &+= 1
        phase = .pairing
        resetMeasurements()
        return generation
    }

    public mutating func startRanging(generation lease: UInt64) {
        guard lease == generation, phase == .pairing || phase == .suspended else { return }
        phase = .ranging
        distance = nil
        lastMeasurementTime = nil
    }

    @discardableResult public mutating func receive(distance value: Float?, at time: TimeInterval, generation lease: UInt64) -> Bool {
        guard lease == generation, phase == .ranging, time.isFinite else { return false }
        guard let value, value.isFinite, value >= 0 else {
            distance = nil
            return false
        }
        if let previous = lastAcceptedSampleTime {
            guard time >= previous else { return false }
            maximumGap = max(maximumGap, time - previous)
        }
        distance = value
        lastMeasurementTime = time
        lastAcceptedSampleTime = time
        sampleCount += 1
        return true
    }

    public func freshDistance(at time: TimeInterval) -> Float? {
        guard phase == .ranging, let lastMeasurementTime,
              time >= lastMeasurementTime, time - lastMeasurementTime < freshnessWindow else { return nil }
        return distance
    }

    public mutating func suspend(generation lease: UInt64) {
        guard lease == generation, phase == .ranging else { return }
        phase = .suspended
        distance = nil
        lastMeasurementTime = nil
    }

    public mutating func fail() { finish(as: .failed) }
    public mutating func stop() { finish(as: .stopped) }

    private mutating func finish(as phase: Phase) {
        generation &+= 1
        self.phase = phase
        distance = nil
        lastMeasurementTime = nil
    }

    private mutating func resetMeasurements() {
        distance = nil
        lastMeasurementTime = nil
        sampleCount = 0
        maximumGap = 0
        lastAcceptedSampleTime = nil
    }
}
