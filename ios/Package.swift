// swift-tools-version: 6.0
import PackageDescription

#if os(Linux)
let sqlitePkgConfig: String? = "sqlite3"
#else
// Apple builds must link the selected SDK's SQLite, never a host Homebrew dylib.
let sqlitePkgConfig: String? = nil
#endif

let package = Package(
    name: "Lezi",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "LeziCore", targets: ["LeziCore"]),
        .library(name: "LeziUI", targets: ["LeziUI"]),
        .library(name: "LeziStorage", targets: ["LeziStorage"]),
    ],
    dependencies: [
        // 4.3.1 is the last release supporting our Swift 6.0 baseline.
        .package(url: "https://github.com/apple/swift-crypto.git", exact: "4.3.1"),
        // Package.resolved locks the compatible transitive ASN.1 release.
        // CI uses --force-resolved-versions rather than updating dependencies.
    ],
    targets: [
        .systemLibrary(name: "CSQLite", pkgConfig: sqlitePkgConfig, providers: [
            .apt(["libsqlite3-dev"]),
        ]),
        .target(name: "LeziStorage", dependencies: ["LeziCore", "CSQLite"]),
        .target(name: "LeziCore", dependencies: [
            .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux])),
        ]),
        .target(name: "LeziUI", dependencies: ["LeziCore", "LeziStorage"], exclude: ["README.md"]),
        .testTarget(name: "LeziCoreTests", dependencies: ["LeziCore"]),
        .testTarget(name: "LeziStorageTests", dependencies: ["LeziStorage", "LeziCore", "CSQLite"]),
        .testTarget(name: "LeziDomainTests", dependencies: ["LeziCore"]),
        .testTarget(name: "LeziPresentationTests", dependencies: ["LeziUI", "LeziCore", "LeziStorage"]),
    ]
)
