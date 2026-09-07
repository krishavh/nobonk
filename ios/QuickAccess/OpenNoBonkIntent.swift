import AppIntents

/// A foreground doorway, never a command to start the camera. This source belongs
/// to both the app and its controls extension, as required by WidgetKit.
struct OpenNoBonkIntent: OpenIntent {
    static let title: LocalizedStringResource = "Open NoBonk"
    static let description = IntentDescription("Open NoBonk to read the safety reminder and set up a scan. Scanning starts only when you tap Start.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "Screen", default: .setup)
    var target: NoBonkDestination

    // OpenIntent asks iOS to bring the containing app forward. Its usual root
    // view owns the safety gate. Do not read/write acknowledgment or capture here.
    func perform() async throws -> some IntentResult { .result() }
}

enum NoBonkDestination: String, AppEnum {
    case setup
    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "NoBonk screen")
    static let caseDisplayRepresentations: [NoBonkDestination: DisplayRepresentation] = [
        .setup: DisplayRepresentation(title: "Safety and setup")
    ]
}
