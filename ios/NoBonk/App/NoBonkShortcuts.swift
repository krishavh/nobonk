import AppIntents

struct NoBonkShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: OpenNoBonkIntent(),
            phrases: ["Open \(.applicationName)", "Get ready with \(.applicationName)"],
            shortTitle: "Open NoBonk",
            systemImageName: "viewfinder"
        )
    }
    static var shortcutTileColor: ShortcutTileColor { .teal }
}
