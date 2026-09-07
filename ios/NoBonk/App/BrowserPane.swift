import SwiftUI
import WebKit

/// A user-opened web pane, not access to other native apps. No page loads at launch.
@MainActor
final class BrowserModel: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    let webView: WKWebView
    @Published var address = ""
    var editingAddress = false
    private var lastLocation: URL?
    @Published var hasPage = false
    @Published var loading = false
    @Published var canGoBack = false
    @Published var canGoForward = false
    @Published var message: String?
    var onCameraCovered: (() -> Void)?
    private var observations: [NSKeyValueObservation] = []

    override init() {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        configuration.allowsPictureInPictureMediaPlayback = false
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.isOpaque = false
        webView.backgroundColor = .secondarySystemBackground
        webView.setAllMediaPlaybackSuspended(true, completionHandler: nil)
        observations = [
            webView.observe(\.isLoading) { [weak self] _, _ in Task { @MainActor in self?.refresh() } },
            webView.observe(\.canGoBack) { [weak self] _, _ in Task { @MainActor in self?.refresh() } },
            webView.observe(\.canGoForward) { [weak self] _, _ in Task { @MainActor in self?.refresh() } },
            webView.observe(\.url) { [weak self] _, _ in Task { @MainActor in self?.refresh() } },
            webView.observe(\.fullscreenState) { [weak self] view, _ in
                let covering = view.fullscreenState != .notInFullscreen
                Task { @MainActor in if covering { self?.onCameraCovered?() } }
            }
        ]
    }
    private func refresh() {
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
        message = nil; hasPage = true; webView.load(URLRequest(url: url))
    }
    func pause() {
        webView.stopLoading()
        webView.pauseAllMediaPlayback(completionHandler: nil)
        webView.setAllMediaPlaybackSuspended(true, completionHandler: nil)
    }
    func resume() {
        webView.setAllMediaPlaybackSuspended(false, completionHandler: nil)
    }
    func close() {
        pause(); hasPage = false; address = ""; lastLocation = nil; message = nil
        // Discard the visible document; website data uses a nonpersistent store.
        webView.loadHTMLString("", baseURL: nil)
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        if url.absoluteString == "about:blank", !hasPage { decisionHandler(.allow); return }
        guard BrowserDestination.allows(url) else {
            if action.targetFrame?.isMainFrame != false { message = "This link needs another app. Scanning works only while NoBonk stays open." }
            decisionHandler(.cancel); return
        }
        decisionHandler(.allow)
    }
    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        guard response.canShowMIMEType else { message = "Downloads are not supported in this preview."; decisionHandler(.cancel); return }
        decisionHandler(.allow)
    }
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if action.targetFrame == nil, let url = action.request.url, BrowserDestination.allows(url) {
            webView.load(action.request)
        }
        return nil
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { report(error) }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { report(error) }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
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
    let model: BrowserModel
    func makeUIView(context: Context) -> WKWebView { model.webView }
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
                Text(message).font(.caption).foregroundStyle(.orange).padding(10).frame(maxWidth: .infinity, alignment: .leading)
            }
            if model.hasPage {
                EmbeddedBrowser(model: model)
                HStack(spacing: 8) {
                    Button { model.webView.goBack() } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }.disabled(!model.canGoBack).accessibilityLabel("Back")
                    Button { model.webView.goForward() } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }.disabled(!model.canGoForward).accessibilityLabel("Forward")
                    Spacer()
                    if model.loading { ProgressView().controlSize(.small) }
                    Button { if model.loading { model.webView.stopLoading() } else { model.webView.reload() } } label: { Image(systemName: model.loading ? "xmark" : "arrow.clockwise").frame(width: 44, height: 44) }.accessibilityLabel(model.loading ? "Stop loading page" : "Reload page")
                    Button { model.close() } label: { Image(systemName: "trash").frame(width: 44, height: 44) }.accessibilityLabel("Close page")
                }.padding(.horizontal, 6).background(.white.opacity(0.04))
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        Image(systemName: "rectangle.split.1x2").font(.largeTitle).foregroundStyle(.mint)
                        Text("A little room for your world.").font(.title3.bold())
                        Text("Open a reading page or an inline video while the camera stays visible above. Set up while standing still, then keep looking up.")
                        Text("Write a message opens Apple’s composer and pauses scanning. NoBonk cannot read or show your Messages inbox. This pane opens websites inside NoBonk, not other iPhone apps. Some video sites require full screen; scanning pauses if the camera is covered.")
                        Text("Camera frames stay on your phone. Websites connect to the internet and follow their own privacy policies. Browsing data is not saved to disk by this pane.").font(.caption)
                    }.foregroundStyle(.secondary).padding(20).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }.background(Color(white: 0.07), in: RoundedRectangle(cornerRadius: 18))
            .clipShape(RoundedRectangle(cornerRadius: 18))
    }
}
