package com.antitheft.common.models

/**
 * Encapsulates an autonomous GNSS or network location acquisition fix.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double? = null,
    val velocity: Float? = null,
    val captureTimeUtc: Long // Epoch seconds
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
        require(accuracy >= 0.0f) { "Accuracy must be non-negative" }
    }
}

/**
 * Signed location event prepared for local Room persistence and server sync.
 */
data class SignedLocationEvent(
    val sequenceNumber: Long,
    val location: LocationFix,
    val signatureDerBase64: String
)

/**
 * Owner Admin display status.
 */
enum class DeviceStatus {
    LIVE,        // Fix acquired within the last 5 minutes
    DELAYED,     // Fix acquired between 5 minutes and 24 hours ago
    UNAVAILABLE  // No fix, or fix older than 24 hours
}
