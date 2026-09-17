package com.vibe.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vibe.app.data.database.entity.WorkflowCheckpointEntity

@Dao
interface WorkflowCheckpointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(checkpoint: WorkflowCheckpointEntity)

    @Query("""
        SELECT * FROM workflow_checkpoints
        WHERE projectId = :projectId AND valid = 1
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun getLatest(projectId: String): WorkflowCheckpointEntity?

    @Query("DELETE FROM workflow_checkpoints WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}
