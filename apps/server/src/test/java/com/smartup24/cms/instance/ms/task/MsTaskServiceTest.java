package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.ms.task.repository.*;
import com.smartup24.cms.instance.ms.task.service.MsTaskService;
import com.smartup24.cms.instance.search.SearchChangePublisher;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

class MsTaskServiceTest {

    private final MsTaskRepository taskRepository = Mockito.mock(MsTaskRepository.class);
    private final MsTaskStatusRepository statusRepository = Mockito.mock(MsTaskStatusRepository.class);
    private final MsTaskTypeRepository typeRepository = Mockito.mock(MsTaskTypeRepository.class);
    private final MsTaskMemberRepository memberRepository = Mockito.mock(MsTaskMemberRepository.class);
    private final MsProjectRepository projectRepository = Mockito.mock(MsProjectRepository.class);
    private final MdCustomFieldService customFieldService = Mockito.mock(MdCustomFieldService.class);
    private final MdScopeService scopeService = Mockito.mock(MdScopeService.class);
    private final MfFileService fileService = Mockito.mock(MfFileService.class);

    private final ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
    private final SearchChangePublisher searchChangePublisher = Mockito.mock(SearchChangePublisher.class);
    private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

    private final MsTaskService service = new MsTaskService(
            taskRepository,
            statusRepository,
            typeRepository,
            memberRepository,
            projectRepository,
            customFieldService,
            scopeService,
            fileService,
            eventPublisher,
            searchChangePublisher,
            auditLogService);

    @Test
    @DisplayName("Установка задачи самой себе в качестве родительской должна вызывать ошибку TASK_PARENT_CYCLE")
    void shouldPreventSelfParentCycle() {
        var task = new MsTaskRepository.TaskRecord(
                10L,
                1L,
                null,
                "Задача 1",
                "",
                1L,
                "medium",
                1L,
                Map.of(),
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                1L,
                1L);
        when(scopeService.filterForTasks(1L)).thenReturn(ScopeFilter.unrestricted());
        when(taskRepository.findById(10L, ScopeFilter.unrestricted())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.updateTask(10L, "Задача 1", "", "medium", 10L, null, null, null, 1L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TASK_PARENT_CYCLE);
                    assertThat(error.getMessageKey()).isEqualTo("error.task.parent_cycle");
                });
    }

    @Test
    @DisplayName("Установка дочерней задачи в качестве родительской должна вызывать ошибку TASK_PARENT_CYCLE")
    void shouldPreventChildAsParentCycle() {
        var task = new MsTaskRepository.TaskRecord(
                10L,
                1L,
                null,
                "Задача 1",
                "",
                1L,
                "medium",
                1L,
                Map.of(),
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                1L,
                1L);
        when(scopeService.filterForTasks(1L)).thenReturn(ScopeFilter.unrestricted());
        when(taskRepository.findById(10L, ScopeFilter.unrestricted())).thenReturn(Optional.of(task));
        when(taskRepository.findById(20L, ScopeFilter.unrestricted())).thenReturn(Optional.of(task));
        when(taskRepository.isDescendantOf(20L, 10L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateTask(10L, "Задача 1", "", "medium", 20L, null, null, null, 1L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TASK_PARENT_CYCLE);
                    assertThat(error.getMessageKey()).isEqualTo("error.task.parent_cycle");
                });
    }

    @Test
    @DisplayName("Прямое чтение задачи всегда передаёт row-scope текущего пользователя в репозиторий")
    void directTaskReadUsesCurrentUserScope() {
        var scope = ScopeFilter.taskSelf(17L);
        var task = new MsTaskRepository.TaskRecord(
                10L,
                1L,
                null,
                "Задача",
                "",
                1L,
                "medium",
                17L,
                Map.of(),
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                17L,
                17L);
        when(scopeService.filterForTasks(17L)).thenReturn(scope);
        when(taskRepository.findById(10L, scope)).thenReturn(Optional.of(task));

        service.getTaskById(10L, 17L);

        verify(taskRepository).findById(10L, scope);
    }

    @Test
    @DisplayName("Нельзя назначить участника за пределами data scope инициатора")
    void participantOutsideActorScopeIsRejected() {
        var task = new MsTaskRepository.TaskRecord(
                10L,
                1L,
                null,
                "Задача",
                "",
                1L,
                "medium",
                17L,
                Map.of(),
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                17L,
                17L);
        when(scopeService.filterForTasks(17L)).thenReturn(ScopeFilter.taskSelf(17L));
        when(taskRepository.findById(10L, ScopeFilter.taskSelf(17L))).thenReturn(Optional.of(task));
        when(scopeService.canAccessUser(17L, 99L)).thenReturn(false);

        assertThatThrownBy(() -> service.setResponsible(10L, 99L, 17L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(error.getMessageKey()).isEqualTo("error.task.assignee_unavailable");
                });
    }
}
