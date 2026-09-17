package com.vibe.app.feature.agent.workflow

enum class AgentStage {
    AUDIT,
    PLANNER,
    UI_UX,
    CODER,
    REVIEWER,
    BUILD,
    DEBUGGER,
    TESTER,
    DONE,
}

enum class ProjectStatus {
    NEW,
    EXISTING_COMPLETE,
    EXISTING_INCOMPLETE,
    EXISTING_BROKEN,
    UNKNOWN,
}

enum class WorkflowDecision {
    CONTINUE,
    RETRY,
    GO_TO_STAGE,
    RESUME,
    BLOCK,
    DONE,
}

data class AuditState(
    val status: ProjectStatus = ProjectStatus.UNKNOWN,
    val auditCompleted: Boolean = false,
    val checkedFiles: List<String> = emptyList(),
    val completedStages: List<AgentStage> = emptyList(),
    val incompleteStages: List<AgentStage> = emptyList(),
    val brokenStages: List<AgentStage> = emptyList(),
    val resumeStage: AgentStage? = null,
    val findings: List<String> = emptyList(),
    val auditedAt: Long? = null,
)

data class ProjectCheckpoint(
    val id: String,
    val projectId: String,
    val stage: AgentStage,
    val timestamp: Long,
    val lastAction: String? = null,
    val nextAction: String? = null,
    val buildSuccessful: Boolean? = null,
    val testsSuccessful: Boolean? = null,
    val knownErrors: List<String> = emptyList(),
    val attempt: Int = 0,
    val valid: Boolean = true,
)

data class MasterProjectState(
    val projectId: String,
    val goal: String,
    val scope: List<String> = emptyList(),
    val scopeLocked: Boolean = true,
    val architecture: String? = null,
    val requirements: List<String> = emptyList(),
    val milestones: List<String> = emptyList(),
    val currentStage: AgentStage = AgentStage.AUDIT,
    val completedStages: List<AgentStage> = emptyList(),
    val failedStages: List<AgentStage> = emptyList(),
    val nextRequiredAction: String? = null,
    val knownErrors: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val filesState: Map<String, String> = emptyMap(),
    val buildState: String? = null,
    val testState: String? = null,
    val auditState: AuditState = AuditState(),
    val checkpoint: ProjectCheckpoint? = null,
    val resumePoint: AgentStage? = null,
)

data class StageContext(
    val stage: AgentStage,
    val goal: String,
    val scope: List<String>,
    val constraints: List<String>,
    val previousFindings: List<String>,
    val nextRequiredAction: String?,
)

data class AgentExecutionResult(
    val stage: AgentStage,
    val success: Boolean,
    val summary: String? = null,
    val nextAction: String? = null,
    val findings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

interface WorkflowAgent {
    val stage: AgentStage

    suspend fun execute(
        state: MasterProjectState,
        context: StageContext,
    ): AgentExecutionResult
}

interface ProjectStateRepository {
    suspend fun create(state: MasterProjectState)
    suspend fun get(projectId: String): MasterProjectState?
    suspend fun update(state: MasterProjectState)
    suspend fun saveCheckpoint(
        projectId: String,
        checkpoint: ProjectCheckpoint,
    )
    suspend fun getLatestCheckpoint(projectId: String): ProjectCheckpoint?
    suspend fun delete(projectId: String)
}
