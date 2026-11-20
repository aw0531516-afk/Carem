package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ShotDao {
    @Query("SELECT * FROM shot_records ORDER BY timestampMs DESC")
    fun observeAllShots(): Flow<List<ShotRecordEntity>>

    @Query("SELECT * FROM shot_records WHERE outcomeCategory = 'SUCCESS' ORDER BY timestampMs DESC")
    fun observeSuccessfulShots(): Flow<List<ShotRecordEntity>>

    @Query("SELECT * FROM shot_records WHERE outcomeCategory = 'FAILED' ORDER BY timestampMs DESC")
    fun observeFailedShots(): Flow<List<ShotRecordEntity>>

    @Query("SELECT * FROM shot_records ORDER BY timestampMs DESC")
    suspend fun getAllShotsSnapshot(): List<ShotRecordEntity>

    @Query("SELECT presetKey FROM shot_records WHERE presetKey IS NOT NULL")
    suspend fun getExistingPresetKeys(): List<String>

    @Query("SELECT recordUid FROM shot_records")
    suspend fun getExistingRecordUids(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertShotIfNew(shot: ShotRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertShotsIfNew(shots: List<ShotRecordEntity>)

    @Query("DELETE FROM shot_records WHERE id = :id")
    suspend fun deleteShotById(id: Long)

    @Query("DELETE FROM shot_records WHERE isPreset = 0")
    suspend fun clearUserShotsOnly()

    @Query("SELECT * FROM diagnostic_logs ORDER BY timestampMs DESC LIMIT 200")
    fun observeDiagnosticLogs(): Flow<List<DiagnosticLogEntity>>

    @Insert
    suspend fun insertDiagnosticLog(log: DiagnosticLogEntity)

    @Query("DELETE FROM diagnostic_logs")
    suspend fun clearDiagnosticLogs()
}
