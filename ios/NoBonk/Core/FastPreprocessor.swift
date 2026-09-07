import Foundation

enum FastPixelRotation: Sendable { case upright, clockwise90 }
enum FastPreprocessError: Error { case invalidBuffer }

enum FastPreprocessor {
    /// Pixel-center bilinear letterbox, RGB planar float32 [0,1], gray 114 padding.
    /// The camera supplies BGRA only for Fast mode. No UIImage/Core Image rendering.
    /// Android uses an 8-bit Canvas destination, so round interpolated channels to
    /// 8-bit values before normalization. Skia's filter precision may differ by one level.
    static func fill(bgra: UnsafeBufferPointer<UInt8>, width: Int, height: Int, rowBytes: Int,
                     rotation: FastPixelRotation, into tensor: UnsafeMutableBufferPointer<Float>,
                     edge: Int = FastModelContract.edge) throws -> FastLetterbox {
        guard width > 0, height > 0, width <= Int.max / 4, rowBytes >= width * 4,
              rowBytes <= Int.max / height, bgra.count >= rowBytes * height,
              edge > 0, edge <= 4096, tensor.count == 3 * edge * edge else { throw FastPreprocessError.invalidBuffer }
        let outWidth = rotation == .upright ? width : height
        let outHeight = rotation == .upright ? height : width
        let transform = FastLetterbox(width: outWidth, height: outHeight, edge: edge)!
        let plane = edge * edge
        func channel(_ x: Int, _ y: Int, _ c: Int) -> Float {
            let rawX = rotation == .upright ? x : y
            let rawY = rotation == .upright ? y : height - 1 - x
            return Float(bgra[rawY * rowBytes + rawX * 4 + c])
        }
        for y in 0..<edge {
            let sourceY = (Float(y) + 0.5 - transform.padY) / transform.scale - 0.5
            for x in 0..<edge {
                let index = y * edge + x
                let sourceX = (Float(x) + 0.5 - transform.padX) / transform.scale - 0.5
                // Test pixel centers against the transformed image rectangle.
                if Float(x) + 0.5 < transform.padX || Float(x) + 0.5 >= transform.padX + Float(outWidth) * transform.scale ||
                    Float(y) + 0.5 < transform.padY || Float(y) + 0.5 >= transform.padY + Float(outHeight) * transform.scale {
                    tensor[index] = 114 / 255; tensor[plane + index] = 114 / 255; tensor[2 * plane + index] = 114 / 255
                    continue
                }
                let sx = min(Float(outWidth - 1), max(0, sourceX)), sy = min(Float(outHeight - 1), max(0, sourceY))
                let x0 = Int(sx), y0 = Int(sy), x1 = min(outWidth - 1, Int(sx) + 1), y1 = min(outHeight - 1, Int(sy) + 1)
                let dx = sx - Float(x0), dy = sy - Float(y0)
                for planeIndex in 0..<3 {
                    let byteChannel = 2 - planeIndex
                    let upper = channel(x0, y0, byteChannel) * (1 - dx) + channel(x1, y0, byteChannel) * dx
                    let lower = channel(x0, y1, byteChannel) * (1 - dx) + channel(x1, y1, byteChannel) * dx
                    tensor[planeIndex * plane + index] = (upper * (1 - dy) + lower * dy).rounded() / 255
                }
            }
        }
        return transform
    }
}
