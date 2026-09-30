package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.ms.task.api.ProjectMemberView;
import com.smartup24.cms.instance.ms.task.api.ProjectView;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository;
import com.smartup24.cms.instance.search.SearchChangePublisher;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MsProjectService {

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
        if (attributes != null) {
            customFieldService.validateAttributes("PROJECT", attributes);
        }

        var project = projectRepository.create(normalizedName, description, state, attributes, createdBy);
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

    @Transactional(readOnly = true)
    public List<ProjectView> listProjects(String state) {
        return MsTaskViews.all(projectRepository.listProjects(state), MsTaskViews::project);
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
        if (attributes != null) {
            customFieldService.validateAttributes("PROJECT", attributes);
        }
        long revision = projectRepository.update(id, normalizedName, description, state, attributes, expectedRevision);
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

        // Состав участников проекта — это доступ к его задачам, а значит
        // изменение доступа: журналируется наравне с выдачей прав.
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

    @Transactional(readOnly = true)
    public List<ProjectMemberView> getProjectMembers(Long projectId) {
        return MsTaskViews.all(projectRepository.getMembers(projectId), MsTaskViews::projectMember);
    }
}
