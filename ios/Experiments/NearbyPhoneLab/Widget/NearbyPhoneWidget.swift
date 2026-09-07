import ActivityKit
import SwiftUI
import WidgetKit

@main
struct NearbyPhoneWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: NearbyPhoneActivity.self) { context in
            HStack(spacing: 14) {
                Image(systemName: "iphone.radiowaves.left.and.right").font(.title2)
                VStack(alignment: .leading, spacing: 4) {
                    Text("NEARBY PHONE LAB").font(.caption.bold())
                    Text(reading(context)).font(.title2.bold().monospacedDigit())
                    Text("One paired phone • no obstacle detection").font(.caption)
                }
                Spacer(minLength: 0)
                Button(intent: StopNearbyPhoneLabIntent()) { Image(systemName: "stop.fill") }
                    .buttonStyle(.bordered).tint(.red).accessibilityLabel("Stop experiment")
            }
            .padding().activityBackgroundTint(Color(red: 0.06, green: 0.12, blue: 0.13))
            .activitySystemActionForegroundColor(.white).foregroundStyle(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label("Phone lab", systemImage: "iphone.radiowaves.left.and.right")
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(reading(context)).monospacedDigit()
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        Text("Paired phone only").font(.caption)
                        Spacer()
                        Button("Stop", intent: StopNearbyPhoneLabIntent()).tint(.red)
                    }
                }
            } compactLeading: {
                Image(systemName: "iphone.radiowaves.left.and.right")
            } compactTrailing: {
                Text(context.isStale || context.state.distance == nil ? "—" : reading(context))
                    .font(.caption.monospacedDigit())
            } minimal: {
                Image(systemName: context.isStale || context.state.distance == nil ? "exclamationmark.circle" : "iphone")
            }
            .widgetURL(URL(string: "nobonknearbylab://open"))
        }
    }

    private func reading(_ context: ActivityViewContext<NearbyPhoneActivity>) -> String {
        guard !context.isStale, let distance = context.state.distance else { return "Signal unavailable" }
        return String(format: "%.1f m", distance)
    }
}
