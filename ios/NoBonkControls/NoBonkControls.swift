import AppIntents
import SwiftUI
import WidgetKit

@main
struct NoBonkControls: WidgetBundle {
    var body: some Widget { OpenNoBonkControl() }
}

struct OpenNoBonkControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "ai.genwhy.nobonk.open") {
            ControlWidgetButton(action: OpenNoBonkIntent()) {
                Label("Open NoBonk", systemImage: "viewfinder")
            }
        }
        .displayName("Open NoBonk")
        .description("Open the app to read the reminder and set up your scan. Does not scan in the background.")
    }
}
