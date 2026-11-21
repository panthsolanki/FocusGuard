package com.focusguard.blocker.data

import androidx.room.*

@Entity(tableName = "blocked_targets")
data class BlockedTarget(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isEnabled: Boolean = true,
    val dailyLimitMinutes: Int? = null
)

@Entity(
    tableName = "block_schedules",
    foreignKeys = [ForeignKey(
        entity = BlockedTarget::class,
        parentColumns = ["packageName"],
        childColumns = ["targetPackage"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("targetPackage")]
)
data class BlockSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetPackage: String,
    val daysOfWeekMask: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int
)

data class TargetWithSchedules(
    @Embedded val target: BlockedTarget,
    @Relation(parentColumn = "packageName", entityColumn = "targetPackage")
    val schedules: List<BlockSchedule>
)
