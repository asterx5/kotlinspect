// swift-tools-version:5.9
// Kotlinspect for pure iOS apps: automatic URLSession capture, an optional Alamofire adapter, and
// the native inspector, bubble and toast. The engine is kotlinspect-core, prebuilt as an
// XCFramework attached to each GitHub release.
//
// Kotlin Multiplatform apps must NOT add this package: they get Kotlinspect through Gradle and
// their own shared framework (two Kotlin runtimes would mean two Kotlinspect instances).
import PackageDescription
import Foundation

// CI and local development point this at a freshly built XCFramework before any release exists.
let localXCFramework = ProcessInfo.processInfo.environment["KOTLINSPECT_LOCAL_XCFRAMEWORK"]

let coreTarget: Target = localXCFramework.map {
    .binaryTarget(name: "KotlinspectCore", path: $0)
} ?? .binaryTarget(
    name: "KotlinspectCore",
    url: "https://github.com/asterx5/kotlinspect/releases/download/v0.0.0/KotlinspectCore.xcframework.zip", // kotlinspect:url
    checksum: "0000000000000000000000000000000000000000000000000000000000000000" // kotlinspect:checksum
)

let package = Package(
    name: "Kotlinspect",
    platforms: [.iOS(.v14)],
    products: [
        .library(name: "Kotlinspect", targets: ["Kotlinspect"]),
        .library(name: "KotlinspectAlamofire", targets: ["KotlinspectAlamofire"]),
    ],
    dependencies: [
        .package(url: "https://github.com/Alamofire/Alamofire.git", .upToNextMajor(from: "5.4.0")),
    ],
    targets: [
        coreTarget,
        .target(name: "KotlinspectLoader", path: "swift/Sources/KotlinspectLoader"),
        .target(
            name: "Kotlinspect",
            dependencies: ["KotlinspectCore", "KotlinspectLoader"],
            path: "swift/Sources/Kotlinspect"
        ),
        .target(
            name: "KotlinspectAlamofire",
            dependencies: ["Kotlinspect", .product(name: "Alamofire", package: "Alamofire")],
            path: "swift/Sources/KotlinspectAlamofire"
        ),
        .testTarget(
            name: "KotlinspectTests",
            dependencies: ["Kotlinspect", "KotlinspectAlamofire", .product(name: "Alamofire", package: "Alamofire")],
            path: "swift/Tests/KotlinspectTests"
        ),
    ]
)
