import SwiftUI
import WebKit

/// A user-opened web pane, not access to other native apps. Neither a WKWebView
/// nor a page is created until the user opens a valid website address.
@MainActor
final class BrowserModel: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    private(set) var webView: WKWebView?
    @Published var address = ""
    var editingAddress = false
    private var lastLocation: URL?
    private var requestedLocation: URL?
    @Published var hasPage = false
    @Published var loading = false
    @Published var canGoBack = false
    @Published var canGoForward = false
    @Published var message: String?
    var onCameraCovered: (() -> Void)?
    private var observations: [NSKeyValueObservation] = []
    private var playbackSuspended = true
    private let loadPage: @MainActor (WKWebView, URLRequest) -> Void

    /// The optional loader supplies local documents to hosted WebKit tests;
    /// production uses URLRequest loading with the same configuration/delegates.
    init(loadPage: @escaping @MainActor (WKWebView, URLRequest) -> Void = { view, request in view.load(request) }) {
        self.loadPage = loadPage
        super.init()
    }
    private func createWebViewIfNeeded() -> WKWebView {
        if let webView { return webView }
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        configuration.allowsPictureInPictureMediaPlayback = false
        let webView = WKWebView(frame: .zero, configuration: configuration)
        self.webView = webView
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.isOpaque = false
        webView.backgroundColor = .secondarySystemBackground
        webView.setAllMediaPlaybackSuspended(playbackSuspended, completionHandler: nil)
        observations = [
            webView.observe(\.isLoading) { [weak self] _, _ in Task { @MainActor in self?.refresh() } },
            webView.observe(\.canGoBack) { [weak self] _, _ in Task { @MainActor in self?.refresh() } },
            webView.observe(\.canGoForward) { [weak self] _, _ in Task { @MainActor in self?.refresh() } },
            webView.observe(\.url) { [weak self] _, _ in Task { @MainActor in self?.refresh() } },
            webView.observe(\.fullscreenState) { [weak self] view, _ in
                let covering = view.fullscreenState != .notInFullscreen
                Task { @MainActor [weak view] in
                    if covering, let self, self.hasPage, self.webView === view { self.onCameraCovered?() }
                }
            }
        ]
        return webView
    }
    private func refresh() {
        guard let webView else { return }
        loading = webView.isLoading; canGoBack = webView.canGoBack; canGoForward = webView.canGoForward
        if let url = webView.url, BrowserDestination.allows(url), url != lastLocation {
            lastLocation = url
            if !editingAddress { address = url.absoluteString }
        }
    }
    func open() {
        guard let url = BrowserDestination.url(from: address) else {
            message = "Enter a website address, such as example.org. Only secure HTTPS pages can open here."
            return
        }
        let webView = createWebViewIfNeeded()
        message = nil; hasPage = true; requestedLocation = url
        loadPage(webView, URLRequest(url: url))
    }
    func open(address: String) {
        self.address = address
        editingAddress = false
        open()
    }
    func pause(completion: (() -> Void)? = nil) {
        playbackSuspended = true
        guard let webView else { completion?(); return }
        webView.stopLoading()
        // Persistent suspension also pauses current playback. Its completion is the
        // boundary at which WebKit has applied the policy, not merely queued it.
        webView.setAllMediaPlaybackSuspended(true, completionHandler: completion)
    }
    func resume() {
        playbackSuspended = false
        webView?.setAllMediaPlaybackSuspended(false, completionHandler: nil)
    }
    func close() {
        // Stop this document without changing whether the pane itself is visible.
        // Otherwise closing a page while Browse is open silently mutes the next page.
        webView?.stopLoading()
        webView?.pauseAllMediaPlayback(completionHandler: nil)
        webView?.setAllMediaPlaybackSuspended(true, completionHandler: nil)
        // Closing a page ends its lifetime, including WebKit's history and observers.
        // Late callbacks from the old page cannot affect a newly opened page or camera.
        observations.removeAll()
        webView?.navigationDelegate = nil
        webView?.uiDelegate = nil
        webView = nil
        hasPage = false; address = ""; lastLocation = nil; requestedLocation = nil; message = nil
        loading = false; canGoBack = false; canGoForward = false
    }
    func reload() {
        guard hasPage, let webView else { return }
        message = nil
        if webView.url != nil { webView.reload() }
        else if let requestedLocation { loadPage(webView, URLRequest(url: requestedLocation)) }
    }
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        guard self.webView === webView, hasPage else { return }
        message = nil
        refresh()
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard self.webView === webView else { decisionHandler(.cancel); return }
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        if url.absoluteString == "about:blank", !hasPage { decisionHandler(.allow); return }
        guard BrowserDestination.allows(url) else {
            if action.targetFrame?.isMainFrame != false { message = "This link needs another app. Scanning works only while NoBonk stays open." }
            decisionHandler(.cancel); return
        }
        decisionHandler(.allow)
    }
    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        guard self.webView === webView, hasPage else { decisionHandler(.cancel); return }
        guard response.canShowMIMEType else { message = "Downloads are not supported in this preview."; decisionHandler(.cancel); return }
        decisionHandler(.allow)
    }
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard self.webView === webView, hasPage else { return nil }
        if action.targetFrame == nil, let url = action.request.url, BrowserDestination.allows(url) {
            webView.load(action.request)
        }
        return nil
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        guard self.webView === webView, hasPage else { return }
        report(error)
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        guard self.webView === webView, hasPage else { return }
        report(error)
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard self.webView === webView, hasPage else { return }
        onCameraCovered?(); message = "This page stopped. Reload it when you’re ready, then tap Start to resume scanning."
    }
    private func report(_ error: Error) {
        guard (error as NSError).code != NSURLErrorCancelled else { return }
        message = "The page couldn’t open. Check the address or your connection."
    }
    func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin, initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType, decisionHandler: @escaping (WKPermissionDecision) -> Void) { decisionHandler(.deny) }
    func webView(_ webView: WKWebView, requestDeviceOrientationAndMotionPermissionFor origin: WKSecurityOrigin, initiatedByFrame frame: WKFrameInfo, decisionHandler: @escaping (WKPermissionDecision) -> Void) { decisionHandler(.deny) }
}

private struct EmbeddedBrowser: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ view: WKWebView, context: Context) {}
}

struct BrowserPane: View {
    @ObservedObject var model: BrowserModel
    @FocusState private var editingAddress: Bool
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: "lock.fill").font(.caption).accessibilityHidden(true)
                TextField("Website address", text: $model.address)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .submitLabel(.go).focused($editingAddress)
                    .onSubmit { editingAddress = false; model.open() }
                    .accessibilityIdentifier("browse.address")
                    .onChange(of: editingAddress) { _, value in model.editingAddress = value }
                Button { editingAddress = false; model.open() } label: { Image(systemName: "arrow.right").frame(width: 44, height: 44) }
                    .accessibilityLabel("Open website")
            }.padding(.leading, 12).background(.white.opacity(0.07))
            if let message = model.message {
                Text(message).font(.caption).foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true).padding(10).frame(maxWidth: .infinity, alignment: .leading)
            }
            if model.hasPage, let webView = model.webView {
                EmbeddedBrowser(webView: webView)
                HStack(spacing: 8) {
                    Button { webView.goBack() } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }.disabled(!model.canGoBack).accessibilityLabel("Back")
                    Button { webView.goForward() } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }.disabled(!model.canGoForward).accessibilityLabel("Forward")
                    Spacer()
                    if model.loading { ProgressView().controlSize(.small) }
                    Button { if model.loading { webView.stopLoading() } else { model.reload() } } label: { Image(systemName: model.loading ? "xmark" : "arrow.clockwise").frame(width: 44, height: 44) }.accessibilityLabel(model.loading ? "Stop loading page" : "Reload page")
                    Button { model.close() } label: { Image(systemName: "trash").frame(width: 44, height: 44) }.accessibilityLabel("Close page")
                }.padding(.horizontal, 6).background(.white.opacity(0.04))
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Browse, with the scan in view.")
                            .font(.headline).foregroundStyle(.primary)
                        Text("Choose a website or enter an address above. Set up while standing still; keep looking up.")
                            .font(.subheadline)
                        VStack(spacing: 8) {
                            websiteButton("Instagram web", subtitle: "Open the website", symbol: "camera", address: "https://www.instagram.com/", identifier: "browse.instagram")
                            websiteButton("YouTube", subtitle: "Try an inline video", symbol: "play.rectangle", address: "https://www.youtube.com/", identifier: "browse.youtube")
                        }
                        Text("Website features and sign-in depend on the service. Native apps cannot appear here. Use Draft & scan to write for Messages or WhatsApp; switching apps pauses scanning.")
                            .font(.caption)
                        Text("Full-screen video also pauses scanning. Websites use the internet and their own privacy policies. Closing a page clears this pane’s temporary browsing session.")
                            .font(.caption)
                    }.foregroundStyle(.secondary).padding(16).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }.background(Color(white: 0.07), in: RoundedRectangle(cornerRadius: 18))
            .clipShape(RoundedRectangle(cornerRadius: 18))
    }

    private func websiteButton(_ title: String, subtitle: String, symbol: String, address: String, identifier: String) -> some View {
        Button {
            editingAddress = false
            model.open(address: address)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .font(.title3).foregroundStyle(.mint).frame(width: 28)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 4)
                Image(systemName: "arrow.up.right").font(.caption.weight(.semibold)).accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 12))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Open \(title) inside NoBonk")
        .accessibilityHint("Scanning can continue while the camera stays visible. The website may require sign-in.")
        .accessibilityIdentifier(identifier)
    }
}
