package com.vibe.app.feature.agent.workflow

import com.vibe.app.data.repository.ProjectRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectAudit @Inject constructor(
    private val projectRepository: ProjectRepository,
) {

    suspend fun audit(
        projectId: String,
        existingState: MasterProjectState? = null,
    ): AuditState {
        val project = projectRepository.fetchProject(projectId)

        if (project == null) {
            return AuditState(
                status = ProjectStatus.UNKNOWN,
                auditCompleted = true,
                findings = listOf("Project record was not found."),
                auditedAt = System.currentTimeMillis(),
            )
        }

        val workspace = File(project.project.workspacePath)

        if (!workspace.exists() || !workspace.isDirectory) {
            return AuditState(
                status = ProjectStatus.EXISTING_BROKEN,
                auditCompleted = true,
                findings = listOf("Project workspace is missing or inaccessible."),
                auditedAt = System.currentTimeMillis(),
            )
        }

        val files = workspace
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(workspace).path }
            .take(500)
            .toList()

        if (files.isEmpty()) {
            return AuditState(
                status = ProjectStatus.NEW,
                auditCompleted = true,
                checkedFiles = emptyList(),
                resumeStage = AgentStage.PLANNER,
                findings = listOf("Workspace contains no project files."),
                auditedAt = System.currentTimeMillis(),
            )
        }

        val state = existingState

        if (state != null) {
            val completed = state.completedStages

            if (state.currentStage == AgentStage.DONE) {
                return AuditState(
                    status = ProjectStatus.EXISTING_COMPLETE,
                    auditCompleted = true,
                    checkedFiles = files,
                    completedStages = completed,
                    resumeStage = AgentStage.DONE,
                    findings = listOf("Persisted workflow state is complete."),
                    auditedAt = System.currentTimeMillis(),
                )
            }

            return AuditState(
                status = ProjectStatus.EXISTING_INCOMPLETE,
                auditCompleted = true,
                checkedFiles = files,
                completedStages = completed,
                incompleteStages = AgentStage.entries
                    .filter { it != AgentStage.DONE && it !in completed },
                resumeStage = state.currentStage,
                findings = listOf(
                    "Existing workspace detected.",
                    "Persisted workflow state indicates unfinished work.",
                ),
                auditedAt = System.currentTimeMillis(),
            )
        }

        return AuditState(
            status = ProjectStatus.EXISTING_INCOMPLETE,
            auditCompleted = true,
            checkedFiles = files,
            resumeStage = AgentStage.PLANNER,
            findings = listOf(
                "Existing workspace detected.",
                "No MasterProjectState exists yet; semantic completion requires workflow initialization.",
            ),
            auditedAt = System.currentTimeMillis(),
        )
    }
}
