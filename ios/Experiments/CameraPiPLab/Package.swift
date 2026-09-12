// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CameraPiPProbeJournal",
    platforms: [.macOS(.v14)],
    targets: [
        .target(name: "ProbeJournal", path: "App", exclude: ["CaptureProbe.swift", "CameraPiPLab.swift"], sources: ["ProbeJournal.swift", "PiPTrialGate.swift", "ProbeLifecycleBoundary.swift", "CaptureEpoch.swift"]),
        .testTarget(name: "ProbeJournalTests", dependencies: ["ProbeJournal"], path: "Tests")
    ]
)
