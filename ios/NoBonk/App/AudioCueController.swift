import AVFoundation

/// No recording or background-audio mode. Only an actual requested cue activates
/// playback; stopping it restores other apps' audio immediately afterwards.
final class ForegroundCueOutput: CueAudioOutput {
    private var player: AVAudioPlayer?
    private var ownsActivation = false
    var duration: TimeInterval { player?.duration ?? 0.2 }
    func prepare() throws {
        if player == nil { player = try AVAudioPlayer(data: AlertTone.wav()) }
        player?.stop()
        player?.currentTime = 0
    }
    func activate() throws {
        let session = AVAudioSession.sharedInstance()
        // Sound is an explicit control. Playback makes its meaning independent of
        // the silent switch; device volume/routing and interruptions still apply.
        try session.setCategory(.playback, mode: .default, options: [.duckOthers])
        try session.setActive(true)
        ownsActivation = true
        guard player?.prepareToPlay() == true else { throw CueError.unavailable }
    }
    private enum CueError: Error { case unavailable }
    func play() -> Bool { player?.play() ?? false }
    func stopAndDeactivate() {
        player?.stop()
        guard ownsActivation else { return }
        ownsActivation = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
