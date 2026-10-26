// swift-tools-version:5.9
// Kotlinspect's Alamofire (and Moya) adapter, a package of its own so that apps using only
// Kotlinspect never download Alamofire.
//
// It is developed in the Kotlinspect repository (swift/KotlinspectAlamofire) against the
// Kotlinspect package next to it. Each release copies this folder to
// https://github.com/asterx5/kotlinspect-alamofire, with the dependency below pointing at the
// same Kotlinspect version, and tags it with that version.
import PackageDescription

let package = Package(
    name: "KotlinspectAlamofire",
    platforms: [.iOS(.v14)],
    products: [
        .library(name: "KotlinspectAlamofire", targets: ["KotlinspectAlamofire"]),
    ],
    dependencies: [
        .package(path: "../.."), // kotlinspect:dependency
        .package(url: "https://github.com/Alamofire/Alamofire.git", .upToNextMajor(from: "5.4.0")),
    ],
    targets: [
        .target(
            name: "KotlinspectAlamofire",
            dependencies: [
                .product(name: "Kotlinspect", package: "kotlinspect"),
                .product(name: "Alamofire", package: "Alamofire"),
            ]
        ),
        .testTarget(
            name: "KotlinspectAlamofireTests",
            dependencies: [
                "KotlinspectAlamofire",
                .product(name: "Kotlinspect", package: "kotlinspect"),
                .product(name: "Alamofire", package: "Alamofire"),
            ]
        ),
    ]
)
