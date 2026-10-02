package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The members of a project as its record actions change them ({@link MsProjectMemberActions}): a member is added with
 * read or write access, or removed; each change is audited as an access change, because membership opens the project's
 * tasks (ADR-0013). The runtime has read the project in the actor's scope before an action runs.
 */
@Service
public class MsProjectMemberService {

    /** Read and write access of a member. */
    public static final List<String> ACCESS_KINDS = List.of("R", "W");

    private final MsProjectRepository projectRepository;
    private final AuditLogService auditLogService;
    private final MdScopeService scopeService;

    public MsProjectMemberService(
            MsProjectRepository projectRepository, AuditLogService auditLogService, MdScopeService scopeService) {
        this.projectRepository = projectRepository;
        this.auditLogService = auditLogService;
        this.scopeService = scopeService;
    }

    /**
     * Adds a member or changes their access; the new member must be someone the actor sees.
     *
     * @return the problem of the action's parameters, or null when the member was added
     */
    @Transactional
    public @Nullable FieldErrorItem addMember(
            long projectId, @Nullable Long userId, @Nullable String accessKind, long actorId) {
        if (userId == null || !scopeService.canAccessUser(actorId, userId)) {
            return FieldErrorItem.keyed("params.userId", "not_found", "error.field.ref_not_found");
        }
        if (accessKind == null || !ACCESS_KINDS.contains(accessKind)) {
            return FieldErrorItem.keyed(
                    "params.accessKind",
                    "invalid",
                    "error.field.one_of",
                    Map.of("values", String.join(", ", ACCESS_KINDS)));
        }
        if (projectRepository.addMember(projectId, userId, accessKind)) {
            auditLogService.logChange(
                    "ms_task_project_members",
                    projectId + ":" + userId,
                    "I",
                    List.of("user_id", "access_kind"),
                    null,
                    Map.of("project_id", projectId, "user_id", userId, "access_kind", accessKind));
        }
        return null;
    }

    /** Removes a member; removing someone who is no member changes nothing. */
    @Transactional
    public void removeMember(long projectId, long userId) {
        if (projectRepository.removeMember(projectId, userId)) {
            auditLogService.logChange(
                    "ms_task_project_members",
                    projectId + ":" + userId,
                    "D",
                    List.of("user_id"),
                    Map.of("project_id", projectId, "user_id", userId),
                    null);
        }
    }
}
