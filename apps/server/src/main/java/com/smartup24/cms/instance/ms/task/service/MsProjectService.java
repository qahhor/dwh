package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.CursorUtils;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.ms.task.api.ProjectMemberView;
import com.smartup24.cms.instance.ms.task.api.ProjectView;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository;
import com.smartup24.cms.instance.search.service.SearchChangePublisher;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MsProjectService {

    static final int DEFAULT_MEMBERS = 50;
    static final int MAX_MEMBERS = 200;

    private final MsProjectRepository projectRepository;
    private final MdCustomFieldService customFieldService;
    private final SearchChangePublisher searchChangePublisher;
    private final AuditLogService auditLogService;

    public MsProjectService(
            MsProjectRepository projectRepository,
            MdCustomFieldService customFieldService,
            SearchChangePublisher searchChangePublisher,
            AuditLogService auditLogService) {
        this.projectRepository = projectRepository;
        this.customFieldService = customFieldService;
        this.searchChangePublisher = searchChangePublisher;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public ProjectView createProject(
            String name, String description, String state, Map<String, Object> attributes, Long createdBy) {

        String normalizedName = validateAndNormalizeName(name, true);
        validateState(state);
        Map<String, Object> storedAttributes =
                attributes != null ? customFieldService.checkedAttributes("PROJECT", attributes) : null;

        var project = projectRepository.create(normalizedName, description, state, storedAttributes, createdBy);
        searchChangePublisher.projectChanged(project.id());

        auditLogService.logChange(
                "ms_task_projects",
                String.valueOf(project.id()),
                "I",
                List.of("name", "state"),
                null,
                Map.of("name", normalizedName, "state", project.state()));

        return MsTaskViews.project(project);
    }

    @Transactional(readOnly = true)
    public ProjectView getProjectById(Long id) {
        return MsTaskViews.project(findProject(id));
    }

    private MsProjectRepository.ProjectRecord findProject(Long id) {
        return projectRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.PROJECT_NOT_FOUND));
    }

    @Transactional
    public long updateProject(
            Long id,
            String name,
            String description,
            String state,
            Map<String, Object> attributes,
            long expectedRevision) {
        var before = findProject(id);
        String normalizedName = validateAndNormalizeName(name, false);
        validateState(state);
        Map<String, Object> storedAttributes =
                attributes != null ? customFieldService.checkedAttributes("PROJECT", attributes) : null;
        long revision =
                projectRepository.update(id, normalizedName, description, state, storedAttributes, expectedRevision);
        searchChangePublisher.projectChanged(id);

        auditLogService.logChange(
                "ms_task_projects",
                String.valueOf(id),
                "U",
                List.of("name", "description", "state"),
                Map.of("name", before.name(), "state", before.state()),
                Map.of(
                        "name",
                        normalizedName != null ? normalizedName : before.name(),
                        "state",
                        state != null ? state : before.state()));
        return revision;
    }

    private String validateAndNormalizeName(String name, boolean required) {
        String normalizedName = name != null ? name.trim() : null;
        if ((required && normalizedName == null) || (normalizedName != null && normalizedName.isBlank())) {
            throw ApiException.validation(
                    "error.project.name_required",
                    List.of(FieldErrorItem.keyed("name", "required", "error.project.name_required")));
        }
        return normalizedName;
    }

    private void validateState(String state) {
        if (state != null && !state.equals("A") && !state.equals("P")) {
            throw ApiException.validation(
                    "error.project.state_invalid",
                    List.of(FieldErrorItem.keyed("state", "invalid", "error.field.one_of", Map.of("values", "A, P"))));
        }
    }

    @Transactional
    public void addProjectMember(Long projectId, Long userId, String accessKind) {
        findProject(projectId);
        projectRepository.addMember(projectId, userId, accessKind);

        // Project membership is access to its tasks, so changing it is an access change:
        // it is audited on a par with granting permissions.
        auditLogService.logChange(
                "ms_task_project_members",
                projectId + ":" + userId,
                "I",
                List.of("user_id", "access_kind"),
                null,
                Map.of("project_id", projectId, "user_id", userId, "access_kind", accessKind));
    }

    @Transactional
    public void removeProjectMember(Long projectId, Long userId) {
        projectRepository.removeMember(projectId, userId);

        auditLogService.logChange(
                "ms_task_project_members",
                projectId + ":" + userId,
                "D",
                List.of("user_id"),
                Map.of("project_id", projectId, "user_id", userId),
                null);
    }

    /**
     * A page of the members of a project by name (plan 10/10, item 3.5): {@code limit} 1 to {@link #MAX_MEMBERS}
     * (else 422), {@code cursor} the {@code nextCursor} of the previous page (422 when it is not one).
     */
    @Transactional(readOnly = true)
    public KeysetPage<ProjectMemberView> pageProjectMembers(Long projectId, Integer limit, String cursor) {
        int size = TimePage.limit(limit, DEFAULT_MEMBERS, MAX_MEMBERS);
        var after = cursor == null || cursor.isBlank() ? null : decodeMember(projectId, cursor);
        var rows = projectRepository.getMembers(projectId, after, size + 1);
        boolean hasMore = rows.size() > size;
        var items = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore ? encodeMember(items.getLast()) : null;
        return new KeysetPage<>(
                MsTaskViews.all(items, MsTaskViews::projectMember),
                next,
                hasMore,
                items.size(),
                after == null && !hasMore);
    }

    private static String encodeMember(MsProjectRepository.ProjectMemberRecord member) {
        return CursorUtils.encode(member.userId() + "|" + member.userName());
    }

    /** The user id goes first: a name may hold the separator. */
    private static MsProjectRepository.ProjectMemberRecord decodeMember(Long projectId, String cursor) {
        String raw = CursorUtils.decode(cursor);
        int bar = raw == null ? -1 : raw.indexOf('|');
        if (bar <= 0) {
            throw TimePage.invalidCursor();
        }
        try {
            long userId = Long.parseLong(raw.substring(0, bar));
            return new MsProjectRepository.ProjectMemberRecord(projectId, userId, raw.substring(bar + 1), null, null);
        } catch (NumberFormatException notOurs) {
            throw TimePage.invalidCursor();
        }
    }
}
