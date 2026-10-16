package com.antitheft.guardian.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: LocationEntity)

    @Query("SELECT * FROM location_queue WHERE syncStatus = 'PENDING' ORDER BY sequenceNumber ASC")
    suspend fun getPendingLocations(): List<LocationEntity>

    @Query("UPDATE location_queue SET syncStatus = 'SYNCED' WHERE sequenceNumber IN (:sequences)")
    suspend fun markAsSynced(sequences: List<Long>)

    @Query("DELETE FROM location_queue WHERE syncStatus = 'SYNCED' AND captureTimeUtc < :thresholdUtc")
    suspend fun pruneSyncedLocations(thresholdUtc: Long)

    @Query("SELECT MAX(sequenceNumber) FROM location_queue")
    suspend fun getMaxSequenceNumber(): Long?

    @Query("SELECT COUNT(*) FROM location_queue WHERE syncStatus = 'PENDING'")
    suspend fun getPendingCount(): Int

    @Query("SELECT * FROM location_queue ORDER BY sequenceNumber DESC LIMIT 1")
    suspend fun getLatestLocation(): LocationEntity?
}
