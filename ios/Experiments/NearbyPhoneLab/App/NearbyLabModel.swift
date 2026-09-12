import ActivityKit
import Combine
import MultipeerConnectivity
import NearbyInteraction
import SwiftUI
import UIKit

/// All lifecycle decisions are serialized on the main actor. Delegate callbacks
/// must match the current framework object before touching the session lease.
@MainActor
final class NearbyLabModel: NSObject, ObservableObject {
    struct Invitation: Identifiable {
        let id = UUID()
        let peer: MCPeerID
        let reply: (Bool, MCSession?) -> Void
    }
    private struct TokenEnvelope: Codable {
        let version: Int
        let token: Data
    }

    @Published private(set) var state = LabSessionState()
    @Published private(set) var peers: [MCPeerID] = []
    @Published private(set) var localName = ""
    @Published private(set) var partner = "Not paired"
    @Published private(set) var status = "Pair two consenting testers in the foreground."
    @Published private(set) var transportConnected = false
    @Published private(set) var backgroundEnabled = false
    @Published private(set) var events: [String] = []
    @Published var invitation: Invitation?

    let preciseRanging = NISession.deviceCapabilities.supportsPreciseDistanceMeasurement
    let directionSupported = NISession.deviceCapabilities.supportsDirectionMeasurement
    let extendedDistanceSupported = NISession.deviceCapabilities.supportsExtendedDistanceMeasurement
    private let service = "nobonk-peer"
    private var transport: MCSession?
    private var advertiser: MCNearbyServiceAdvertiser?
    private var browser: MCNearbyServiceBrowser?
    private var nearby: NISession?
    private var expectedPeer: MCPeerID?
    private var remoteToken: NIDiscoveryToken?
    private var configuration: NINearbyPeerConfiguration?
    private var sentToken = false
    private var measuredAt: Date?
    private var activity: Activity<NearbyPhoneActivity>?
    private var activityUpdates: Task<Void, Never>?
    private var activityObserver: Task<Void, Never>?
    private var stopObserver: NSObjectProtocol?
    private var lastActivityUpdate: TimeInterval = -.infinity
    nonisolated private let trialJournal = NearbyTrialJournal()
    private var trialLifecycle: NearbyTrialLifecycle?

    override init() {
        super.init()
        trialLifecycle = NearbyTrialLifecycle(journal: trialJournal,
            background: UIApplication.didEnterBackgroundNotification,
            foreground: UIApplication.willEnterForegroundNotification)
        stopObserver = NotificationCenter.default.addObserver(forName: .stopNearbyPhoneLab, object: nil, queue: .main) { [weak self] _ in
            // Notification is posted synchronously by the LiveActivityIntent on MainActor.
            MainActor.assumeIsolated { self?.stop() }
        }
        // An app process restart must never revive an old ranging session.
        let leftovers = Activity<NearbyPhoneActivity>.activities
        Task {
            for item in leftovers { await item.end(nil, dismissalPolicy: .immediate) }
        }
    }

    var active: Bool { [.pairing, .ranging, .suspended].contains(state.phase) }
    var canEnableBackground: Bool {
        state.freshDistance(at: ProcessInfo.processInfo.systemUptime) != nil && !backgroundEnabled
    }
    var canArmTrial: Bool {
        backgroundEnabled && state.freshDistance(at: ProcessInfo.processInfo.systemUptime) != nil
    }
    var trialSnapshot: NearbyTrialJournal.Snapshot { trialJournal.snapshot() }

    func armTrial(_ kind: NearbyTrialJournal.Kind) {
        guard UIApplication.shared.applicationState == .active, canArmTrial,
              trialJournal.arm(kind) else { return }
        record("Armed next background interval: \(kind.rawValue) (tester-selected label)")
    }

    func begin(hosting: Bool) {
        stop(recordEvent: false)
        guard preciseRanging else {
            status = "This device cannot perform precise Nearby Interaction ranging. Use a supported physical iPhone."
            return
        }
        state.begin()
        partner = "Not paired"
        localName = "Phone-" + UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(6).uppercased()
        let identity = MCPeerID(displayName: localName)
        let transport = MCSession(peer: identity, securityIdentity: nil, encryptionPreference: .required)
        transport.delegate = self
        self.transport = transport
        let nearby = NISession()
        nearby.delegate = self
        self.nearby = nearby
        trialJournal.begin(producer: ObjectIdentifier(nearby), receiving: false)
        if hosting {
            let advertiser = MCNearbyServiceAdvertiser(peer: identity, discoveryInfo: ["v": "1"], serviceType: service)
            advertiser.delegate = self
            self.advertiser = advertiser
            advertiser.startAdvertisingPeer()
            status = "Show your temporary phone name to your partner. Approve only their matching name."
        } else {
            let browser = MCNearbyServiceBrowser(peer: identity, serviceType: service)
            browser.delegate = self
            self.browser = browser
            browser.startBrowsingForPeers()
            status = "Find your partner's temporary name below. Both testers must approve pairing."
        }
        record(hosting ? "Waiting for an invitation" : "Looking for nearby test phones")
    }

    func invite(_ peer: MCPeerID) {
        guard state.phase == .pairing, expectedPeer == nil,
              peers.contains(peer), let transport, let browser,
              UIApplication.shared.applicationState == .active else { return }
        expectedPeer = peer
        partner = peer.displayName
        browser.invitePeer(peer, to: transport, withContext: Data("nearby-phone-lab-v1".utf8), timeout: 30)
        status = "Waiting for \(partner) to approve. Cancel with Stop."
        record("Invitation sent after local confirmation")
    }

    func respondToInvitation(accept: Bool) {
        guard let pending = invitation else { return }
        invitation = nil
        guard accept, state.phase == .pairing, expectedPeer == nil, let transport,
              UIApplication.shared.applicationState == .active else {
            pending.reply(false, nil)
            return
        }
        expectedPeer = pending.peer
        partner = pending.peer.displayName
        pending.reply(true, transport)
        status = "Connecting to \(partner)…"
        record("Invitation approved by this tester")
    }

    func enableBackground() {
        guard UIApplication.shared.applicationState == .active, canEnableBackground else { return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            status = "Allow Live Activities for Nearby Phone Lab in Settings, then try again."
            return
        }
        do {
            let activity = try Activity.request(attributes: NearbyPhoneActivity(partner: partner), content: activityContent(), pushType: nil)
            self.activity = activity
            backgroundEnabled = true
            record("Live Activity started in foreground")
            status = "Both testers can now enable this and switch apps. Watch for fresh readings, not just a visible activity."
            let lease = state.generation
            activityObserver = Task { [weak self, weak activity] in
                guard let activity else { return }
                for await phase in activity.activityStateUpdates {
                    guard let self, self.state.generation == lease, self.activity?.id == activity.id else { return }
                    if phase == .dismissed || phase == .ended {
                        self.stop()
                        self.status = "Live Activity ended. This experiment has stopped; pair again to restart."
                        return
                    }
                }
            }
        } catch {
            status = "Could not start a Live Activity: \(error.localizedDescription)"
        }
    }

    func sceneChanged(_ phase: ScenePhase) {
        guard active else { return }
        if phase == .background {
            respondToInvitation(accept: false)
            record(backgroundEnabled ? "App entered background with Live Activity" : "App entered background without Live Activity")
        } else if phase == .active {
            record("App returned to foreground")
        }
    }

    func stop(recordEvent: Bool = true) {
        // Invalidate the lease BEFORE framework calls: queued callbacks cannot revive it.
        trialJournal.finish(at: ProcessInfo.processInfo.systemUptime, reason: .stopped)
        state.stop()
        tearDown()
        status = "Stopped. Both testers must pair again to restart."
        if recordEvent { record("Stopped locally; all sessions released") }
    }

    private func fail(_ message: String) {
        trialJournal.finish(at: ProcessInfo.processInfo.systemUptime, reason: .failed)
        state.fail()
        tearDown()
        status = message
        record(message)
    }

    private func tearDown() {
        let pending = invitation
        invitation = nil
        pending?.reply(false, nil)
        let oldNearby = nearby
        nearby = nil
        oldNearby?.invalidate()
        let oldTransport = transport
        transport = nil
        oldTransport?.disconnect()
        advertiser?.stopAdvertisingPeer()
        advertiser = nil
        browser?.stopBrowsingForPeers()
        browser = nil
        expectedPeer = nil
        remoteToken = nil
        configuration = nil
        sentToken = false
        peers = []
        transportConnected = false
        measuredAt = nil
        backgroundEnabled = false
        activityObserver?.cancel()
        activityObserver = nil
        let oldActivity = activity
        activity = nil
        let priorUpdate = activityUpdates
        // Any update already in flight completes before the final end operation.
        activityUpdates = Task {
            await priorUpdate?.value
            await oldActivity?.end(nil, dismissalPolicy: .immediate)
        }
        lastActivityUpdate = -.infinity
    }

    private func sendToken(on session: MCSession, to peer: MCPeerID) {
        guard !sentToken, let token = nearby?.discoveryToken else { return }
        do {
            let archive = try NSKeyedArchiver.archivedData(withRootObject: token, requiringSecureCoding: true)
            let data = try JSONEncoder().encode(TokenEnvelope(version: 1, token: archive))
            try session.send(data, toPeers: [peer], with: .reliable)
            sentToken = true
        } catch { fail("Could not exchange a ranging token. Pair again. \(error.localizedDescription)") }
    }

    private func receiveToken(_ data: Data, from peer: MCPeerID, on session: MCSession) {
        guard session === transport, peer == expectedPeer, state.phase == .pairing,
              remoteToken == nil, data.count <= 32_768, let nearby else { return }
        do {
            let message = try JSONDecoder().decode(TokenEnvelope.self, from: data)
            guard message.version == 1,
                  let token = try NSKeyedUnarchiver.unarchivedObject(ofClass: NIDiscoveryToken.self, from: message.token) else {
                fail("Invalid pairing token. Stop and confirm your partner before trying again.")
                return
            }
            remoteToken = token
            let config = NINearbyPeerConfiguration(peerToken: token)
            // A true value can create an ARSession implicitly. This baseline never uses a camera.
            config.isCameraAssistanceEnabled = false
            config.isExtendedDistanceMeasurementEnabled = false
            configuration = config
            state.startRanging(generation: state.generation)
            trialJournal.resume(producer: ObjectIdentifier(nearby))
            nearby.run(config)
            advertiser?.stopAdvertisingPeer()
            browser?.stopBrowsingForPeers()
            status = "Paired. Waiting for a real distance measurement from the other phone."
            record("Encrypted token exchange completed; ranging requested")
        } catch { fail("Could not decode the pairing token. Pair again. \(error.localizedDescription)") }
    }

    private func activityContent() -> ActivityContent<NearbyPhoneActivity.ContentState> {
        let distance = measuredAt == nil ? nil : state.freshDistance(at: ProcessInfo.processInfo.systemUptime)
        let content = NearbyPhoneActivity.ContentState(distance: distance, sampleCount: state.sampleCount,
            measuredAt: distance == nil ? nil : measuredAt, status: state.phase.rawValue)
        return ActivityContent(state: content, staleDate: distance == nil ? Date() : measuredAt?.addingTimeInterval(state.freshnessWindow))
    }

    private func updateActivity(force: Bool = false) {
        guard let activity else { return }
        let now = ProcessInfo.processInfo.systemUptime
        guard force || now - lastActivityUpdate >= 1 else { return }
        lastActivityUpdate = now
        let content = activityContent()
        let lease = state.generation
        let prior = activityUpdates
        activityUpdates = Task { [weak self] in
            await prior?.value
            guard let self, self.state.generation == lease, self.activity?.id == activity.id else { return }
            await activity.update(content)
        }
    }

    private func record(_ event: String) {
        let time = Date.now.formatted(date: .omitted, time: .standard)
        events.append("\(time) · \(event)")
        events = Array(events.suffix(16))
    }
}

extension NearbyLabModel: MCNearbyServiceAdvertiserDelegate {
    nonisolated func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer peerID: MCPeerID, withContext context: Data?, invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        Task { @MainActor in
            guard advertiser === self.advertiser, self.state.phase == .pairing,
                  self.expectedPeer == nil, self.invitation == nil,
                  context == Data("nearby-phone-lab-v1".utf8), UIApplication.shared.applicationState == .active else {
                invitationHandler(false, nil)
                return
            }
            self.invitation = Invitation(peer: peerID, reply: invitationHandler)
        }
    }
    nonisolated func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer error: Error) {
        Task { @MainActor in
            guard advertiser === self.advertiser else { return }
            self.fail("Discovery could not start. Check Local Network permission. \(error.localizedDescription)")
        }
    }
}

extension NearbyLabModel: MCNearbyServiceBrowserDelegate {
    nonisolated func browser(_ browser: MCNearbyServiceBrowser, foundPeer peerID: MCPeerID, withDiscoveryInfo info: [String: String]?) {
        Task { @MainActor in
            guard browser === self.browser, self.state.phase == .pairing, info?["v"] == "1", !self.peers.contains(peerID) else { return }
            self.peers.append(peerID)
        }
    }
    nonisolated func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
        Task { @MainActor in
            guard browser === self.browser else { return }
            self.peers.removeAll { $0 == peerID }
        }
    }
    nonisolated func browser(_ browser: MCNearbyServiceBrowser, didNotStartBrowsingForPeers error: Error) {
        Task { @MainActor in
            guard browser === self.browser else { return }
            self.fail("Discovery could not start. Check Local Network permission. \(error.localizedDescription)")
        }
    }
}

extension NearbyLabModel: MCSessionDelegate {
    nonisolated func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        Task { @MainActor in
            guard session === self.transport, peerID == self.expectedPeer else { return }
            self.transportConnected = state == .connected
            if state == .connected {
                self.record("Local pairing transport connected")
                self.sendToken(on: session, to: peerID)
            } else if state == .notConnected {
                // MPC disconnects in the background. It is NOT evidence that UWB ended.
                self.record("Pairing transport disconnected; UWB lifecycle remains independent")
                if self.state.phase == .pairing {
                    self.fail("Pairing ended before token exchange completed. Start again with both apps visible.")
                }
            }
        }
    }
    nonisolated func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        Task { @MainActor in self.receiveToken(data, from: peerID, on: session) }
    }
    nonisolated func session(_ session: MCSession, didReceive stream: InputStream, withName streamName: String, fromPeer peerID: MCPeerID) { stream.close() }
    nonisolated func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, with progress: Progress) { progress.cancel() }
    nonisolated func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}

extension NearbyLabModel: NISessionDelegate {
    nonisolated func session(_ session: NISession, didUpdate nearbyObjects: [NINearbyObject]) {
        // Capture callback receipt time before hopping actors, so a delayed main
        // queue cannot turn an old callback into an apparently fresh sample.
        let receivedAt = ProcessInfo.processInfo.systemUptime
        let wallDate = Date()
        // This session runs exactly one peer configuration. Count receipt of a
        // finite distance synchronously; delayed UI work cannot rewrite reports.
        let hasDistance = nearbyObjects.contains { object in
            guard let distance = object.distance else { return false }
            return distance.isFinite && distance >= 0
        }
        trialJournal.receive(hasDistance: hasDistance, producer: ObjectIdentifier(session), at: receivedAt)
        Task { @MainActor in
            guard session === self.nearby, self.state.phase == .ranging,
                  let object = nearbyObjects.first(where: { $0.discoveryToken == self.remoteToken }) else { return }
            let valid = self.state.receive(distance: object.distance, at: receivedAt, generation: self.state.generation)
            if valid { self.measuredAt = wallDate }
            else if self.state.distance == nil { self.measuredAt = nil }
            let fresh = self.state.freshDistance(at: ProcessInfo.processInfo.systemUptime) != nil
            self.status = fresh ? "Distance to your paired phone. No obstacle detection." : "Distance unavailable. Keep the phones unobstructed; never rely on this for safety."
            self.updateActivity(force: !valid)
        }
    }
    nonisolated func session(_ session: NISession, didRemove nearbyObjects: [NINearbyObject], reason: NINearbyObject.RemovalReason) {
        if !nearbyObjects.isEmpty {
            trialJournal.finish(producer: ObjectIdentifier(session), at: ProcessInfo.processInfo.systemUptime, reason: .peerRemoved)
        }
        Task { @MainActor in
            guard session === self.nearby, nearbyObjects.contains(where: { $0.discoveryToken == self.remoteToken }) else { return }
            // Deliberately stop on timeout rather than spin retries in a suspended app.
            self.fail(reason == .peerEnded ? "The paired phone ended its session. Pair again to restart." : "Ranging timed out. Bring both apps to the foreground and pair again.")
        }
    }
    nonisolated func sessionWasSuspended(_ session: NISession) {
        trialJournal.suspend(producer: ObjectIdentifier(session))
        Task { @MainActor in
            guard session === self.nearby else { return }
            self.state.suspend(generation: self.state.generation)
            self.measuredAt = nil
            self.status = "iOS suspended ranging. Distance is unavailable."
            self.record("Nearby Interaction suspended")
            self.updateActivity(force: true)
        }
    }
    nonisolated func sessionSuspensionEnded(_ session: NISession) {
        Task { @MainActor in
            guard session === self.nearby, self.state.phase == .suspended, let config = self.configuration else { return }
            self.state.startRanging(generation: self.state.generation)
            self.trialJournal.resume(producer: ObjectIdentifier(session))
            session.run(config)
            self.record("iOS ended suspension; existing consenting session resumed")
        }
    }
    nonisolated func session(_ session: NISession, didInvalidateWith error: Error) {
        trialJournal.finish(producer: ObjectIdentifier(session), at: ProcessInfo.processInfo.systemUptime, reason: .invalidated)
        Task { @MainActor in
            guard session === self.nearby else { return }
            self.fail("Nearby Interaction ended: \(error.localizedDescription). Check Nearby Interaction permission if needed, then pair again.")
        }
    }
}
