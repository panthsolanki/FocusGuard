package com.focusguard.blocker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockDao {
    @Transaction
    @Query("SELECT * FROM blocked_targets")
    fun observeAllRules(): Flow<List<TargetWithSchedules>>

    @Transaction
    @Query("SELECT * FROM blocked_targets WHERE packageName = :pkg AND isEnabled = 1")
    suspend fun getActiveRuleForPackage(pkg: String): TargetWithSchedules?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTarget(target: BlockedTarget)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: BlockSchedule)

    @Query("DELETE FROM block_schedules WHERE targetPackage = :pkg")
    suspend fun clearSchedulesForPackage(pkg: String)

    @Query("DELETE FROM blocked_targets WHERE packageName = :pkg")
    suspend fun deleteTarget(pkg: String)
}
