package com.vibe.app.data.repository

import com.vibe.app.data.database.dao.WorkflowCheckpointDao
import com.vibe.app.data.database.dao.WorkflowStateDao
import com.vibe.app.data.database.entity.WorkflowCheckpointEntity
import com.vibe.app.data.database.entity.WorkflowStateEntity
import com.vibe.app.feature.agent.workflow.MasterProjectState
import com.vibe.app.feature.agent.workflow.ProjectCheckpoint
import com.vibe.app.feature.agent.workflow.ProjectStateRepository
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomProjectStateRepository @Inject constructor(
    private val stateDao: WorkflowStateDao,
    private val checkpointDao: WorkflowCheckpointDao,
) : ProjectStateRepository {

    override suspend fun create(state: MasterProjectState) {
        saveState(state)
    }

    override suspend fun get(projectId: String): MasterProjectState? {
        return stateDao.get(projectId)?.let {
            decodeState(it.stateJson)
        }
    }

    override suspend fun update(state: MasterProjectState) {
        saveState(state)
    }

    override suspend fun saveCheckpoint(
        projectId: String,
        checkpoint: ProjectCheckpoint,
    ) {
        checkpointDao.save(
            WorkflowCheckpointEntity(
                id = checkpoint.id,
                projectId = projectId,
                stage = checkpoint.stage.name,
                timestamp = checkpoint.timestamp,
                checkpointJson = encodeCheckpoint(checkpoint),
                valid = checkpoint.valid,
            )
        )
    }

    override suspend fun getLatestCheckpoint(
        projectId: String,
    ): ProjectCheckpoint? {
        return checkpointDao.getLatest(projectId)?.let {
            decodeCheckpoint(it.checkpointJson)
        }
    }

    override suspend fun delete(projectId: String) {
        stateDao.delete(projectId)
        checkpointDao.deleteForProject(projectId)
    }

    private suspend fun saveState(state: MasterProjectState) {
        stateDao.save(
            WorkflowStateEntity(
                projectId = state.projectId,
                stateJson = encodeState(state),
            )
        )
    }

    private fun encodeState(state: MasterProjectState): String {
        return JSONObject().apply {
            put("projectId", state.projectId)
            put("goal", state.goal)
            put("scope", JSONArray(state.scope))
            put("scopeLocked", state.scopeLocked)
            put("architecture", state.architecture)
            put("requirements", JSONArray(state.requirements))
            put("milestones", JSONArray(state.milestones))
            put("currentStage", state.currentStage.name)
            put("completedStages", JSONArray(state.completedStages.map { it.name }))
            put("failedStages", JSONArray(state.failedStages.map { it.name }))
            put("nextRequiredAction", state.nextRequiredAction)
            put("knownErrors", JSONArray(state.knownErrors))
            put("decisions", JSONArray(state.decisions))
            put("constraints", JSONArray(state.constraints))
            put("buildState", state.buildState)
            put("testState", state.testState)
            put("resumePoint", state.resumePoint?.name)
            put("auditState", JSONObject().apply {
                put("status", state.auditState.status.name)
                put("auditCompleted", state.auditState.auditCompleted)
                put("checkedFiles", JSONArray(state.auditState.checkedFiles))
                put("completedStages", JSONArray(state.auditState.completedStages.map { it.name }))
                put("incompleteStages", JSONArray(state.auditState.incompleteStages.map { it.name }))
                put("brokenStages", JSONArray(state.auditState.brokenStages.map { it.name }))
                put("resumeStage", state.auditState.resumeStage?.name)
                put("findings", JSONArray(state.auditState.findings))
                put("auditedAt", state.auditState.auditedAt)
            })
        }.toString()
    }

    private fun decodeState(json: String): MasterProjectState {
        val o = JSONObject(json)
        val audit = o.optJSONObject("auditState") ?: JSONObject()

        return MasterProjectState(
            projectId = o.getString("projectId"),
            goal = o.getString("goal"),
            scope = stringList(o.optJSONArray("scope")),
            scopeLocked = o.optBoolean("scopeLocked", true),
            architecture = o.optString("architecture").takeIf { it.isNotBlank() },
            requirements = stringList(o.optJSONArray("requirements")),
            milestones = stringList(o.optJSONArray("milestones")),
            currentStage = stage(o.optString("currentStage")),
            completedStages = stageList(o.optJSONArray("completedStages")),
            failedStages = stageList(o.optJSONArray("failedStages")),
            nextRequiredAction = o.optString("nextRequiredAction").takeIf { it.isNotBlank() },
            knownErrors = stringList(o.optJSONArray("knownErrors")),
            decisions = stringList(o.optJSONArray("decisions")),
            constraints = stringList(o.optJSONArray("constraints")),
            buildState = o.optString("buildState").takeIf { it.isNotBlank() },
            testState = o.optString("testState").takeIf { it.isNotBlank() },
            resumePoint = o.optString("resumePoint").takeIf { it.isNotBlank() }?.let(::stage),
            auditState = com.vibe.app.feature.agent.workflow.AuditState(
                status = runCatching {
                    com.vibe.app.feature.agent.workflow.ProjectStatus.valueOf(
                        audit.optString("status")
                    )
                }.getOrDefault(com.vibe.app.feature.agent.workflow.ProjectStatus.UNKNOWN),
                auditCompleted = audit.optBoolean("auditCompleted"),
                checkedFiles = stringList(audit.optJSONArray("checkedFiles")),
                completedStages = stageList(audit.optJSONArray("completedStages")),
                incompleteStages = stageList(audit.optJSONArray("incompleteStages")),
                brokenStages = stageList(audit.optJSONArray("brokenStages")),
                resumeStage = audit.optString("resumeStage")
                    .takeIf { it.isNotBlank() }?.let(::stage),
                findings = stringList(audit.optJSONArray("findings")),
                auditedAt = if (audit.has("auditedAt") && !audit.isNull("auditedAt"))
                    audit.optLong("auditedAt") else null,
            ),
        )
    }

    private fun encodeCheckpoint(checkpoint: ProjectCheckpoint): String {
        return JSONObject().apply {
            put("id", checkpoint.id)
            put("projectId", checkpoint.projectId)
            put("stage", checkpoint.stage.name)
            put("timestamp", checkpoint.timestamp)
            put("lastAction", checkpoint.lastAction)
            put("nextAction", checkpoint.nextAction)
            put("buildSuccessful", checkpoint.buildSuccessful)
            put("testsSuccessful", checkpoint.testsSuccessful)
            put("knownErrors", JSONArray(checkpoint.knownErrors))
            put("attempt", checkpoint.attempt)
            put("valid", checkpoint.valid)
        }.toString()
    }

    private fun decodeCheckpoint(json: String): ProjectCheckpoint {
        val o = JSONObject(json)
        return ProjectCheckpoint(
            id = o.getString("id"),
            projectId = o.getString("projectId"),
            stage = stage(o.getString("stage")),
            timestamp = o.getLong("timestamp"),
            lastAction = o.optString("lastAction").takeIf { it.isNotBlank() },
            nextAction = o.optString("nextAction").takeIf { it.isNotBlank() },
            buildSuccessful = nullableBoolean(o, "buildSuccessful"),
            testsSuccessful = nullableBoolean(o, "testsSuccessful"),
            knownErrors = stringList(o.optJSONArray("knownErrors")),
            attempt = o.optInt("attempt", 0),
            valid = o.optBoolean("valid", true),
        )
    }

    private fun nullableBoolean(o: JSONObject, key: String): Boolean? {
        return if (!o.has(key) || o.isNull(key)) null else o.optBoolean(key)
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                add(array.optString(i))
            }
        }
    }

    private fun stageList(array: JSONArray?): List<com.vibe.app.feature.agent.workflow.AgentStage> {
        return stringList(array).mapNotNull {
            runCatching { com.vibe.app.feature.agent.workflow.AgentStage.valueOf(it) }.getOrNull()
        }
    }

    private fun stage(value: String): com.vibe.app.feature.agent.workflow.AgentStage {
        return runCatching {
            com.vibe.app.feature.agent.workflow.AgentStage.valueOf(value)
        }.getOrDefault(com.vibe.app.feature.agent.workflow.AgentStage.AUDIT)
    }
}
