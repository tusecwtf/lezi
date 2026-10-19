// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Lezi",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "LeziCore", targets: ["LeziCore"]),
        .library(name: "LeziUI", targets: ["LeziUI"]),
    ],
    targets: [
        .target(name: "LeziCore"),
        .target(name: "LeziUI", dependencies: ["LeziCore"], exclude: ["README.md"]),
        .testTarget(name: "LeziCoreTests", dependencies: ["LeziCore"]),
        .testTarget(name: "LeziDomainTests", dependencies: ["LeziCore"]),
        .testTarget(name: "LeziPresentationTests", dependencies: ["LeziUI", "LeziCore"]),
    ]
)
