import SwiftUI
import UIKit
import XCTest
@testable import NoBonk

/// Hosts the real SwiftUI editor in a constrained UIKit window. These checks
/// exercise layout only: no keyboard events, message sending or camera access.
@MainActor
final class DraftLayoutTests: XCTestCase {
    private var window: UIWindow?

    override func tearDown() async throws {
        window?.isHidden = true
        window?.rootViewController = nil
        window = nil
    }

    func testCompactDraftKeepsAnEditableLineWithinAShortPane() async throws {
        try await assertDraftFits(size: CGSize(width: 320, height: 145), type: .large)
    }

    func testAccessibilityDraftFitsNarrowPaneWithoutClippingTheEditor() async throws {
        try await assertDraftFits(size: CGSize(width: 351, height: 360), type: .accessibility3)
    }

    func testLargestTextDraftRetainsAnEditableLine() async throws {
        try await assertDraftFits(size: CGSize(width: 351, height: 420), type: .accessibility5)
    }

    func testReadModeFitsShortPaneWithoutAnEditableKeyboardSurface() async throws {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            throw XCTSkip("Hosted layout test needs a window scene")
        }
        let content = DraftScanPane(
            text: .constant("A selected message for reading beside the camera."),
            canMessage: true, compact: true,
            onMessages: { XCTFail("Reading must never send") },
            onShare: { XCTFail("Reading must never share automatically") },
            onHelp: {}, initialMode: .read
        )
        let controller = UIHostingController(rootView: content)
        controller.safeAreaRegions = []
        let window = UIWindow(windowScene: scene)
        self.window = window
        window.frame = CGRect(x: 0, y: 0, width: 320, height: 145)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller.view.frame = window.bounds
        controller.view.layoutIfNeeded()
        let minimum = controller.sizeThatFits(in: CGSize(width: 320, height: 1))
        XCTAssertLessThanOrEqual(minimum.height, 146)
        XCTAssertFalse(descendants(of: controller.view).compactMap { $0 as? UITextView }.contains { $0.isEditable },
                       "Read mode must not mount an editable keyboard surface")
    }

    private func assertDraftFits(size: CGSize, type: DynamicTypeSize, file: StaticString = #filePath, line: UInt = #line) async throws {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            throw XCTSkip("Hosted layout test needs a window scene")
        }
        let content = DraftScanPane(
            text: .constant("A draft written beside the camera."),
            canMessage: true,
            compact: true,
            onMessages: { XCTFail("Layout must never initiate a Messages handoff") },
            onShare: { XCTFail("Layout must never initiate sharing") },
            onHelp: {}
        ).environment(\.dynamicTypeSize, type)
        let controller = UIHostingController(rootView: content)
        controller.safeAreaRegions = []
        let window = UIWindow(windowScene: scene)
        self.window = window
        window.frame = CGRect(origin: .zero, size: size)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller.view.frame = window.bounds
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()

        // A nearly-zero proposal measures the minimum height of the complete
        // pane, including its handoff controls. This catches a VStack that
        // merely overflows its assigned rectangle while the editor still exists.
        let minimum = controller.sizeThatFits(in: CGSize(width: size.width, height: 1))
        XCTAssertLessThanOrEqual(minimum.height, size.height + 1,
                                 "The draft's controls cannot fit in the available pane", file: file, line: line)

        var editor: UITextView?
        for _ in 0..<40 {
            controller.view.setNeedsLayout()
            controller.view.layoutIfNeeded()
            editor = descendants(of: controller.view).compactMap { $0 as? UITextView }.first
            if let editor, editor.bounds.height > 0 { break }
            try await Task.sleep(for: .milliseconds(25))
        }
        let textView = try XCTUnwrap(editor, "The real TextEditor must be mounted", file: file, line: line)
        let editorFrame = textView.convert(textView.bounds, to: controller.view)
        let visibleBounds = controller.view.bounds.insetBy(dx: -1, dy: -1)
        XCTAssertTrue(visibleBounds.contains(editorFrame),
                      "The editor extends outside the pane: \(editorFrame), pane \(controller.view.bounds)", file: file, line: line)
        XCTAssertGreaterThanOrEqual(editorFrame.width, 44, file: file, line: line)
        XCTAssertGreaterThanOrEqual(editorFrame.height, max(44, ceil(textView.font?.lineHeight ?? 0)),
                                    "At least one editable line must remain visible at the selected text size", file: file, line: line)
        XCTAssertTrue(textView.isEditable, file: file, line: line)
        XCTAssertTrue(textView.isScrollEnabled, "Long drafts must remain reachable without growing the pane", file: file, line: line)
    }

    private func descendants(of view: UIView) -> [UIView] {
        view.subviews.flatMap { [$0] + descendants(of: $0) }
    }
}
