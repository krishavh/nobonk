import SwiftUI

@main
struct MessagesCameraLabApp: App {
    var body: some Scene {
        WindowGroup {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Image("BrandIcon").resizable().scaledToFit().frame(width: 84, height: 84)
                    Text("NoBonk\nMessages Lab").font(.largeTitle.bold())
                    Text("A visible camera panel inside Messages.").font(.title3)
                    Text("This separate research app installs a Messages extension. It does not run NoBonk in the background or send messages.")
                    VStack(alignment: .leading, spacing: 14) {
                        Text("1. Open Messages and a conversation you choose.")
                        Text("2. Open the + app menu, then find NoBonk Messages Lab (possibly under More). The menu depends on your iOS version.")
                        Text("3. Read the notice, acknowledge it and tap Start camera. Allow camera permission if prompted. If the prompt suspends the panel, reopen, acknowledge and tap Start again.")
                        Text("4. While stationary, have a helper enter the camera view. Check the frame age and scan counts. Tap Stop and verify the camera turns off.")
                    }
                    Text("Reading above the compact panel is the hypothesis being tested. Compact replaces the keyboard. Simultaneous typing, other apps, lock-screen operation and App Store acceptance are unproven.")
                        .foregroundStyle(.yellow)
                    Text("People detection can miss people. It does not detect all hazards or replace paying attention. This lab supplies no navigation, collision guarantee or alerts.")
                    Text("Frames stay in memory. The extension never reads, inserts or sends conversation content. No microphone, network or background mode is used.")
                        .font(.callout).foregroundStyle(.secondary)
                    Text("By Krishav · research preview").font(.caption)
                }
                .padding(24).frame(maxWidth: 620, alignment: .leading)
            }
            .background(Color(red: 0.035, green: 0.06, blue: 0.085))
            .foregroundStyle(.white)
            .preferredColorScheme(.dark)
        }
    }
}
