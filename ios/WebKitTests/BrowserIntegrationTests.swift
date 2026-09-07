import UIKit
import WebKit
import XCTest
@testable import NoBonk

/// Real WebKit process + production BrowserModel, using only generated/local HTML.
/// No policy mock and no network page, third-party media or granted camera permission.
@MainActor
final class BrowserIntegrationTests: XCTestCase {
    private var model: BrowserModel!
    private var window: UIWindow!
    override func setUp() async throws {
        model = BrowserModel()
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            throw XCTSkip("Hosted test needs a foreground window scene")
        }
        window = UIWindow(windowScene: scene)
        let controller = UIViewController()
        window.rootViewController = controller
        window.frame = scene.coordinateSpace.bounds
        model.webView.frame = window.bounds
        model.webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        controller.view.addSubview(model.webView)
        window.makeKeyAndVisible()
    }
    override func tearDown() async throws {
        model?.close()
        model?.webView.removeFromSuperview()
        window?.isHidden = true
        window = nil; model = nil
    }
    private func eventually(_ description: String, timeout: TimeInterval = 6, _ condition: () async throws -> Bool) async throws {
        let end = ProcessInfo.processInfo.systemUptime + timeout
        while ProcessInfo.processInfo.systemUptime < end {
            if try await condition() { return }
            try await Task.sleep(for: .milliseconds(40))
        }
        XCTFail("Timed out: \(description)")
        throw WaitFailure.timeout
    }
    private enum WaitFailure: Error { case timeout }
    private func load(_ body: String = "<p id='marker'>local fixture</p>") async throws {
        let html = "<html><head><meta name='viewport' content='width=device-width'><meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; script-src 'unsafe-inline'; media-src data:; style-src 'unsafe-inline'\"></head><body>\(body)<script>window.fixtureReady=true;</script></body></html>"
        model.webView.loadHTMLString(html, baseURL: URL(string: "https://nobonk.invalid/fixture/"))
        try await eventually("local document ready") {
            (try? await self.model.webView.evaluateJavaScript("window.fixtureReady === true")) as? Bool == true
        }
    }
    func testInitializationDoesNotNavigateToAPage() async throws {
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertNil(model.webView.url)
        XCTAssertNil(model.webView.backForwardList.currentItem)
        XCTAssertFalse(model.webView.isLoading)
        XCTAssertFalse(model.hasPage)
    }
    func testActualDocumentCustomSchemeNavigationIsCanceled() async throws {
        try await load()
        let original = model.webView.url
        _ = try await model.webView.evaluateJavaScript("window.location.href='nobonk-unhandled://fixture'; void 0")
        try await eventually("production navigation policy rejection") { self.model.message?.contains("needs another app") == true }
        XCTAssertEqual(model.webView.url, original)
        let marker = try await model.webView.evaluateJavaScript("document.getElementById('marker').textContent") as? String
        XCTAssertEqual(marker, "local fixture")
        XCTAssertFalse(model.webView.isLoading)
    }
    func testActualDocumentInsecureNavigationAndPopupDoNotOpen() async throws {
        try await load()
        let original = model.webView.url
        _ = try await model.webView.evaluateJavaScript("window.location.href='http://nobonk.invalid/not-requested'; void 0")
        try await eventually("insecure navigation rejection") { self.model.message?.contains("needs another app") == true }
        model.message = nil
        let popup = try await model.webView.evaluateJavaScript("window.open('nobonk-unhandled://popup','_blank') === null") as? Bool
        XCTAssertEqual(popup, true)
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(model.webView.url, original)
        let marker = try await model.webView.evaluateJavaScript("document.getElementById('marker').textContent") as? String
        XCTAssertEqual(marker, "local fixture")
    }
    func testSuspensionBlocksScriptRestartUntilVisibleResume() async throws {
        let media = AlertTone.wav().base64EncodedString()
        try await load("<audio id='media' loop muted preload='auto' src='data:audio/wav;base64,\(media)'></audio><p id='marker'>local fixture</p>")
        model.resume()
        // evaluateJavaScript executes this explicit test gesture; no autoplay
        // policy is weakened or configuration/delegate replaced for the test.
        _ = try await model.webView.evaluateJavaScript("window.playResult='pending'; document.getElementById('media').play().then(()=>window.playResult='playing').catch(e=>window.playResult=e.name); void 0")
        try await eventually("generated audio starts under explicit test action") {
            (try await self.model.webView.evaluateJavaScript("window.playResult")) as? String == "playing"
        }
        try await eventually("media timeline advances") {
            (try await self.model.webView.evaluateJavaScript("document.getElementById('media').currentTime")) as? Double ?? 0 > 0.02
        }
        // Positive control: ordinary pause is deliberately reversible by script.
        // The regression must distinguish this from persistent suspension.
        model.webView.pauseAllMediaPlayback(completionHandler: nil)
        try await eventually("ordinary pause completed") {
            (try await self.model.webView.evaluateJavaScript("document.getElementById('media').paused")) as? Bool == true
        }
        _ = try await model.webView.evaluateJavaScript("window.control='pending'; document.getElementById('media').play().then(()=>window.control='playing').catch(e=>window.control=e.name); void 0")
        try await eventually("positive control restarts after ordinary pause") {
            (try await self.model.webView.evaluateJavaScript("window.control")) as? String == "playing"
        }
        model.pause()
        try await eventually("media paused by production pause") {
            (try await self.model.webView.evaluateJavaScript("document.getElementById('media').paused")) as? Bool == true
        }
        let pausedTime = try await model.webView.evaluateJavaScript("document.getElementById('media').currentTime") as! Double
        _ = try await model.webView.evaluateJavaScript("window.restart='pending'; document.getElementById('media').play().then(()=>window.restart='playing').catch(e=>window.restart=e.name); void 0")
        try await Task.sleep(for: .milliseconds(350))
        let after = try await model.webView.evaluateJavaScript("document.getElementById('media').currentTime") as! Double
        XCTAssertEqual(after, pausedTime, accuracy: 0.02, "Hidden-page script must not restart the media clock")
        let restart = try await model.webView.evaluateJavaScript("window.restart") as? String
        XCTAssertNotEqual(restart, "playing")
        model.resume()
        _ = try await model.webView.evaluateJavaScript("window.resumeResult='pending'; document.getElementById('media').play().then(()=>window.resumeResult='playing').catch(e=>window.resumeResult=e.name); void 0")
        try await eventually("explicit playback after visible resume") {
            (try await self.model.webView.evaluateJavaScript("window.resumeResult")) as? String == "playing"
        }
        model.pause()
    }
    func testEditingAddressSurvivesActualWebKitLocationChanges() async throws {
        try await load()
        model.editingAddress = true; model.address = "https://example.org/my-draft"
        _ = try await model.webView.evaluateJavaScript("history.pushState({},'', '/fixture/next'); void 0")
        try await eventually("actual same-origin history URL") { self.model.webView.url?.path == "/fixture/next" }
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(model.address, "https://example.org/my-draft")
        model.editingAddress = false
        _ = try await model.webView.evaluateJavaScript("history.pushState({},'', '/fixture/committed'); void 0")
        try await eventually("committed location updates when not editing") { self.model.address == "https://nobonk.invalid/fixture/committed" }
    }
    func testSecureLocalDocumentCannotAcquireCameraOrMicrophone() async throws {
        try await load()
        let supported = try await model.webView.evaluateJavaScript("window.isSecureContext && self.origin === 'https://nobonk.invalid' && !!navigator.mediaDevices && !!navigator.mediaDevices.getUserMedia") as? Bool
        guard supported == true else { throw XCTSkip("This WebKit local-HTML origin cannot exercise secure media permission; physical website permission still needs testing.") }
        let observer = PermissionObserver(model: model)
        model.webView.uiDelegate = observer
        defer { model.webView.uiDelegate = model }
        _ = try await model.webView.evaluateJavaScript("window.captureResult='pending'; navigator.mediaDevices.getUserMedia({video:true,audio:true}).then(s=>{s.getTracks().forEach(t=>t.stop());window.captureResult='unexpected-stream'}).catch(e=>window.captureResult=e.name); void 0")
        try await eventually("media capture rejected") {
            (try await self.model.webView.evaluateJavaScript("window.captureResult")) as? String != "pending"
        }
        let result = try await model.webView.evaluateJavaScript("window.captureResult") as? String
        if result == "NotFoundError" { throw XCTSkip("Simulator lacks capture hardware; cannot distinguish production permission denial from absent devices.") }
        guard observer.decisions.count > 0 else { throw XCTSkip("WebKit rejected local capture before reaching the app delegate; this cannot prove the app permission policy.") }
        XCTAssertEqual(result, "NotAllowedError")
        XCTAssertTrue(observer.decisions.allSatisfy { $0 == .deny })
    }
}

/// Observes actual WebKit permission callbacks and forwards to the production
/// implementation unchanged; a pre-delegate WebKit rejection does not count.
@MainActor
private final class PermissionObserver: NSObject, WKUIDelegate {
    let model: BrowserModel
    var decisions: [WKPermissionDecision] = []
    init(model: BrowserModel) { self.model = model }
    func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin, initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType, decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        model.webView(webView, requestMediaCapturePermissionFor: origin, initiatedByFrame: frame, type: type) { decision in
            self.decisions.append(decision); decisionHandler(decision)
        }
    }
}
