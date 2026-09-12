import Foundation
import XCTest
@testable import NearbyPhoneLabCore

final class NearbyTrialJournalTests: XCTestCase {
    private final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var value: TimeInterval = 0
        func set(_ value: TimeInterval) { lock.lock(); self.value = value; lock.unlock() }
        func read() -> TimeInterval { lock.lock(); defer { lock.unlock() }; return value }
    }

    func testReturningForegroundCannotFreshenFrozenBackgroundEvidence() throws {
        let journal = NearbyTrialJournal(), phone = NSObject()
        let producer = ObjectIdentifier(phone)
        journal.begin(producer: producer)
        journal.receive(hasDistance: true, producer: producer, at: 9)
        XCTAssertTrue(journal.arm(.appSwitch))
        journal.background(at: 10)
        journal.receive(hasDistance: true, producer: producer, at: 10.5)
        journal.receive(hasDistance: true, producer: producer, at: 12)
        journal.foreground(at: 30)
        // Same session resumes and UI count may increase, but this report cannot.
        journal.receive(hasDistance: true, producer: producer, at: 30.1)
        journal.receive(hasDistance: true, producer: producer, at: 31)
        let report = try XCTUnwrap(journal.snapshot().reports.last)
        XCTAssertEqual(report.distanceCallbacks, 2)
        XCTAssertEqual(report.callbacksAfterOneSecond, 1)
        XCTAssertEqual(report.lastDistanceAgeAtEnd, 18)
        XCTAssertEqual(report.largestSilence, 18)
        XCTAssertEqual(report.duration, 20)
        XCTAssertEqual(report.endReason, .foreground)
    }

    func testActualNotificationFreezesBeforeResumedCallbacksAndDelayedUIWork() throws {
        let journal = NearbyTrialJournal(), center = NotificationCenter(), clock = Clock(), phone = NSObject()
        let producer = ObjectIdentifier(phone)
        let background = Notification.Name("Test.didEnterBackground"), foreground = Notification.Name("Test.willEnterForeground")
        let boundary = NearbyTrialLifecycle(journal: journal, center: center, background: background,
                                           foreground: foreground, clock: { clock.read() })
        defer { withExtendedLifetime(boundary) {} }
        journal.begin(producer: producer)
        XCTAssertTrue(journal.arm(.lock))
        clock.set(10)
        center.post(name: background, object: nil)
        journal.receive(hasDistance: true, producer: producer, at: 12)
        clock.set(40)
        center.post(name: foreground, object: nil)
        // A foreground measurement can arrive before SwiftUI's ScenePhase hop.
        journal.receive(hasDistance: true, producer: producer, at: 40.001)
        let delayedUIRead = { journal.snapshot() }
        let report = try XCTUnwrap(delayedUIRead().reports.last)
        XCTAssertEqual(report.kind, .lock)
        XCTAssertEqual(report.endedAt, 40)
        XCTAssertEqual(report.distanceCallbacks, 1)
        XCTAssertEqual(report.lastDistanceAgeAtEnd, 28)
        XCTAssertNil(delayedUIRead().current)
    }

    func testReceiptTimeRejectsPreBackgroundAndOutOfOrderCallbacks() throws {
        let journal = NearbyTrialJournal(), phone = NSObject()
        let producer = ObjectIdentifier(phone)
        journal.begin(producer: producer)
        journal.arm(.appSwitch)
        journal.background(at: 10)
        journal.receive(hasDistance: true, producer: producer, at: 9.9)
        journal.receive(hasDistance: true, producer: producer, at: 12)
        journal.receive(hasDistance: true, producer: producer, at: 11)
        journal.receive(hasDistance: true, producer: producer, at: .nan)
        journal.foreground(at: 15)
        // A receipt that loses the lock to foreground freezing is conservatively
        // omitted rather than retroactively rewriting completed evidence.
        journal.receive(hasDistance: true, producer: producer, at: 14)
        let report = try XCTUnwrap(journal.snapshot().reports.last)
        XCTAssertEqual(report.distanceCallbacks, 1)
        XCTAssertEqual(report.lastDistanceAt, 12)
        XCTAssertEqual(report.largestSilence, 3)
    }

    func testStopRetainsReportAndOldCallbacksCannotModifyANewTrial() throws {
        let journal = NearbyTrialJournal(), oldPhone = NSObject(), newPhone = NSObject()
        let old = ObjectIdentifier(oldPhone), new = ObjectIdentifier(newPhone)
        journal.begin(producer: old)
        journal.arm(.appSwitch)
        journal.background(at: 10)
        journal.receive(hasDistance: true, producer: old, at: 11)
        journal.finish(at: 12, reason: .stopped)
        journal.resume(producer: old)
        journal.receive(hasDistance: true, producer: old, at: 13)
        journal.foreground(at: 20)
        let stopped = try XCTUnwrap(journal.snapshot().reports.last)
        XCTAssertEqual(stopped.endReason, .stopped)
        XCTAssertEqual(stopped.endedAt, 12)
        XCTAssertEqual(stopped.distanceCallbacks, 1)

        journal.begin(producer: new)
        XCTAssertTrue(journal.snapshot().reports.isEmpty)
        journal.arm(.lock)
        journal.background(at: 30)
        journal.receive(hasDistance: true, producer: old, at: 31)
        journal.finish(producer: old, at: 32, reason: .invalidated)
        journal.suspend(producer: old)
        journal.resume(producer: old)
        journal.receive(hasDistance: true, producer: new, at: 33)
        journal.foreground(at: 35)
        let report = try XCTUnwrap(journal.snapshot().reports.last)
        XCTAssertEqual(report.kind, .lock)
        XCTAssertEqual(report.distanceCallbacks, 1)
        XCTAssertEqual(report.suspensions, 0)
        XCTAssertEqual(report.endReason, .foreground)
    }

    func testArmingIsExplicitAndLifecycleDuplicatesDoNotResetEvidence() throws {
        let journal = NearbyTrialJournal(), phone = NSObject()
        let producer = ObjectIdentifier(phone)
        XCTAssertFalse(journal.arm(.lock))
        journal.begin(producer: producer, receiving: false)
        XCTAssertFalse(journal.arm(.lock))
        journal.resume(producer: producer)
        XCTAssertTrue(journal.arm(.appSwitch))
        journal.background(at: 10)
        XCTAssertFalse(journal.arm(.lock))
        journal.receive(hasDistance: true, producer: producer, at: 11)
        journal.background(at: 12)
        journal.foreground(at: 15)
        journal.foreground(at: 16)
        journal.background(at: 20) // not re-armed
        journal.receive(hasDistance: true, producer: producer, at: 22)
        journal.foreground(at: 25)
        XCTAssertEqual(journal.snapshot().reports.count, 1)
        let report = try XCTUnwrap(journal.snapshot().reports.last)
        XCTAssertEqual(report.startedAt, 10)
        XCTAssertEqual(report.endedAt, 15)
        XCTAssertEqual(report.distanceCallbacks, 1)
        journal.arm(.lock)
        journal.background(at: 30)
        journal.foreground(at: 40)
        XCTAssertEqual(journal.snapshot().reports.map(\.kind), [.appSwitch, .lock])
    }

    func testUnavailableDistanceAndSuspensionDoNotShortenSilence() throws {
        let journal = NearbyTrialJournal(), phone = NSObject()
        let producer = ObjectIdentifier(phone)
        journal.begin(producer: producer)
        journal.arm(.appSwitch)
        journal.background(at: 10)
        journal.receive(hasDistance: false, producer: producer, at: 11)
        journal.receive(hasDistance: true, producer: producer, at: 14)
        journal.suspend(producer: producer, at: 14.5)
        journal.suspend(producer: producer, at: 14.6)
        journal.receive(hasDistance: true, producer: producer, at: 15)
        journal.resume(producer: producer, at: 16)
        journal.resume(producer: producer, at: 16.1)
        journal.receive(hasDistance: false, producer: producer, at: 20)
        journal.receive(hasDistance: true, producer: producer, at: 24)
        journal.foreground(at: 26)
        let report = try XCTUnwrap(journal.snapshot().reports.last)
        XCTAssertEqual(report.distanceCallbacks, 2)
        XCTAssertEqual(report.unavailableCallbacks, 2)
        XCTAssertEqual(report.suspensions, 1)
        XCTAssertEqual(report.resumptions, 1)
        XCTAssertEqual(report.largestSilence, 10)
        XCTAssertEqual(report.lastDistanceAgeAtEnd, 2)
    }

    func testNoBackgroundDistancesPreservesFullSilenceAndNoMadeUpAge() throws {
        let journal = NearbyTrialJournal(), phone = NSObject()
        let producer = ObjectIdentifier(phone)
        journal.begin(producer: producer)
        journal.receive(hasDistance: true, producer: producer, at: 9)
        journal.arm(.lock)
        journal.background(at: 10)
        journal.receive(hasDistance: false, producer: producer, at: 15)
        journal.foreground(at: 40)
        let report = try XCTUnwrap(journal.snapshot().reports.last)
        XCTAssertEqual(report.distanceCallbacks, 0)
        XCTAssertEqual(report.unavailableCallbacks, 1)
        XCTAssertNil(report.lastDistanceAgeAtEnd)
        XCTAssertEqual(report.largestSilence, 30)
    }

    func testFrameworkFailureFreezesAtReceiptBeforeLaterForegroundOrTeardown() throws {
        let journal = NearbyTrialJournal(), phone = NSObject()
        let producer = ObjectIdentifier(phone)
        journal.begin(producer: producer)
        journal.arm(.appSwitch)
        journal.background(at: 10)
        journal.receive(hasDistance: true, producer: producer, at: 11)
        journal.finish(producer: producer, at: 12, reason: .invalidated)
        journal.receive(hasDistance: true, producer: producer, at: 13)
        journal.foreground(at: 30)
        journal.finish(at: 31, reason: .failed)
        let report = try XCTUnwrap(journal.snapshot().reports.last)
        XCTAssertEqual(journal.snapshot().reports.count, 1)
        XCTAssertEqual(report.endReason, .invalidated)
        XCTAssertEqual(report.duration, 2)
        XCTAssertEqual(report.lastDistanceAgeAtEnd, 1)
    }

    func testResetDuringBackgroundDoesNotInventForegroundOrRearm() {
        let journal = NearbyTrialJournal(), first = NSObject(), second = NSObject()
        journal.begin(producer: ObjectIdentifier(first))
        journal.arm(.lock)
        journal.background(at: 10)
        journal.begin(producer: ObjectIdentifier(second))
        XCTAssertNil(journal.snapshot().current)
        XCTAssertNil(journal.snapshot().armed)
        XCTAssertFalse(journal.arm(.appSwitch))
        journal.foreground(at: 20)
        XCTAssertTrue(journal.snapshot().reports.isEmpty)
        XCTAssertTrue(journal.arm(.appSwitch))
    }

    func testBoundaryTimestampExcludesCallbacksThatWinTheLockAfterReturn() throws {
        let journal = NearbyTrialJournal(), phone = NSObject()
        let producer = ObjectIdentifier(phone)
        journal.begin(producer: producer)
        journal.arm(.appSwitch)
        journal.background(at: 10)
        journal.receive(hasDistance: true, producer: producer, at: 12)
        // Notification captured t=30, was descheduled, and callbacks at/after
        // that boundary reached the journal before the notification got its lock.
        journal.receive(hasDistance: true, producer: producer, at: 30)
        journal.receive(hasDistance: true, producer: producer, at: 30.1)
        journal.suspend(producer: producer, at: 30.2)
        journal.foreground(at: 30)
        let report = try XCTUnwrap(journal.snapshot().reports.last)
        XCTAssertEqual(report.endedAt, 30)
        XCTAssertEqual(report.distanceCallbacks, 1)
        XCTAssertEqual(report.callbacksAfterOneSecond, 1)
        XCTAssertEqual(report.lastDistanceAgeAtEnd, 18)
        XCTAssertEqual(report.largestSilence, 18)
        XCTAssertEqual(report.suspensions, 0)
    }

    func testForegroundCutoffWinsOverNewerTerminationThatAcquiredLockFirst() throws {
        let journal = NearbyTrialJournal(), phone = NSObject()
        let producer = ObjectIdentifier(phone)
        journal.begin(producer: producer)
        journal.arm(.lock)
        journal.background(at: 10)
        journal.receive(hasDistance: true, producer: producer, at: 11)
        journal.receive(hasDistance: true, producer: producer, at: 30.1)
        journal.finish(producer: producer, at: 30.2, reason: .invalidated)
        journal.foreground(at: 30)
        let report = try XCTUnwrap(journal.snapshot().reports.last)
        XCTAssertEqual(report.endReason, .foreground)
        XCTAssertEqual(report.distanceCallbacks, 1)
        XCTAssertEqual(report.lastDistanceAgeAtEnd, 19)
        journal.foreground(at: 31)
        XCTAssertEqual(journal.snapshot().reports.last?.endedAt, 30)
    }

    func testOlderLifecycleBoundaryCannotEndLaterTrial() throws {
        let journal = NearbyTrialJournal(), phone = NSObject()
        let producer = ObjectIdentifier(phone)
        journal.begin(producer: producer)
        journal.arm(.appSwitch)
        journal.background(at: 10)
        journal.foreground(at: 20)
        journal.arm(.lock)
        journal.background(at: 30)
        journal.foreground(at: 25)
        XCTAssertNotNil(journal.snapshot().current)
        journal.receive(hasDistance: true, producer: producer, at: 32)
        journal.foreground(at: 40)
        let report = try XCTUnwrap(journal.snapshot().reports.last)
        XCTAssertEqual(report.kind, .lock)
        XCTAssertEqual(report.duration, 10)
        XCTAssertEqual(report.distanceCallbacks, 1)
    }
}
