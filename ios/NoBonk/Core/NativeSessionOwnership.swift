/// A throwing RHS assignment keeps the old object alive while creating its
/// replacement. Failed native graphs must instead be released first so fallback
/// does not hold both graphs at once. The caller serializes all session access.
enum NativeSessionOwnership {
    static func discardAndReplace<Session: AnyObject>(
        _ session: inout Session?, create: () throws -> Session
    ) rethrows {
        session = nil
        session = try create()
    }
}
