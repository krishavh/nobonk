import XCTest
@testable import NoBonkCore

final class NativeSessionOwnershipTests: XCTestCase {
    private final class Session {
        let onRelease: () -> Void
        init(onRelease: @escaping () -> Void = {}) { self.onRelease = onRelease }
        deinit { onRelease() }
    }
    private enum Failure: Error { case warmUp, replacement }

    func testFailedWarmUpSessionIsDestroyedBeforeCPUFactoryRuns() {
        var released = false
        var session: Session? = Session { released = true }
        weak let failed = session
        do {
            throw Failure.warmUp
        } catch {
            NativeSessionOwnership.discardAndReplace(&session) {
                XCTAssertNil(failed, "The failed native session must not overlap CPU construction")
                XCTAssertTrue(released)
                return Session()
            }
        }
        XCTAssertNotNil(session)
        XCTAssertNil(failed)
    }

    func testReplacementFailureLeavesNoFailedOrPartiallyAdoptedSession() {
        var releases = 0
        var session: Session? = Session { releases += 1 }
        weak let failed = session
        XCTAssertThrowsError(try NativeSessionOwnership.discardAndReplace(&session) {
            XCTAssertNil(failed)
            XCTAssertEqual(releases, 1)
            throw Failure.replacement
        })
        XCTAssertNil(session)
        XCTAssertNil(failed)
        XCTAssertEqual(releases, 1)
    }
}
