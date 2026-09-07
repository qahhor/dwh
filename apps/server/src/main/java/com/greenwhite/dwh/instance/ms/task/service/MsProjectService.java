package com.greenwhite.dwh.instance.ms.task.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.service.MdCustomFieldService;
import com.greenwhite.dwh.instance.ms.task.repository.MsProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class MsProjectService {

    private final MsProjectRepository projectRepository;
    private final MdCustomFieldService customFieldService;
    private final com.greenwhite.dwh.instance.search.SearchChangePublisher searchChangePublisher;
    private final AuditLogService auditLogService;

    public MsProjectService(
            MsProjectRepository projectRepository,
            MdCustomFieldService customFieldService,
            com.greenwhite.dwh.instance.search.SearchChangePublisher searchChangePublisher,
            AuditLogService auditLogService) {
        this.projectRepository = projectRepository;
        this.customFieldService = customFieldService;
        this.searchChangePublisher = searchChangePublisher;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public MsProjectRepository.ProjectRecord createProject(
            String name, String description, String state, Map<String, Object> attributes, Long createdBy) {

        String normalizedName = validateAndNormalizeName(name, true);
        validateState(state);
        if (attributes != null) {
            customFieldService.validateAttributes("PROJECT", attributes);
        }

        var project = projectRepository.create(normalizedName, description, state, attributes, createdBy);
        searchChangePublisher.projectChanged(project.id());

        auditLogService.logChange("ms_task_projects", String.valueOf(project.id()), "I",
                List.of("name", "state"),
                null,
                Map.of("name", normalizedName, "state", project.state()));

        return project;
    }


    @Transactional(readOnly = true)
    public MsProjectRepository.ProjectRecord getProjectById(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.PROJECT_NOT_FOUND, "Проект не найден"));
    }

    @Transactional(readOnly = true)
    public List<MsProjectRepository.ProjectRecord> listProjects(String state) {
        return projectRepository.listProjects(state);
    }

    @Transactional
    public void updateProject(Long id, String name, String description, String state, Map<String, Object> attributes) {
        var before = getProjectById(id);
        String normalizedName = validateAndNormalizeName(name, false);
        validateState(state);
        if (attributes != null) {
            customFieldService.validateAttributes("PROJECT", attributes);
        }
        projectRepository.update(
                id,
                normalizedName,
                description,
                state,
                attributes);
        searchChangePublisher.projectChanged(id);

        auditLogService.logChange("ms_task_projects", String.valueOf(id), "U",
                List.of("name", "description", "state"),
                Map.of("name", before.name(), "state", before.state()),
                Map.of("name", normalizedName != null ? normalizedName : before.name(),
                        "state", state != null ? state : before.state()));
    }

    private String validateAndNormalizeName(String name, boolean required) {
        String normalizedName = name != null ? name.trim() : null;
        if ((required && normalizedName == null) || (normalizedName != null && normalizedName.isBlank())) {
            throw ApiException.validation("Название проекта обязательно", List.of(
                    new FieldErrorItem("name", "required", "Название проекта обязательно")));
        }
        return normalizedName;
    }

    private void validateState(String state) {
        if (state != null && !state.equals("A") && !state.equals("P")) {
            throw ApiException.validation("Недопустимый статус проекта", List.of(
                    new FieldErrorItem("state", "invalid", "Допустимые значения: A, P")));
        }
    }


    @Transactional
    public void addProjectMember(Long projectId, Long userId, String accessKind) {
        getProjectById(projectId);
        projectRepository.addMember(projectId, userId, accessKind);

        // Состав участников проекта — это доступ к его задачам, а значит
        // изменение доступа: журналируется наравне с выдачей прав.
        auditLogService.logChange("ms_task_project_members", projectId + ":" + userId, "I",
                List.of("user_id", "access_kind"),
                null,
                Map.of("project_id", projectId, "user_id", userId, "access_kind", accessKind));
    }

    @Transactional
    public void removeProjectMember(Long projectId, Long userId) {
        projectRepository.removeMember(projectId, userId);

        auditLogService.logChange("ms_task_project_members", projectId + ":" + userId, "D",
                List.of("user_id"),
                Map.of("project_id", projectId, "user_id", userId),
                null);
    }

    @Transactional(readOnly = true)
    public List<MsProjectRepository.ProjectMemberRecord> getProjectMembers(Long projectId) {
        return projectRepository.getMembers(projectId);
    }
}
