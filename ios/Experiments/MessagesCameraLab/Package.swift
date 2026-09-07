// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "MessagesCameraLabCore", platforms: [.macOS(.v14)], targets: [
    .target(name: "MessagesCameraLabCore", path: "Core"),
    .testTarget(name: "MessagesCameraLabTests", dependencies: ["MessagesCameraLabCore"], path: "Tests")
])
