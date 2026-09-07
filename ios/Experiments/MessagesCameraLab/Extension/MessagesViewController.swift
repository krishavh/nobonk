import AVFoundation
import Messages
import UIKit

@MainActor
final class MessagesViewController: MSMessagesAppViewController {
    private let journal = MessageCameraJournal()
    private lazy var capture = MessageCameraCapture(journal: journal) { [weak self] handle in
        Task { @MainActor [weak self] in
            guard let self, self.journal.accepts(handle.token), self.view.window != nil else { return }
            self.preview.previewLayer.session = handle.session
        }
    }
    private var boundary: MessageCameraBoundary?
    private var timer: Timer?
    private var messagesActive = false
    private var expanded = false
    private let preview = MessageCameraPreviewView()
    private let status = UILabel()
    private let notice = UILabel()
    private let details = UILabel()
    private let diagnostics = UILabel()
    private let acknowledgeButton = UIButton(type: .system)
    private let startButton = UIButton(type: .system)
    private let stopButton = UIButton(type: .system)
    private let sizeButton = UIButton(type: .system)
    private let resetButton = UIButton(type: .system)
    private var previewHeight: NSLayoutConstraint!

    override func viewDidLoad() {
        super.viewDidLoad()
        setupInterface()
        let capture = capture
        boundary = MessageCameraBoundary(journal: journal,
            stopNames: [.NSExtensionHostWillResignActive, .NSExtensionHostDidEnterBackground],
            resumeName: .NSExtensionHostDidBecomeActive,
            stopped: { [weak self] token in
                capture.tearDown(token)
                Task { @MainActor [weak self] in self?.render() }
            }, resumed: { [weak self] in
                Task { @MainActor [weak self] in self?.activateIfVisible() }
            })
    }
    override func didBecomeActive(with conversation: MSConversation) {
        super.didBecomeActive(with: conversation)
        // The framework passes a conversation; this experiment never reads it.
        messagesActive = true
        activateIfVisible()
    }
    override func willResignActive(with conversation: MSConversation) {
        let receipt = ProcessInfo.processInfo.systemUptime
        messagesActive = false
        endActivation(at: receipt, reason: "Messages extension dismissed — camera stopped")
        super.willResignActive(with: conversation)
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        activateIfVisible()
        if timer == nil {
            let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in self?.render() }
            }
            self.timer = timer
            RunLoop.main.add(timer, forMode: .common)
        }
    }
    override func viewWillDisappear(_ animated: Bool) {
        let receipt = ProcessInfo.processInfo.systemUptime
        endActivation(at: receipt, reason: "Camera panel no longer visible — stopped")
        timer?.invalidate(); timer = nil
        super.viewWillDisappear(animated)
    }
    override func willTransition(to presentationStyle: MSMessagesAppPresentationStyle) {
        // Conservative prototype: changing panel size needs another explicit Start.
        stopCamera(reason: "Panel changed — tap Start to continue")
        super.willTransition(to: presentationStyle)
    }
    override func didTransition(to presentationStyle: MSMessagesAppPresentationStyle) {
        super.didTransition(to: presentationStyle)
        expanded = presentationStyle == .expanded
        activateIfVisible(); render()
    }
    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        stopCamera(reason: "Layout changed — use portrait and tap Start again")
        super.viewWillTransition(to: size, with: coordinator)
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        enforceVisibleCameraRegion()
    }
    private func activateIfVisible() {
        guard isViewLoaded, view.window != nil, messagesActive, presentationStyle != .transcript else { return }
        expanded = presentationStyle == .expanded
        _ = journal.activate(at: ProcessInfo.processInfo.systemUptime)
        render()
    }
    private func endActivation(at now: TimeInterval, reason: String) {
        let token = journal.deactivate(at: now, reason: reason)
        preview.previewLayer.session = nil
        capture.tearDown(token)
        render()
    }
    private func stopCamera(reason: String) {
        let token = journal.stop(at: ProcessInfo.processInfo.systemUptime, reason: reason)
        preview.previewLayer.session = nil
        capture.tearDown(token)
        render()
    }
    @objc private func acknowledge() {
        journal.acknowledge(activation: journal.snapshot().activation)
        render()
    }
    @objc private func start() {
        guard cameraRegionIsVisible() else {
            stopCamera(reason: "Expand the panel to show the camera and Stop button")
            return
        }
        guard view.window?.windowScene?.interfaceOrientation == .portrait else {
            stopCamera(reason: "This experiment requires portrait orientation")
            return
        }
        guard view.window != nil, messagesActive,
              let token = journal.begin(at: ProcessInfo.processInfo.systemUptime) else { return }
        capture.start(token); render()
    }
    @objc private func stop() { stopCamera(reason: "Stopped — tap Start when ready") }
    @objc private func resizePanel() { requestPresentationStyle(expanded ? .compact : .expanded) }
    @objc private func reset() {
        let token = journal.snapshot().current?.token
        journal.reset(at: ProcessInfo.processInfo.systemUptime)
        preview.previewLayer.session = nil; capture.tearDown(token); render()
    }
    private func setupInterface() {
        view.backgroundColor = UIColor(red: 0.035, green: 0.06, blue: 0.085, alpha: 1)
        view.tintColor = .systemMint
        let stack = UIStackView()
        stack.axis = .vertical; stack.spacing = 8; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        let scroll = UIScrollView()
        let detailsStack = UIStackView()
        detailsStack.axis = .vertical; detailsStack.spacing = 12; detailsStack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(detailsStack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -14),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -8),
            detailsStack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            detailsStack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            detailsStack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            detailsStack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            detailsStack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor)
        ])
        let brand = UIImageView(image: UIImage(named: "BrandIcon"))
        brand.contentMode = .scaleAspectFit
        NSLayoutConstraint.activate([brand.widthAnchor.constraint(equalToConstant: 32), brand.heightAnchor.constraint(equalToConstant: 32)])
        let title = UILabel(); title.text = "NoBonk · Messages Lab"
        title.font = .preferredFont(forTextStyle: .headline); title.textColor = .white; title.adjustsFontForContentSizeCategory = true
        title.numberOfLines = 0
        configure(sizeButton, title: "Details", action: #selector(resizePanel))
        let header = UIStackView(arrangedSubviews: [brand, title, sizeButton]); header.spacing = 8; header.alignment = .center
        stack.addArrangedSubview(header)
        notice.text = "People only. Can miss people and obstacles. Stay aware — this is not navigation or a safety system."
        style(notice, font: .subheadline); notice.textColor = .systemYellow
        detailsStack.addArrangedSubview(notice)
        configure(acknowledgeButton, title: "I understand — enable Start", action: #selector(acknowledge))
        detailsStack.addArrangedSubview(acknowledgeButton)
        previewHeight = preview.heightAnchor.constraint(equalToConstant: 100)
        previewHeight.priority = .defaultHigh; previewHeight.isActive = true
        let minimumPreview = preview.heightAnchor.constraint(greaterThanOrEqualToConstant: 48)
        minimumPreview.priority = UILayoutPriority(999); minimumPreview.isActive = true
        preview.layer.cornerRadius = 14; preview.clipsToBounds = true
        stack.addArrangedSubview(preview)
        style(status, font: .subheadline); stack.addArrangedSubview(status)
        configure(startButton, title: "Start camera", action: #selector(start))
        configure(stopButton, title: "Stop", action: #selector(stop))
        stopButton.configuration?.baseBackgroundColor = .systemRed
        let buttons = UIStackView(arrangedSubviews: [startButton, stopButton]); buttons.spacing = 10; buttons.distribution = .fillEqually
        stack.addArrangedSubview(buttons)
        // Only the notice/details scroll. The preview, freshness and Stop remain
        // pinned; clipping during host resize is independently gated below.
        stack.addArrangedSubview(scroll)
        style(details, font: .body)
        details.text = "This research panel tests a visible, local People scan below a Messages conversation. It does not detect walls, traffic or all hazards, measure safe distance, or prevent collisions. No audio or vibration alerts are provided.\n\nCompact mode replaces the keyboard. Reading above the panel may work on this device; simultaneous typing and other apps are not supported claims. Dismissing, resizing, locking, leaving Messages or an interruption stops scanning. Reopening requires acknowledgment and Start.\n\nCamera frames are used in memory by Apple Vision; no frames are saved. The extension never reads, inserts or sends conversation content and has no network code. Diagnostics below contain only monotonic times and counts, kept in memory. Stop and test while stationary with another person helping."
        detailsStack.addArrangedSubview(details)
        style(diagnostics, font: .caption1); diagnostics.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        detailsStack.addArrangedSubview(diagnostics)
        configure(resetButton, title: "Clear trial diagnostics", action: #selector(reset)); detailsStack.addArrangedSubview(resetButton)
        render()
    }
    private func style(_ label: UILabel, font: UIFont.TextStyle) {
        label.font = .preferredFont(forTextStyle: font); label.adjustsFontForContentSizeCategory = true
        label.numberOfLines = 0; label.textColor = .white
    }
    private func configure(_ button: UIButton, title: String, action: Selector) {
        var configuration = UIButton.Configuration.filled()
        configuration.title = title; configuration.cornerStyle = .medium
        configuration.baseBackgroundColor = .systemTeal; configuration.baseForegroundColor = .black
        button.configuration = configuration
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        button.addTarget(self, action: action, for: .touchUpInside)
    }
    private func render() {
        guard isViewLoaded, previewHeight != nil else { return }
        let now = ProcessInfo.processInfo.systemUptime
        let snapshot = journal.snapshot()
        if snapshot.current != nil, !cameraRegionIsVisible() {
            stopCamera(reason: "Camera panel or Stop was clipped — expand and start again")
            return
        }
        acknowledgeButton.isHidden = snapshot.acknowledged
        acknowledgeButton.isEnabled = snapshot.active
        startButton.isEnabled = snapshot.active && snapshot.acknowledged && snapshot.current == nil
        stopButton.isEnabled = snapshot.current != nil
        details.isHidden = !expanded; diagnostics.isHidden = !expanded; resetButton.isHidden = !expanded
        sizeButton.configuration?.title = expanded ? "Compact" : (snapshot.acknowledged ? "Details" : "Read notice")
        startButton.configuration?.title = snapshot.acknowledged ? "Start camera" : "Read notice first"
        previewHeight.constant = expanded ? 240 : 100
        let fresh = snapshot.frameIsFresh(at: now)
        preview.cover.isHidden = fresh
        if snapshot.current == nil { preview.previewLayer.session = nil }
        preview.cover.text = snapshot.current == nil ? "CAMERA OFF" : "WAITING FOR FRESH FRAMES"
        if fresh, let trial = snapshot.current {
            let age = now - (trial.lastFrame ?? now)
            let people = snapshot.inferenceIsFresh(at: now) ? "People detected: \(trial.people ?? 0)" : "People result pending or stale"
            status.text = "Frame age \(String(format: "%.1f", age))s · \(people)\n\(trial.frames) frames · \(trial.inferences) scans · no safety guarantee"
        } else if let trial = snapshot.current, trial.frames > 0 {
            status.text = "Camera frames stale — no current scan evidence. Stop, then start again."
        } else { status.text = snapshot.phase + (snapshot.current != nil ? "\nNo fresh camera evidence" : "") }
        let rows = snapshot.completed.reversed().map { trial in
            let last = trial.lastFrame.map { String(format: "%.3f", $0 - trial.began) + "s" } ?? "none"
            return "Activation \(trial.token.activation), run \(trial.token.run): \(trial.frames) frames / \(trial.inferences) scans\nLast receipt +\(last); ended +\(String(format: "%.3f", (trial.ended ?? now) - trial.began))s\n\(trial.endReason ?? "")"
        }
        diagnostics.text = "Frozen trials (monotonic relative times; no images)\n" + (rows.isEmpty ? "No completed trials yet." : rows.joined(separator: "\n\n"))
    }
    private func enforceVisibleCameraRegion() {
        guard previewHeight != nil, journal.snapshot().current != nil, !cameraRegionIsVisible() else { return }
        stopCamera(reason: "Camera panel or Stop was clipped — expand and start again")
    }
    private func cameraRegionIsVisible() -> Bool {
        guard isViewLoaded, let window = view.window else { return false }
        var viewport = view.bounds.intersection(view.safeAreaLayoutGuide.layoutFrame)
        var ancestor: UIView? = view
        while let item = ancestor {
            guard !item.isHidden, item.alpha > 0.01 else { return false }
            if item.clipsToBounds || item === window {
                viewport = viewport.intersection(item.convert(item.bounds, to: view))
            }
            ancestor = item.superview
        }
        return MessageCameraVisibility.permitsScanning(preview: preview.convert(preview.bounds, to: view),
            stop: stopButton.convert(stopButton.bounds, to: view), viewport: viewport)
    }
}

@MainActor
private final class MessageCameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    let cover = UILabel()
    override init(frame: CGRect) {
        super.init(frame: frame)
        previewLayer.videoGravity = .resizeAspectFill
        cover.textAlignment = .center; cover.textColor = .secondaryLabel; cover.numberOfLines = 0
        cover.font = .preferredFont(forTextStyle: .caption1); cover.backgroundColor = .black
        cover.translatesAutoresizingMaskIntoConstraints = false; addSubview(cover)
        NSLayoutConstraint.activate([cover.leadingAnchor.constraint(equalTo: leadingAnchor), cover.trailingAnchor.constraint(equalTo: trailingAnchor),
                                     cover.topAnchor.constraint(equalTo: topAnchor), cover.bottomAnchor.constraint(equalTo: bottomAnchor)])
    }
    required init?(coder: NSCoder) { fatalError("Programmatic preview only") }
    override func layoutSubviews() {
        super.layoutSubviews()
        if let connection = previewLayer.connection, connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
    }
}
