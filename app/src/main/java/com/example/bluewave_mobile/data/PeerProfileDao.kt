package com.example.bluewave_mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `peer_profile` table — populated by
 * [com.example.bluewave_mobile.data.MessageRepositoryImpl] when an
 * inbound
 * [com.example.bluewave_mobile.network.BlueWaveFrame.Type.PROFILE_METADATA]
 * frame is decrypted.
 *
 * Every read is a [Flow] so the UI is automatically refreshed by
 * Room's invalidation tracker as soon as the upsert lands; every
 * write is `suspend` so callers stay on a coroutine and never
 * block the main thread.
 */
@Dao
interface PeerProfileDao {

    /**
     * Upsert the cached profile for [PeerProfileEntity.macAddress].
     * `OnConflictStrategy.REPLACE` keeps the table single-row per
     * peer — the latest pushed profile wins.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: PeerProfileEntity)

    /**
     * Reactive view of one peer's cached profile. Emits `null`
     * before any inbound profile has been received; this lets the
     * chat top bar and contact-list rows fall back to the radio
     * device name / MAC without any extra branching.
     */
    @Query("SELECT * FROM peer_profile WHERE macAddress = :macAddress LIMIT 1")
    fun observeProfile(macAddress: String): Flow<PeerProfileEntity?>

    /**
     * Reactive view of every cached peer profile. The device-list
     * screen consumes this to resolve `displayName` for every row
     * (preferring the peer-pushed name over the radio device
     * name).
     */
    @Query("SELECT * FROM peer_profile")
    fun observeAll(): Flow<List<PeerProfileEntity>>
}
