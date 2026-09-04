package com.greenwhite.dwh.instance.ms.task;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.ScopeFilter;
import com.greenwhite.dwh.instance.md.service.MdCustomFieldService;
import com.greenwhite.dwh.instance.md.service.MdScopeService;
import com.greenwhite.dwh.instance.mf.service.MfFileService;
import com.greenwhite.dwh.instance.ms.task.repository.*;
import com.greenwhite.dwh.instance.ms.task.service.MsTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MsTaskServiceTest {

    private final MsTaskRepository taskRepository = Mockito.mock(MsTaskRepository.class);
    private final MsTaskStatusRepository statusRepository = Mockito.mock(MsTaskStatusRepository.class);
    private final MsTaskTypeRepository typeRepository = Mockito.mock(MsTaskTypeRepository.class);
    private final MsTaskMemberRepository memberRepository = Mockito.mock(MsTaskMemberRepository.class);
    private final MsProjectRepository projectRepository = Mockito.mock(MsProjectRepository.class);
    private final MdCustomFieldService customFieldService = Mockito.mock(MdCustomFieldService.class);
    private final MdScopeService scopeService = Mockito.mock(MdScopeService.class);
    private final MfFileService fileService = Mockito.mock(MfFileService.class);

    private final org.springframework.context.ApplicationEventPublisher eventPublisher =
            Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
    private final com.greenwhite.dwh.instance.search.typesense.TypesenseIndexer typesenseIndexer =
            Mockito.mock(com.greenwhite.dwh.instance.search.typesense.TypesenseIndexer.class);
    private final com.greenwhite.dwh.instance.audit.service.AuditLogService auditLogService =
            Mockito.mock(com.greenwhite.dwh.instance.audit.service.AuditLogService.class);

    private final MsTaskService service = new MsTaskService(
            taskRepository, statusRepository, typeRepository, memberRepository, projectRepository, customFieldService,
            scopeService, fileService, eventPublisher, typesenseIndexer, auditLogService
    );




    @Test
    @DisplayName("Установка задачи самой себе в качестве родительской должна вызывать ошибку TASK_PARENT_CYCLE")
    void shouldPreventSelfParentCycle() {
        var task = new MsTaskRepository.TaskRecord(
                10L, 1L, null, "Задача 1", "", 1L, "medium", 1L, Map.of(), null, null, null,
                Instant.now(), Instant.now(), 1L, 1L
        );
        when(scopeService.filterForTasks(1L)).thenReturn(ScopeFilter.unrestricted());
        when(taskRepository.findById(10L, ScopeFilter.unrestricted())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.updateTask(10L, "Задача 1", "", "medium", 10L, null, null, null, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("цикл");
    }

    @Test
    @DisplayName("Установка дочерней задачи в качестве родительской должна вызывать ошибку TASK_PARENT_CYCLE")
    void shouldPreventChildAsParentCycle() {
        var task = new MsTaskRepository.TaskRecord(
                10L, 1L, null, "Задача 1", "", 1L, "medium", 1L, Map.of(), null, null, null,
                Instant.now(), Instant.now(), 1L, 1L
        );
        when(scopeService.filterForTasks(1L)).thenReturn(ScopeFilter.unrestricted());
        when(taskRepository.findById(10L, ScopeFilter.unrestricted())).thenReturn(Optional.of(task));
        when(taskRepository.findById(20L, ScopeFilter.unrestricted())).thenReturn(Optional.of(task));
        when(taskRepository.isDescendantOf(20L, 10L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateTask(10L, "Задача 1", "", "medium", 20L, null, null, null, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("цикл");
    }

    @Test
    @DisplayName("Прямое чтение задачи всегда передаёт row-scope текущего пользователя в репозиторий")
    void directTaskReadUsesCurrentUserScope() {
        var scope = ScopeFilter.taskSelf(17L);
        var task = new MsTaskRepository.TaskRecord(
                10L, 1L, null, "Задача", "", 1L, "medium", 17L, Map.of(), null, null, null,
                Instant.now(), Instant.now(), 17L, 17L);
        when(scopeService.filterForTasks(17L)).thenReturn(scope);
        when(taskRepository.findById(10L, scope)).thenReturn(Optional.of(task));

        service.getTaskById(10L, 17L);

        verify(taskRepository).findById(10L, scope);
    }

    @Test
    @DisplayName("Нельзя назначить участника за пределами data scope инициатора")
    void participantOutsideActorScopeIsRejected() {
        var task = new MsTaskRepository.TaskRecord(
                10L, 1L, null, "Задача", "", 1L, "medium", 17L, Map.of(), null, null, null,
                Instant.now(), Instant.now(), 17L, 17L);
        when(scopeService.filterForTasks(17L)).thenReturn(ScopeFilter.taskSelf(17L));
        when(taskRepository.findById(10L, ScopeFilter.taskSelf(17L))).thenReturn(Optional.of(task));
        when(scopeService.canAccessUser(17L, 99L)).thenReturn(false);

        assertThatThrownBy(() -> service.setResponsible(10L, 99L, 17L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("пользователь недоступен");
    }
}
