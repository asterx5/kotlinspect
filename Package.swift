// swift-tools-version:5.7
// SwiftUI wrapper for Kotlinspect's native iOS overlay. The Kotlin side comes from your app's KMP
// framework, which must export io.github.asterx5:kotlinspect-core (see README, "iOS without Compose").
import PackageDescription

let package = Package(
    name: "KotlinspectSwiftUI",
    platforms: [.iOS(.v14)],
    products: [
        .library(name: "KotlinspectSwiftUI", targets: ["KotlinspectSwiftUI"]),
    ],
    targets: [
        .target(name: "KotlinspectSwiftUI", path: "swift/Sources/KotlinspectSwiftUI"),
    ]
)
