import XCTest
@testable import NoBonkCore
final class BrowserDestinationTests: XCTestCase {
    func testTypedDomainUsesHTTPSAndPreservesPath() {
        XCTAssertEqual(BrowserDestination.url(from: "  genwhy.ai/projects?q=hello#start  ")?.absoluteString,
                       "https://genwhy.ai/projects?q=hello#start")
        XCTAssertEqual(BrowserDestination.url(from: "https://example.org/a%20b")?.path, "/a b")
    }
    func testSchemesAndCredentialsDoNotEscapeEmbeddedBrowser() {
        for text in ["javascript:alert(1)", "file:///etc/passwd", "data:text/html,x", "http://example.org", "tel:123", "mailto:a@example.org", "https://user:pass@example.org", "https://user@example.org", "https://", "", "some search terms", "https://example.org/\nfoo"] {
            XCTAssertNil(BrowserDestination.url(from: text), text)
        }
    }
    func testRedirectPolicyUsesSameSecureAddressRules() {
        XCTAssertTrue(BrowserDestination.allows(URL(string: "https://example.org/watch")!))
        XCTAssertFalse(BrowserDestination.allows(URL(string: "myapp://open")!))
        XCTAssertFalse(BrowserDestination.allows(URL(string: "https://user:pass@example.org")!))
    }
}
