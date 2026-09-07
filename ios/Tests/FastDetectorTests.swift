import XCTest
@testable import NoBonkCore

final class FastDetectorTests: XCTestCase {
    private let count = FastModelContract.anchors
    private func head(_ entries: [(Int, Int, Float)]) -> [Float] {
        var values = [Float](repeating: 0, count: 84 * count)
        for (anchor, cls, score) in entries {
            values[anchor] = 208; values[count + anchor] = 208
            values[2 * count + anchor] = 100; values[3 * count + anchor] = 200
            values[(4 + cls) * count + anchor] = score
        }
        return values
    }
    private func decode(_ values: [Float], width: Int = 416, height: Int = 416) throws -> [FastDetection] {
        try values.withUnsafeBufferPointer { try FastDecoder.decode($0, shape: [1,84,count], transform: FastLetterbox(width: width, height: height)!) }
    }
    func testActualGraphMetadataAndSupportedTaxonomy() throws {
        let url = Bundle.module.url(forResource: "fast-graph-contract", withExtension: "json", subdirectory: "Fixtures")!
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        XCTAssertEqual(json["sha256"] as? String, FastModelContract.sha256)
        XCTAssertEqual(json["input_name"] as? String, FastModelContract.inputName)
        XCTAssertEqual(json["output_name"] as? String, FastModelContract.outputName)
        XCTAssertEqual(json["input_shape"] as? [Int], [1,3,FastModelContract.edge,FastModelContract.edge])
        XCTAssertEqual(json["output_shape"] as? [Int], [1,FastModelContract.channels,FastModelContract.anchors])
        let names = json["names"] as! [String: String]
        XCTAssertEqual(names["15"], "cat"); XCTAssertEqual(names["16"], "dog"); XCTAssertEqual(names["17"], "horse")
        XCTAssertNil(FastModelContract.names[17])
        XCTAssertEqual(FastModelContract.selectedClasses, [0,1,2,3,5,7,16,15])
        for (id, name) in FastModelContract.names { XCTAssertEqual(names[String(id)], name) }
    }
    func testRawChannelMajorHeadIncludesCatDogAndRejectsHorse() throws {
        let boxes = try decode(head([(0,15,0.9),(1,16,0.8),(2,17,0.99)]))
        XCTAssertEqual(boxes.map(\.classID), [15,16])
        XCTAssertEqual(boxes.map(\.label), ["cat","dog"])
        XCTAssertEqual(boxes[0].rect.x, 158.0 / 416, accuracy: 0.000001)
    }
    func testSupportedClassSelectionTieOrderAndInclusiveThreshold() throws {
        let boxes = try decode(head([(0,15,0.4),(0,16,0.4),(0,17,0.99)]))
        XCTAssertEqual(boxes.count, 1); XCTAssertEqual(boxes.first?.classID, 16)
        XCTAssertEqual(boxes.first?.confidence, 0.4)
        XCTAssertTrue(try decode(head([(0,0,0.3999)])).isEmpty)
    }
    func testMalformedShapeAndNonfiniteBoxesNeverBecomeDetection() throws {
        XCTAssertThrowsError(try [Float](repeating: 0, count: 6).withUnsafeBufferPointer {
            try FastDecoder.decode($0, shape: [1,1,6], transform: FastLetterbox(width: 416, height: 416)!)
        })
        var values = head([(0,0,0.8),(1,15,0.9),(2,16,0.9)])
        values[0] = .nan; values[2 * count + 1] = -10; values[3 * count + 2] = .infinity
        XCTAssertTrue(try decode(values).isEmpty)
    }
    func testLetterboxPortraitLandscapeAndPaddingOnlyRejection() throws {
        for (width,height) in [(1920,1080),(1080,1920),(853,480)] {
            let transform = FastLetterbox(width: width, height: height)!
            let rect = transform.normalized(left: transform.padX + Float(width) * transform.scale * 0.25,
                top: transform.padY + Float(height) * transform.scale * 0.125,
                right: transform.padX + Float(width) * transform.scale * 0.75,
                bottom: transform.padY + Float(height) * transform.scale * 0.875)!
            XCTAssertEqual(rect.x, 0.25, accuracy: 0.000001); XCTAssertEqual(rect.y, 0.125, accuracy: 0.000001)
            XCTAssertEqual(rect.width, 0.5, accuracy: 0.000001); XCTAssertEqual(rect.height, 0.75, accuracy: 0.000001)
        }
        let landscape = FastLetterbox(width: 1920, height: 1080)!
        XCTAssertNil(landscape.normalized(left: 10, top: 0, right: 20, bottom: 10))
        let clipped = landscape.normalized(left: -20, top: -20, right: 500, bottom: 500)!
        XCTAssertEqual(clipped, PreviewRect(x: 0,y: 0,width: 1,height: 1))
    }
    func testNMSIsClassAwareStableAndStrictAtThreshold() {
        let rect = PreviewRect(x: 0,y: 0,width: 1,height: 1)
        let boxes = [FastDetection(anchor: 8,classID: 16,confidence: 0.8,rect: rect),
                     FastDetection(anchor: 1,classID: 16,confidence: 0.8,rect: rect),
                     FastDetection(anchor: 2,classID: 15,confidence: 0.7,rect: rect)]
        XCTAssertEqual(FastDecoder.suppress(boxes, threshold: 0.45).map(\.anchor), [8,2])
        XCTAssertEqual(FastDecoder.suppress(boxes, threshold: 1).map(\.anchor), [8,1,2])
        let half = FastDetection(anchor: 3,classID: 16,confidence: 0.7,rect: PreviewRect(x: 0,y: 0,width: 0.5,height: 1))
        XCTAssertEqual(FastDecoder.suppress([boxes[0],half], threshold: 0.5).count, 2)
        XCTAssertEqual(FastDecoder.suppress([boxes[0],half], threshold: 0.49).count, 1)
    }
    private func preprocess(_ bytes: [UInt8], width: Int, height: Int, stride: Int, rotation: FastPixelRotation = .upright, edge: Int) throws -> [Float] {
        var tensor = [Float](repeating: -1, count: 3 * edge * edge)
        try bytes.withUnsafeBufferPointer { pixels in
            try tensor.withUnsafeMutableBufferPointer { output in
                _ = try FastPreprocessor.fill(bgra: pixels,width: width,height: height,rowBytes: stride,rotation: rotation,into: output,edge: edge)
            }
        }
        return tensor
    }
    func testPreprocessingRGBPlanarStrideAndGrayPadding() throws {
        // Two red pixels, then blue sentinel bytes outside each real row.
        let tensor = try preprocess([0,0,255,255, 0,0,255,255, 255,0,0,255], width: 2,height: 1,stride: 12,edge: 4)
        for y in 0..<4 { for x in 0..<4 {
            let i = y * 4 + x
            if y == 0 || y == 3 {
                for channel in 0..<3 { XCTAssertEqual(tensor[channel * 16 + i], Float(114) / 255) }
            } else { XCTAssertEqual(tensor[i], 1); XCTAssertEqual(tensor[16+i], 0); XCTAssertEqual(tensor[32+i], 0) }
        }}
    }
    func testPreprocessingClockwiseRotationAndPixelCenterInterpolation() throws {
        // Red-channel 2x2 image [[10,20],[30,40]] rotates to [[30,10],[40,20]].
        let bytes: [UInt8] = [0,0,10,255, 0,0,20,255, 0,0,30,255, 0,0,40,255]
        let rotated = try preprocess(bytes,width: 2,height: 2,stride: 8,rotation: .clockwise90,edge: 2)
        XCTAssertEqual(Array(rotated[0..<4]), [30,10,40,20].map { Float($0) / 255 })
        let center = try preprocess(bytes,width: 2,height: 2,stride: 8,edge: 1)
        XCTAssertEqual(center[0], Float(25) / 255)
        XCTAssertThrowsError(try preprocess(bytes,width: 2,height: 2,stride: 7,edge: 2))
    }
    func testRectangularPortraitRotationKeepsCornersAndPadding() throws {
        let bytes: [UInt8] = [10,20,30,40,50,60].flatMap { [0,0,$0,255] }
        let tensor = try preprocess(bytes,width: 2,height: 3,stride: 8,rotation: .clockwise90,edge: 6)
        XCTAssertEqual(tensor[6], Float(50) / 255)
        XCTAssertEqual(tensor[11], Float(10) / 255)
        XCTAssertEqual(tensor[24], Float(60) / 255)
        XCTAssertEqual(tensor[29], Float(20) / 255)
        XCTAssertEqual(tensor[0], Float(114) / 255)
        XCTAssertEqual(tensor[35], Float(114) / 255)
    }
    func testObjectCueNeedsThreeSameClassFramesAndRejectsUnsupportedClasses() {
        func box(_ id: Int) -> PersonBox { PersonBox(id: 0,x: 0.3,y: 0.1,width: 0.4,height: 0.7,confidence: 0.8,classID: id,detectorMode: .fastObjects) }
        var policy = DetectionPolicy()
        XCTAssertEqual(policy.evaluate([box(15)],time: 0), .none)
        XCTAssertEqual(policy.evaluate([box(16)],time: 1), .none)
        XCTAssertEqual(policy.evaluate([box(16)],time: 2), .none)
        XCTAssertEqual(policy.evaluate([box(16)],time: 3), .objectAhead)
        policy.reset()
        for time in 0..<5 { XCTAssertEqual(policy.evaluate([box(17)],time: Double(time)), .none) }
        XCTAssertFalse(box(17).usable)
    }
}
