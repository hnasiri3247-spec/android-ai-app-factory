package com.vibe.app.feature.agent.workflow

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowOrchestratorImpl @Inject constructor(
    private val projectAudit: ProjectAudit,
    private val stateRepository: ProjectStateRepository,
) : WorkflowOrchestrator {

    override suspend fun start(
        projectId: String,
        userIntent: String,
    ): WorkflowResult {
        val existing = stateRepository.get(projectId)

        val audit = projectAudit.audit(
            projectId = projectId,
            existingState = existing,
        )

        val state = existing ?: MasterProjectState(
            projectId = projectId,
            goal = userIntent,
            scopeLocked = true,
            currentStage = AgentStage.AUDIT,
        )

        val auditedState = state.copy(
            auditState = audit,
            currentStage = audit.resumeStage ?: when (audit.status) {
                ProjectStatus.NEW -> AgentStage.PLANNER
                ProjectStatus.EXISTING_COMPLETE -> AgentStage.DONE
                ProjectStatus.EXISTING_INCOMPLETE -> audit.resumeStage
                    ?: AgentStage.PLANNER
                ProjectStatus.EXISTING_BROKEN -> AgentStage.DEBUGGER
                ProjectStatus.UNKNOWN -> AgentStage.AUDIT
            },
            resumePoint = audit.resumeStage,
            nextRequiredAction = nextActionFor(
                audit.resumeStage ?: AgentStage.PLANNER
            ),
        )

        if (existing == null) {
            stateRepository.create(auditedState)
        } else {
            stateRepository.update(auditedState)
        }

        return WorkflowResult(
            decision = when (auditedState.currentStage) {
                AgentStage.DONE -> WorkflowDecision.DONE
                AgentStage.AUDIT -> WorkflowDecision.BLOCK
                else -> WorkflowDecision.CONTINUE
            },
            stage = auditedState.currentStage,
            state = auditedState,
        )
    }

    override suspend fun resume(
        projectId: String,
    ): WorkflowResult {
        val state = stateRepository.get(projectId)
            ?: return WorkflowResult.blocked(
                "No persisted workflow state exists for project $projectId."
            )

        val checkpoint = stateRepository.getLatestCheckpoint(projectId)

        val stage = checkpoint?.stage
            ?: state.resumePoint
            ?: state.currentStage

        val resumed = state.copy(
            currentStage = stage,
            resumePoint = stage,
            checkpoint = checkpoint,
            nextRequiredAction = nextActionFor(stage),
        )

        stateRepository.update(resumed)

        return WorkflowResult(
            decision = if (stage == AgentStage.DONE) {
                WorkflowDecision.DONE
            } else {
                WorkflowDecision.RESUME
            },
            stage = stage,
            state = resumed,
        )
    }

    override suspend fun continueFrom(
        projectId: String,
        stage: AgentStage,
    ): WorkflowResult {
        val state = stateRepository.get(projectId)
            ?: return WorkflowResult.blocked(
                "No persisted workflow state exists for project $projectId."
            )

        val updated = state.copy(
            currentStage = stage,
            resumePoint = stage,
            nextRequiredAction = nextActionFor(stage),
        )

        stateRepository.update(updated)

        return WorkflowResult(
            decision = WorkflowDecision.GO_TO_STAGE,
            stage = stage,
            state = updated,
        )
    }

    override suspend fun handleFailure(
        projectId: String,
        stage: AgentStage,
        error: String,
    ): WorkflowResult {
        val state = stateRepository.get(projectId)
            ?: return WorkflowResult.blocked(
                "Cannot route failure without persisted project state."
            )

        val updated = state.copy(
            currentStage = AgentStage.DEBUGGER,
            failedStages = (state.failedStages + stage).distinct(),
            knownErrors = (state.knownErrors + error).distinct(),
            nextRequiredAction = "Investigate failure from $stage and repair the project.",
            resumePoint = AgentStage.DEBUGGER,
        )

        val checkpoint = ProjectCheckpoint(
            id = "${projectId}-${stage.name}-${System.currentTimeMillis()}",
            projectId = projectId,
            stage = stage,
            timestamp = System.currentTimeMillis(),
            lastAction = "Stage failed: ${stage.name}",
            nextAction = "DEBUGGER",
            knownErrors = updated.knownErrors,
            valid = false,
        )

        stateRepository.update(updated)
        stateRepository.saveCheckpoint(projectId, checkpoint)

        return WorkflowResult(
            decision = WorkflowDecision.GO_TO_STAGE,
            stage = AgentStage.DEBUGGER,
            state = updated,
        )
    }

    private fun nextActionFor(stage: AgentStage): String =
        when (stage) {
            AgentStage.AUDIT -> "Audit the project."
            AgentStage.PLANNER -> "Create or restore the project plan."
            AgentStage.UI_UX -> "Define the required UI/UX."
            AgentStage.CODER -> "Implement the planned changes."
            AgentStage.REVIEWER -> "Review implementation against the locked goal."
            AgentStage.BUILD -> "Build the Android project."
            AgentStage.DEBUGGER -> "Diagnose and repair the latest failure."
            AgentStage.TESTER -> "Run functional validation."
            AgentStage.DONE -> "Project is complete."
        }
}

data class WorkflowResult(
    val decision: WorkflowDecision,
    val stage: AgentStage,
    val state: MasterProjectState? = null,
    val message: String? = null,
) {
    companion object {
        fun blocked(message: String) = WorkflowResult(
            decision = WorkflowDecision.BLOCK,
            stage = AgentStage.AUDIT,
            message = message,
        )
    }
}
