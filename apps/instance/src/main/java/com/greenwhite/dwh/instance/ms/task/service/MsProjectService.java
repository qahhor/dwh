package com.greenwhite.dwh.instance.ms.task.service;

import com.greenwhite.dwh.core.error.ErrorCode;
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
    private final com.greenwhite.dwh.instance.search.typesense.TypesenseIndexer typesenseIndexer;

    public MsProjectService(
            MsProjectRepository projectRepository,
            MdCustomFieldService customFieldService,
            com.greenwhite.dwh.instance.search.typesense.TypesenseIndexer typesenseIndexer) {
        this.projectRepository = projectRepository;
        this.customFieldService = customFieldService;
        this.typesenseIndexer = typesenseIndexer;
    }

    @Transactional
    public MsProjectRepository.ProjectRecord createProject(
            String name, String description, String state, Map<String, Object> attributes, Long createdBy) {

        if (attributes != null) {
            customFieldService.validateAttributes("PROJECT", attributes);
        }

        var project = projectRepository.create(name, description, state, attributes, createdBy);
        typesenseIndexer.indexProject(project.id());
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
        getProjectById(id);
        if (attributes != null) {
            customFieldService.validateAttributes("PROJECT", attributes);
        }
        projectRepository.update(id, name, description, state, attributes);
        typesenseIndexer.indexProject(id);
    }


    @Transactional
    public void addProjectMember(Long projectId, Long userId, String accessKind) {
        getProjectById(projectId);
        projectRepository.addMember(projectId, userId, accessKind);
    }

    @Transactional
    public void removeProjectMember(Long projectId, Long userId) {
        projectRepository.removeMember(projectId, userId);
    }

    @Transactional(readOnly = true)
    public List<MsProjectRepository.ProjectMemberRecord> getProjectMembers(Long projectId) {
        return projectRepository.getMembers(projectId);
    }
}
