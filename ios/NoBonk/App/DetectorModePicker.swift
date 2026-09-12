import SwiftUI

/// Kept separate so the model picker can be placed beside either camera layout.
struct DetectorModePicker: View {
    @ObservedObject var camera: CameraModel
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("WHAT TO NOTICE").font(.caption2.bold()).tracking(1.7)
            HStack(spacing: 10) {
                ForEach(DetectorMode.allCases, id: \.self) { mode in
                    Button { camera.detectorMode = mode } label: {
                        VStack(spacing: 6) {
                            Image(systemName: mode == .visionPeople ? "person.crop.rectangle" : "viewfinder")
                            Text(mode.rawValue).font(.caption.bold())
                        }.frame(maxWidth: .infinity).padding(12)
                            .background(camera.detectorMode == mode ? Color.mint.opacity(0.18) : .white.opacity(0.04), in: RoundedRectangle(cornerRadius: 14))
                    }.buttonStyle(.plain)
                        .disabled(camera.running || camera.starting || (mode == .fastObjects && !camera.fastModelAvailable))
                        .accessibilityAddTraits(camera.detectorMode == mode ? .isSelected : [])
                }
            }
            Text(camera.detectorMode.summary).font(.caption).foregroundStyle(.secondary)
            Text(camera.detectorMode == .visionPeople
                 ? "People mode uses Apple Vision. It does not detect vehicles, pets or other obstacles."
                 : "Fast Objects is an early preview for eight object classes. It can miss or mislabel objects; walls, steps, ground hazards and distance are not covered. ‘Fast’ is the model name, not a speed guarantee.")
                .font(.caption).foregroundStyle(.secondary)
            if !camera.fastModelAvailable {
                Text("Fast Objects is not included in this build.").font(.caption).foregroundStyle(.orange)
            }
            if let timing = camera.detectorTiming, camera.detectorMode == .fastObjects {
                Text(timing.configuration).font(.caption).foregroundStyle(.mint)
                Text(String(format: "Recent frame: prepare %.0f ms · infer %.0f ms · decode %.0f ms", timing.preprocessMS, timing.inferenceMS, timing.decodeMS))
                    .font(.caption2).monospacedDigit().foregroundStyle(.secondary)
            }
            if camera.running || camera.starting { Text("Stop scanning to change mode.").font(.caption2).foregroundStyle(.secondary) }
        }.padding(16).background(.white.opacity(0.045), in: RoundedRectangle(cornerRadius: 20))
    }
}
