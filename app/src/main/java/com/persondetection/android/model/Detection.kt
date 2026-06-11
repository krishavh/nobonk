package com.persondetection.android.model

import androidx.compose.ui.geometry.Rect

/**
 * Represents a detection with bounding box and distance information
 */
data class Detection(
    val id: String,
    val boundingBox: Rect,  // Normalized coordinates (0-1)
    val confidence: Float,
    val distance: Float,  // Estimated distance in meters
    val className: String,  // "person" or "dog"
    val isApproaching: Boolean = false
)

/**
 * Alert level based on distance thresholds
 */
enum class AlertLevel {
    NONE,    // > threshold
    LOW,     // 50-100% of threshold (yellow)
    MEDIUM,  // 25-50% of threshold (orange)
    HIGH     // < 25% of threshold (red)
}
