import ActivityKit
import AppIntents
import Foundation

struct NearbyPhoneActivity: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var distance: Float?
        var sampleCount: Int
        var measuredAt: Date?
        var status: String
    }
    var partner: String
}

extension Notification.Name {
    static let stopNearbyPhoneLab = Notification.Name("ai.genwhy.nobonk.nearbylab.stop")
}

struct StopNearbyPhoneLabIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Stop nearby phone experiment"
    static let description = IntentDescription("Ends the experiment and stops sharing relative distance with the paired phone.")

    func perform() async throws -> some IntentResult {
        // LiveActivityIntent runs in the containing app's process. Ending activities
        // directly also clears orphaned UI if that process had already been killed.
        await MainActor.run {
            NotificationCenter.default.post(name: .stopNearbyPhoneLab, object: nil)
        }
        for activity in Activity<NearbyPhoneActivity>.activities {
            let state = NearbyPhoneActivity.ContentState(distance: nil, sampleCount: 0, measuredAt: nil, status: "Stopped")
            await activity.end(ActivityContent(state: state, staleDate: nil), dismissalPolicy: .immediate)
        }
        return .result()
    }
}
