package com.antitheft.guardian.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_queue")
data class LocationEntity(
    @PrimaryKey
    val sequenceNumber: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double? = null,
    val velocity: Float? = null,
    val captureTimeUtc: Long,
    val signatureDerBase64: String,
    val syncStatus: String = STATUS_PENDING
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SYNCED = "SYNCED"
    }
}
