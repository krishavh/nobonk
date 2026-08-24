package com.persondetection.android.ml

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Analyses raw camera frames for environmental hazards that YOLO can't detect.
 *
 * 1. **Wall proximity** — detected via *adjacent-cell difference*, NOT global std-dev.
 *
 *    Previous approaches failed because:
 *    - Global pixel std-dev: textured walls (brick/concrete) have high per-pixel variance.
 *    - Global cell-mean std-dev: overhead lighting gradients (bright top, dark bottom)
 *      produce high std-dev even on a plain wall.
 *
 *    New approach: divide the centre 60% into a 4×4 grid, compute mean brightness per
 *    cell, then measure the MEAN ABSOLUTE DIFFERENCE BETWEEN ADJACENT CELLS (horizontal
 *    and vertical neighbours).  This is GRADIENT-INVARIANT — a wall lit from above has
 *    small step-changes between neighbours even though overall contrast is high.
 *    A complex scene (street, room with furniture) has abrupt jumps between neighbours.
 *
 *    Threshold: mean adjacent diff < 18 → wall-like.
 *    Debug: lastWallScore is exposed so the UI can show the live value for tuning.
 *
 * 2. **Ground hazard** — a dark patch in the bottom quarter contrasting sharply with
 *    its surroundings (pothole / gap / step-down).
 */
class FrameAnalyzer {

    // ── Public results ──────────────────────────────────────────

    var isWallDetected: Boolean = false
        private set

    var isGroundHazardDetected: Boolean = false
        private set

    /**
     * Last raw wall score: mean absolute diff between adjacent grid cells.
     * Lower = more wall-like.  Exposed so the UI can display it for live tuning.
     */
    var lastWallScore: Float = 999f
        private set

    // ── State for wall persistence ──────────────────────────────

    private var wallConsecutiveFrames = 0
    private var groundConsecutiveFrames = 0

    // ── Tuning constants ────────────────────────────────────────

    companion object {
        private const val TAG = "FrameAnalyzer"

        // Wall: adjacent-cell difference test
        // Lower threshold = stricter (only very uniform scenes pass).
        // 18 works well for plain walls even with overhead lighting gradients.
        // Raise toward 25 if too many false negatives; lower toward 12 to reduce false positives.
        private const val WALL_GRID_COLS = 4
        private const val WALL_GRID_ROWS = 4
        private const val WALL_ADJACENT_DIFF_THRESHOLD = 18f
        private const val WALL_MIN_MEAN_BRIGHTNESS = 10f      // exclude near-black (camera covered)
        private const val WALL_MAX_MEAN_BRIGHTNESS = 245f     // exclude fully blown-out frames
        private const val WALL_PERSISTENCE_FRAMES = 2         // consecutive frames required
        private const val GROUND_PERSISTENCE_FRAMES = 2       // consecutive frames (fixes ML-12: no single-frame shadow spam)

        // Ground hazard
        private const val GROUND_CELL_COLS = 8
        private const val GROUND_CELL_ROWS = 3
        private const val GROUND_DARK_RATIO = 0.55f
        private const val GROUND_MIN_CONTRAST = 25
    }

    // ── Analysis entry point ────────────────────────────────────

    fun analyze(bitmap: Bitmap) {
        val rawWall = checkWall(bitmap)

        if (rawWall) {
            wallConsecutiveFrames = (wallConsecutiveFrames + 1).coerceAtMost(WALL_PERSISTENCE_FRAMES + 1)
        } else {
            wallConsecutiveFrames = 0
        }
        isWallDetected = wallConsecutiveFrames >= WALL_PERSISTENCE_FRAMES

        // Ground hazard: require persistence too, so a single-frame shadow doesn't spam.
        if (checkGroundHazard(bitmap)) {
            groundConsecutiveFrames = (groundConsecutiveFrames + 1).coerceAtMost(GROUND_PERSISTENCE_FRAMES + 1)
        } else {
            groundConsecutiveFrames = 0
        }
        isGroundHazardDetected = groundConsecutiveFrames >= GROUND_PERSISTENCE_FRAMES
    }

    // ── Wall detection — adjacent-cell difference ────────────────

    private fun checkWall(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        // Sample the centre 60% of the frame (tighter crop reduces edge clutter)
        val x0 = (w * 0.20f).toInt()
        val x1 = (w * 0.80f).toInt()
        val y0 = (h * 0.20f).toInt()
        val y1 = (h * 0.80f).toInt()

        val regionW = (x1 - x0) / WALL_GRID_COLS
        val regionH = (y1 - y0) / WALL_GRID_ROWS
        if (regionW < 4 || regionH < 4) return false

        // Build the 4×4 grid of cell mean brightnesses
        val cellMeans = FloatArray(WALL_GRID_ROWS * WALL_GRID_COLS)
        var overallSum = 0f
        for (row in 0 until WALL_GRID_ROWS) {
            for (col in 0 until WALL_GRID_COLS) {
                val mean = regionMeanBrightness(
                    bitmap,
                    x0 + col * regionW,
                    y0 + row * regionH,
                    regionW, regionH
                )
                cellMeans[row * WALL_GRID_COLS + col] = mean
                overallSum += mean
            }
        }
        val overallMean = overallSum / cellMeans.size

        // Reject frames that are too dark (covered camera) or blown-out
        if (overallMean < WALL_MIN_MEAN_BRIGHTNESS || overallMean > WALL_MAX_MEAN_BRIGHTNESS) {
            lastWallScore = 999f
            return false
        }

        // ── Key metric: mean absolute difference between adjacent cells ──────────
        // This is gradient-invariant: a wall lit from above has small per-step changes
        // between neighbours even though global contrast is high.
        var adjDiffSum = 0f
        var adjCount = 0
        for (row in 0 until WALL_GRID_ROWS) {
            for (col in 0 until WALL_GRID_COLS) {
                val idx = row * WALL_GRID_COLS + col
                if (col < WALL_GRID_COLS - 1) {           // horizontal neighbour
                    adjDiffSum += abs(cellMeans[idx] - cellMeans[idx + 1])
                    adjCount++
                }
                if (row < WALL_GRID_ROWS - 1) {           // vertical neighbour
                    adjDiffSum += abs(cellMeans[idx] - cellMeans[idx + WALL_GRID_COLS])
                    adjCount++
                }
            }
        }
        val meanAdjDiff = if (adjCount > 0) adjDiffSum / adjCount else Float.MAX_VALUE
        lastWallScore = meanAdjDiff

        val isWall = meanAdjDiff < WALL_ADJACENT_DIFF_THRESHOLD
        return isWall
    }

    /** Average brightness of a rectangular region, sampled every ~4px for speed. */
    private fun regionMeanBrightness(bitmap: Bitmap, x0: Int, y0: Int, w: Int, h: Int): Float {
        val step = maxOf(1, minOf(w, h) / 4)
        var sum = 0L
        var count = 0
        var x = x0
        while (x < x0 + w && x < bitmap.width) {
            var y = y0
            while (y < y0 + h && y < bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                sum += ((pixel shr 16 and 0xFF) * 299 +
                        (pixel shr 8  and 0xFF) * 587 +
                        (pixel        and 0xFF) * 114) / 1000
                count++
                y += step
            }
            x += step
        }
        return if (count > 0) sum.toFloat() / count else 0f
    }

    // ── Ground hazard detection ─────────────────────────────────

    private fun checkGroundHazard(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height

        val yStart = (h * 0.75f).toInt()
        val cellW = w / GROUND_CELL_COLS
        val cellH = (h - yStart) / GROUND_CELL_ROWS
        if (cellW < 4 || cellH < 4) return false

        val grid = Array(GROUND_CELL_ROWS) { row ->
            FloatArray(GROUND_CELL_COLS) { col ->
                regionMeanBrightness(bitmap, col * cellW, yStart + row * cellH, cellW, cellH)
            }
        }

        for (row in 0 until GROUND_CELL_ROWS) {
            for (col in 1 until GROUND_CELL_COLS - 1) {
                val cell = grid[row][col]
                val avgNeighbour = (grid[row][col - 1] + grid[row][col + 1]) / 2f
                if (avgNeighbour > 0 && cell < avgNeighbour * GROUND_DARK_RATIO
                    && (avgNeighbour - cell) > GROUND_MIN_CONTRAST
                ) return true
            }
        }

        // Dark band spanning the bottom (step-down / curb)
        val bottomAvg = grid[GROUND_CELL_ROWS - 1].average().toFloat()
        val topAvg    = grid[0].average().toFloat()
        if (topAvg > 0 && bottomAvg < topAvg * 0.5f && (topAvg - bottomAvg) > GROUND_MIN_CONTRAST) {
            return true
        }

        return false
    }
}
