// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "NoBonkCore", products: [.library(name: "NoBonkCore", targets: ["NoBonkCore"])], targets: [
    .target(name: "NoBonkCore", path: "NoBonk/Core"),
    .testTarget(name: "NoBonkCoreTests", dependencies: ["NoBonkCore"], path: "Tests", resources: [.copy("Fixtures")])
])
