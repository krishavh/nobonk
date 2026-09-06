package ai.genwhy.nobonk.ml

/**
 * Turns real camera characteristics into the one number the distance estimator needs.
 *
 * The estimator is a pinhole model on the *upright* frame: `distance = H_real · f / h_box`,
 * where `h_box` is the box height as a fraction of frame height and `f` is the focal length
 * expressed in the same units — focal length divided by the sensor extent along the frame's
 * height axis. Until now `f` was a hard-coded 0.87 (roughly a 1/2.55" sensor at 4.7 mm);
 * real phones range from ~0.8 to ~1.3, which is a 50 % distance error on its own.
 *
 * Portrait phones mount the sensor rotated 90°, so the upright frame's HEIGHT runs along
 * the sensor's LONG side. A 16:9 analysis stream crops the sensor's short side, which in
 * portrait is the frame WIDTH, so the height mapping is unaffected by the stream aspect.
 */
object CameraIntrinsics {
    /** Fallback when the camera does not report intrinsics. */
    const val DEFAULT_FOCAL_NORM = 0.87f
    private const val MIN_FOCAL_NORM = 0.5f
    private const val MAX_FOCAL_NORM = 2.5f

    /**
     * @param focalMm      lens focal length in mm (first of LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
     * @param sensorWmm    SENSOR_INFO_PHYSICAL_SIZE width in mm
     * @param sensorHmm    SENSOR_INFO_PHYSICAL_SIZE height in mm
     * @param sensorRotationDeg CameraInfo.sensorRotationDegrees (90/270 = portrait mapping)
     * @return normalized focal length, or null when the inputs are unusable
     */
    fun normalizedFocal(focalMm: Float, sensorWmm: Float, sensorHmm: Float, sensorRotationDeg: Int): Float? {
        if (!(focalMm > 0f) || !(sensorWmm > 0f) || !(sensorHmm > 0f)) return null
        val portrait = ((sensorRotationDeg % 180) + 180) % 180 != 0
        val heightExtent = if (portrait) maxOf(sensorWmm, sensorHmm) else minOf(sensorWmm, sensorHmm)
        val f = focalMm / heightExtent
        if (f.isNaN() || f < MIN_FOCAL_NORM || f > MAX_FOCAL_NORM) return null
        return f
    }
}
