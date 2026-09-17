package com.vibe.app.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "workflow_checkpoints",
)
data class WorkflowCheckpointEntity(
    @PrimaryKey
    val id: String,
    val projectId: String,
    val stage: String,
    val timestamp: Long,
    val checkpointJson: String,
    val valid: Boolean = true,
)
