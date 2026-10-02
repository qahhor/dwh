package com.smartup24.cms.instance.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.error.GlobalExceptionHandler;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.kauth.security.RequiresPermissionInterceptor;
import com.smartup24.cms.instance.mf.api.FileView;
import com.smartup24.cms.instance.mf.controller.MfFileController;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.ms.task.controller.MsTaskCommentController;
import com.smartup24.cms.instance.ms.task.controller.MsTaskController;
import com.smartup24.cms.instance.ms.task.service.MsTaskCommentService;
import com.smartup24.cms.instance.ms.task.service.MsTaskMemberService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TaskFileDataScopeControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void taskEndpointRejectsMissingActionPermissionBeforeService() throws Exception {
        MsTaskMemberService service = mock(MsTaskMemberService.class);
        SecurityContext.setPrincipal(principal(Set.of()));

        taskMvc(service)
                .perform(get("/api/v1/tasks/42/members"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));
    }

    @Test
    void taskEndpointReturnsNotFoundForOutOfScopeIdentifier() throws Exception {
        MsTaskMemberService service = mock(MsTaskMemberService.class);
        SecurityContext.setPrincipal(principal(Set.of("tasks.items.view")));
        when(service.getTaskMembers(42L, 10L))
                .thenThrow(ApiException.notFound(ErrorCode.TASK_NOT_FOUND, "Задача не найдена"));

        taskMvc(service)
                .perform(get("/api/v1/tasks/42/members"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("task_not_found"));
    }

    @Test
    void taskEndpointPassesAuthenticatedUserToTheScopedRead() throws Exception {
        MsTaskMemberService service = mock(MsTaskMemberService.class);
        SecurityContext.setPrincipal(principal(Set.of("tasks.items.view")));
        when(service.getTaskMembers(42L, 10L)).thenReturn(List.of());

        taskMvc(service).perform(get("/api/v1/tasks/42/members")).andExpect(status().isOk());

        verify(service).getTaskMembers(42L, 10L);
    }

    @Test
    void commentEndpointRejectsMissingActionPermissionBeforeService() throws Exception {
        MsTaskCommentService service = mock(MsTaskCommentService.class);
        SecurityContext.setPrincipal(principal(Set.of()));

        commentMvc(service)
                .perform(get("/api/v1/tasks/42/comments"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));
    }

    @Test
    void commentEndpointReturnsNotFoundForOutOfScopeTaskIdentifier() throws Exception {
        MsTaskCommentService service = mock(MsTaskCommentService.class);
        SecurityContext.setPrincipal(principal(Set.of("tasks.comments.view")));
        when(service.listComments(eq(42L), eq(10L), any()))
                .thenThrow(ApiException.notFound(ErrorCode.TASK_NOT_FOUND, "Задача не найдена"));

        commentMvc(service)
                .perform(get("/api/v1/tasks/42/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("task_not_found"));
    }

    @Test
    void fileEndpointRejectsMissingActionPermissionBeforeService() throws Exception {
        MfFileService service = mock(MfFileService.class);
        SecurityContext.setPrincipal(principal(Set.of()));

        fileMvc(service)
                .perform(get("/api/v1/files/6db360cf-26ba-4729-b8c9-f5adcf2df74c"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));
    }

    @Test
    void fileEndpointReturnsNotFoundForOutOfScopeIdentifier() throws Exception {
        MfFileService service = mock(MfFileService.class);
        UUID id = UUID.fromString("6db360cf-26ba-4729-b8c9-f5adcf2df74c");
        SecurityContext.setPrincipal(principal(Set.of("mf.files.view")));
        when(service.getFileMetadata(id, 10L))
                .thenThrow(ApiException.notFound(ErrorCode.FILE_NOT_FOUND, "Файл не найден"));

        fileMvc(service)
                .perform(get("/api/v1/files/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("file_not_found"));
    }

    @Test
    void fileEndpointPassesAuthenticatedUserToScopedRead() throws Exception {
        MfFileService service = mock(MfFileService.class);
        UUID id = UUID.fromString("6db360cf-26ba-4729-b8c9-f5adcf2df74c");
        SecurityContext.setPrincipal(principal(Set.of("mf.files.view")));
        when(service.getFileMetadata(id, 10L)).thenReturn(file(id));

        fileMvc(service)
                .perform(get("/api/v1/files/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        verify(service).getFileMetadata(id, 10L);
    }

    private static MockMvc taskMvc(MsTaskMemberService service) {
        return MockMvcBuilders.standaloneSetup(new MsTaskController(service))
                .addInterceptors(new RequiresPermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
                .build();
    }

    private static MockMvc fileMvc(MfFileService service) {
        return MockMvcBuilders.standaloneSetup(new MfFileController(service))
                .addInterceptors(new RequiresPermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
                .build();
    }

    private static MockMvc commentMvc(MsTaskCommentService service) {
        return MockMvcBuilders.standaloneSetup(new MsTaskCommentController(service))
                .addInterceptors(new RequiresPermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
                .build();
    }

    private static SecurityContext.KauthPrincipal principal(Set<String> permissions) {
        return new SecurityContext.KauthPrincipal(
                10L, "scoped", "scoped@example.invalid", 20L, false, permissions, 1L, false, 0, null);
    }

    private static FileView file(UUID id) {
        return new FileView(id, "scoped.txt", 1, "text/plain", Instant.parse("2026-09-04T10:15:30Z"), 10L);
    }
}
