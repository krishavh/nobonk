// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "NearbyPhoneLabCore",
    platforms: [.macOS(.v13)],
    products: [.library(name: "NearbyPhoneLabCore", targets: ["NearbyPhoneLabCore"])],
    targets: [
        .target(name: "NearbyPhoneLabCore", path: "Core"),
        .testTarget(name: "NearbyPhoneLabCoreTests", dependencies: ["NearbyPhoneLabCore"], path: "Tests")
    ]
)
