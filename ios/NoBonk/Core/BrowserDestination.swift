import Foundation

/// URLs are explicit user input. Never interpret an address as a search query or
/// hand redirects/custom schemes to other apps without the user's action.
enum BrowserDestination {
    static func url(from text: String) -> URL? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, !value.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              !value.contains(" ") else { return nil }
        let candidate = value.contains(":") ? value : "https://" + value
        guard let url = URL(string: candidate), allows(url) else { return nil }
        return url
    }
    static func allows(_ url: URL) -> Bool {
        url.scheme?.lowercased() == "https" && !(url.host ?? "").isEmpty && url.user == nil && url.password == nil
    }
}
