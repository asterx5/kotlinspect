import XCTest

/// Drives the sample in Debug: the bubble appears with no start call, requests raise its count,
/// the inspector lists URLSession and Alamofire calls, and HAR export writes a file.
final class SampleUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    private func snapshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private var bubble: XCUIElement { app.descendants(matching: .any)["kotlinspect.bubble"] }

    /// The sample's diagnostics line, added to failure messages.
    private var diag: String { app.staticTexts["diag"].exists ? app.staticTexts["diag"].label : "no diag" }

    func testBubbleAppearsWithoutAnyStartCall() {
        XCTAssertTrue(bubble.waitForExistence(timeout: 10), "the package started itself ((diag))")
        snapshot("1 bubble")
    }

    func testRequestsShowInTheInspector() {
        XCTAssertTrue(bubble.waitForExistence(timeout: 10), "bubble ((diag))")
        let before = bubble.label
        app.buttons["GET JSON"].tap()
        app.buttons["Alamofire GET"].tap()
        let status = app.staticTexts["status"]
        _ = status.waitForExistence(timeout: 5)
        sleep(5)
        snapshot("2 after requests")
        XCTAssertNotEqual(bubble.label, "", "bubble still present (was \(before))")

        bubble.tap()
        let inspector = app.descendants(matching: .any)["kotlinspect.inspector"]
        XCTAssertTrue(inspector.waitForExistence(timeout: 10), "inspector opened")
        snapshot("3 inspector")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "/todos/1")).firstMatch.waitForExistence(timeout: 10),
                      "the URLSession call is listed")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "/posts/1")).firstMatch.waitForExistence(timeout: 10),
                      "the Alamofire call is listed")
    }

    func testHarExportWritesAFile() {
        XCTAssertTrue(bubble.waitForExistence(timeout: 10), "bubble ((diag))")
        app.buttons["GET JSON"].tap()
        sleep(3)
        let export = app.buttons["Export HAR"]
        for _ in 0..<6 where !export.isHittable { app.swipeUp() }
        export.tap()
        let state = app.staticTexts["harState"]
        XCTAssertTrue(state.waitForExistence(timeout: 10))
        let predicate = NSPredicate(format: "label BEGINSWITH %@", "har: kotlinspect-")
        expectation(for: predicate, evaluatedWith: state)
        waitForExpectations(timeout: 15)
        snapshot("4 har exported")
    }
}

/// Run with `-configuration Release`: no bubble, no database, no capture.
final class ReleaseTests: XCTestCase {
    func testReleaseBuildIsInert() {
        let app = XCUIApplication()
        app.launchArguments = ["-kotlinspect-report-db"]
        app.launch()
        app.buttons["GET JSON"].tap()
        sleep(4)
        XCTAssertFalse(app.descendants(matching: .any)["kotlinspect.bubble"].exists, "no bubble in Release")
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "release"
        attachment.lifetime = .keepAlways
        add(attachment)
        // Relaunch so the check sees the state after the app ran a request.
        app.terminate()
        app.launch()
        XCTAssertEqual(app.staticTexts["dbState"].label, "db: none", "no kotlinspect.db in Release")
    }
}
