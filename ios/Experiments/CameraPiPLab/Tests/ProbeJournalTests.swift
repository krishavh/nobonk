import CoreMedia
import CoreVideo
import XCTest
@testable import ProbeJournal

final class ProbeJournalTests: XCTestCase {
    private func frame(_ seconds: Double) throws -> CMSampleBuffer {
        var pixel: CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault, 2, 2, kCVPixelFormatType_32BGRA, nil, &pixel), kCVReturnSuccess)
        let image = try XCTUnwrap(pixel)
        var format: CMVideoFormatDescription?
        XCTAssertEqual(CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: image, formatDescriptionOut: &format), noErr)
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: 15), presentationTimeStamp: CMTime(seconds: seconds, preferredTimescale: 600), decodeTimeStamp: .invalid)
        var buffer: CMSampleBuffer?
        XCTAssertEqual(CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: image, formatDescription: try XCTUnwrap(format), sampleTiming: &timing, sampleBufferOut: &buffer), noErr)
        return try XCTUnwrap(buffer)
    }
    func testStopRejectsQueuedFramesAndStateThenAllowsNewSession() throws {
        let journal = ProbeJournal()
        let old = journal.begin()
        journal.append(try frame(1), token: old, now: 1)
        journal.invalidate(message: "Stop")
        journal.append(try frame(2), token: old, now: 2)
        journal.update(old) { $0.status = "Late running callback" }
        XCTAssertNil(journal.takeLatest())
        XCTAssertEqual(journal.snapshot().frameCount, 1)
        XCTAssertEqual(journal.snapshot().status, "Stop")
        let current = journal.begin()
        journal.append(try frame(3), token: old, now: 3)
        XCTAssertEqual(journal.snapshot().frameCount, 0)
        journal.append(try frame(4), token: current, now: 4)
        XCTAssertEqual(journal.takeLatest()?.0, current)
        XCTAssertEqual(journal.snapshot().frameCount, 1)
    }
    func testFrozenFramesDoNotCountAndMailboxOnlyRetainsLatest() throws {
        let journal = ProbeJournal()
        let token = journal.begin()
        journal.append(try frame(2), token: token, now: 2)
        journal.append(try frame(2), token: token, now: 3)
        journal.append(try frame(1), token: token, now: 4)
        XCTAssertEqual(journal.snapshot().frameCount, 1)
        journal.append(try frame(5), token: token, now: 5)
        let sample = try XCTUnwrap(journal.takeLatest()?.1)
        XCTAssertEqual(CMSampleBufferGetPresentationTimeStamp(sample).seconds, 5)
        XCTAssertNil(journal.takeLatest())
        XCTAssertEqual(journal.snapshot().longestGap, 3)
    }
    func testBackgroundReportSeparatesGraceFramesAndFreezesAgeAtReturn() throws {
        let journal = ProbeJournal()
        let token = journal.begin()
        journal.append(try frame(9), token: token, now: 9)
        journal.background(true, now: 10)
        journal.append(try frame(10.2), token: token, now: 10.2)
        journal.append(try frame(11.1), token: token, now: 11.1)
        journal.background(false, now: 25)
        journal.append(try frame(25.1), token: token, now: 25.1)
        let report = journal.snapshot()
        XCTAssertEqual(report.backgroundFrames, 2)
        XCTAssertEqual(report.backgroundFramesAfterOneSecond, 1)
        XCTAssertEqual(report.lastBackgroundDuration, 15)
        XCTAssertEqual(report.frameAgeAtReturn, 13.9, accuracy: 0.0001)
        XCTAssertEqual(report.lastPTS, 25.1, accuracy: 0.0001)
        XCTAssertFalse(report.inBackground)
    }
}
