import Foundation

/// The output is created and used only on the controller's serial queue.
protocol CueAudioOutput: AnyObject {
    var duration: TimeInterval { get }
    func prepare() throws
    func activate() throws
    func play() -> Bool
    func stopAndDeactivate()
}

/// Activation can block. Keep it off Main and invalidate pending work on Stop.
/// A delayed completion from an old cue must not silence a newer cue.
final class CuePlaybackController: @unchecked Sendable {
    typealias FinishScheduler = @Sendable (TimeInterval, @escaping @Sendable () -> Void) -> DispatchWorkItem
    private let queue: DispatchQueue
    private let scheduleFinish: FinishScheduler
    private let outputFactory: @Sendable () -> any CueAudioOutput
    private let gate = CaptureGeneration()
    private var output: (any CueAudioOutput)?
    private var finish: DispatchWorkItem?
    init(queue: DispatchQueue = DispatchQueue(label: "ai.genwhy.nobonk.cue", qos: .userInitiated),
         scheduleFinish: FinishScheduler? = nil,
         outputFactory: @escaping @Sendable () -> any CueAudioOutput) {
        self.queue = queue
        self.scheduleFinish = scheduleFinish ?? { delay, body in
            let work = DispatchWorkItem(block: body)
            queue.asyncAfter(deadline: .now() + delay, execute: work)
            return work
        }
        self.outputFactory = outputFactory
    }
    func play(result: @escaping @Sendable (Bool) -> Void = { _ in }) {
        let token = gate.begin()
        queue.async { [self] in
            guard gate.accepts(token) else { return }
            finish?.cancel()
            if output == nil { output = outputFactory() }
            guard let output else { return }
            do {
                try output.prepare()
                guard gate.accepts(token) else { output.stopAndDeactivate(); return }
                try output.activate()
                guard gate.accepts(token) else { output.stopAndDeactivate(); return }
                let played = output.play()
                // Even if Stop raced the final play call, cleanup is already queued
                // and this stale result is never published to the next scan.
                guard gate.accepts(token) else { output.stopAndDeactivate(); return }
                result(played)
                guard played else { output.stopAndDeactivate(); return }
                finish = scheduleFinish(max(0.05, min(1, output.duration)) + 0.05) { [weak self] in
                    guard let self, self.gate.accepts(token) else { return }
                    self.output?.stopAndDeactivate()
                }
            } catch {
                output.stopAndDeactivate()
                if gate.accepts(token) { result(false) }
            }
        }
    }
    func stop() {
        gate.stop()
        queue.async { [self] in
            finish?.cancel(); finish = nil
            output?.stopAndDeactivate()
        }
    }
}
