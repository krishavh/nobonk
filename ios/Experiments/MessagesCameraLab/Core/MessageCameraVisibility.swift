import Foundation
import CoreGraphics

enum MessageCameraVisibility {
    /// Camera and Stop must fit wholly inside the current visible region.
    /// Small or partly clipped panels fail closed rather than imply awareness.
    static func permitsScanning(preview: CGRect, stop: CGRect, viewport: CGRect) -> Bool {
        guard [preview, stop, viewport].allSatisfy({ rectangle in
            [rectangle.minX, rectangle.minY, rectangle.width, rectangle.height].allSatisfy(\.isFinite)
        }), preview.width >= 80, preview.height >= 48, stop.width >= 44, stop.height >= 44 else { return false }
        return viewport.contains(preview) && viewport.contains(stop)
    }
}
