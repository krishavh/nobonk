import XCTest
@testable import NearbyPhoneLabCore

final class LabSessionStateTests: XCTestCase {
    func rangingState() -> LabSessionState {
        var state = LabSessionState()
        let lease = state.begin()
        state.startRanging(generation: lease)
        return state
    }

    func testNoMeasurementIsNotZeroDistance() {
        let state = rangingState()
        XCTAssertNil(state.freshDistance(at: 10))
    }

    func testAReadingExpiresWithoutReceivingAnotherCallback() {
        var state = rangingState()
        state.receive(distance: 1.2, at: 10, generation: state.generation)
        XCTAssertEqual(state.freshDistance(at: 11.9), 1.2)
        XCTAssertNil(state.freshDistance(at: 12))
    }

    func testStopRejectsLateMeasurementAndResume() {
        var state = rangingState()
        let lease = state.generation
        state.stop()
        state.startRanging(generation: lease)
        XCTAssertFalse(state.receive(distance: 0.4, at: 20, generation: lease))
        XCTAssertEqual(state.phase, .stopped)
        XCTAssertNil(state.freshDistance(at: 20))
    }

    func testPreviousSessionCannotModifyNewSession() {
        var state = rangingState()
        let oldLease = state.generation
        let newLease = state.begin()
        state.startRanging(generation: newLease)
        state.suspend(generation: oldLease)
        XCTAssertFalse(state.receive(distance: 0.1, at: 10, generation: oldLease))
        XCTAssertTrue(state.receive(distance: 3, at: 11, generation: newLease))
        XCTAssertEqual(state.sampleCount, 1)
    }

    func testSuspendClearsMeasurementAndRejectsDataUntilResumed() {
        var state = rangingState()
        let lease = state.generation
        state.receive(distance: 1, at: 10, generation: lease)
        state.suspend(generation: lease)
        XCTAssertNil(state.freshDistance(at: 10.1))
        XCTAssertFalse(state.receive(distance: 2, at: 11, generation: lease))
        state.startRanging(generation: lease)
        XCTAssertNil(state.freshDistance(at: 12))
        XCTAssertTrue(state.receive(distance: 2, at: 12, generation: lease))
    }

    func testInvalidAndOutOfOrderMeasurementsAreNotFreshened() {
        var state = rangingState()
        let lease = state.generation
        state.receive(distance: 2, at: 10, generation: lease)
        XCTAssertFalse(state.receive(distance: 1, at: 9, generation: lease))
        XCTAssertEqual(state.distance, 2)
        XCTAssertFalse(state.receive(distance: .nan, at: 11, generation: lease))
        XCTAssertNil(state.freshDistance(at: 11))
        XCTAssertFalse(state.receive(distance: -1, at: 12, generation: lease))
        XCTAssertFalse(state.receive(distance: 1, at: .infinity, generation: lease))
        XCTAssertEqual(state.sampleCount, 1)
    }

    func testFailureInvalidatesLeaseAndGapsAreInstrumented() {
        var state = rangingState()
        let lease = state.generation
        state.receive(distance: 2, at: 10, generation: lease)
        state.receive(distance: 3, at: 13, generation: lease)
        XCTAssertEqual(state.maximumGap, 3)
        state.fail()
        XCTAssertFalse(state.receive(distance: 1, at: 14, generation: lease))
        XCTAssertEqual(state.phase, .failed)
    }

    func testCancelBeforePairingCompletesRejectsLateConfiguration() {
        var state = LabSessionState()
        let lease = state.begin()
        state.stop()
        state.startRanging(generation: lease)
        XCTAssertEqual(state.phase, .stopped)
        XCTAssertFalse(state.receive(distance: 1, at: 10, generation: lease))
    }

    func testMeasurementGapIncludesSuspension() {
        var state = rangingState()
        let lease = state.generation
        state.receive(distance: 2, at: 10, generation: lease)
        state.suspend(generation: lease)
        state.startRanging(generation: lease)
        state.receive(distance: 3, at: 25, generation: lease)
        XCTAssertEqual(state.maximumGap, 15)
    }

    func testZeroIsValidButMissingDistanceClearsTheReading() {
        var state = rangingState()
        let lease = state.generation
        XCTAssertTrue(state.receive(distance: 0, at: 10, generation: lease))
        XCTAssertEqual(state.freshDistance(at: 10.1), 0)
        XCTAssertFalse(state.receive(distance: nil, at: 10.2, generation: lease))
        XCTAssertNil(state.freshDistance(at: 10.3))
    }
}
