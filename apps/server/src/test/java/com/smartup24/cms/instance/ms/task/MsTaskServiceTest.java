package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.ms.task.repository.MsTaskFileRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskMemberRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskTreeRepository;
import com.smartup24.cms.instance.ms.task.service.MsTaskService;
import com.smartup24.cms.instance.search.SearchChangePublisher;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class MsTaskServiceTest {

    private final MsTaskFixture.Repositories repos = MsTaskFixture.Repositories.mocks();
    private final MsTaskRepository taskRepository = repos.tasks();
    private final MsTaskTreeRepository treeRepository = repos.tree();
    private final MsTaskFileRepository fileRepository = repos.files();
    private final MsTaskMemberRepository memberRepository = repos.members();
    private final MdScopeService scopeService = mock(MdScopeService.class);

    private final MsTaskFixture tasks = MsTaskFixture.wire(
            repos,
            MsTaskFixture.Collaborators.with(
                    scopeService, mock(SearchChangePublisher.class), mock(AuditLogService.class)),
            MsTaskFixture.NO_PROXY);
    private final MsTaskService service = tasks.tasks();

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
        when(treeRepository.isDescendantOf(20L, 10L)).thenReturn(true);

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
    @DisplayName("The task card reads every part in the viewer's scope and writes nothing")
    void taskDetailReadsEveryPartInViewerScope() {
        var scope = ScopeFilter.taskSelf(17L);
        Instant now = Instant.parse("2026-09-04T10:15:30Z");
        var task = new MsTaskRepository.TaskRecord(
                10L, 1L, null, "Задача", "", 1L, "medium", 17L, Map.of(), null, null, null, now, now, 17L, 17L);
        var child = new MsTaskRepository.TaskRecord(
                11L, 1L, 10L, "Подзадача", "", 1L, "low", 17L, Map.of(), null, null, null, now, now, 17L, 17L);
        var file = new MsTaskFileRepository.TaskFileRecord(
                java.util.UUID.fromString("6db360cf-26ba-4729-b8c9-f5adcf2df74c"), "a.txt", 3, "text/plain", now);
        when(scopeService.filterForTasks(17L)).thenReturn(scope);
        when(taskRepository.findById(10L, scope)).thenReturn(Optional.of(task));
        when(treeRepository.findSubtasks(10L, scope)).thenReturn(java.util.List.of(child));
        when(treeRepository.findAncestorChain(10L, scope)).thenReturn(java.util.List.of());
        when(fileRepository.listTaskFiles(10L)).thenReturn(java.util.List.of(file));

        var detail = tasks.reads().getTaskDetail(10L, 17L);

        assertThat(detail.task().id()).isEqualTo(10L);
        assertThat(detail.task().revision()).isEqualTo(1L);
        assertThat(detail.subtasks()).extracting(view -> view.id()).containsExactly(11L);
        assertThat(detail.ancestors()).isEmpty();
        assertThat(detail.files()).extracting(view -> view.fileName()).containsExactly("a.txt");
        verify(treeRepository).findSubtasks(10L, scope);
        verify(treeRepository).findAncestorChain(10L, scope);
        verify(memberRepository).getTaskMembers(10L);
        verify(memberRepository, never()).markViewed(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Marking a task viewed checks it in the viewer's scope, then writes the mark")
    void markViewedChecksScopeThenWrites() {
        var scope = ScopeFilter.taskSelf(17L);
        Instant now = Instant.parse("2026-09-04T10:15:30Z");
        var task = new MsTaskRepository.TaskRecord(
                10L, 1L, null, "Задача", "", 1L, "medium", 17L, Map.of(), null, null, null, now, now, 17L, 17L);
        when(scopeService.filterForTasks(17L)).thenReturn(scope);
        when(taskRepository.findById(10L, scope)).thenReturn(Optional.of(task));

        tasks.members().markViewed(10L, 17L);

        verify(memberRepository).markViewed(10L, 17L);
        when(taskRepository.findById(11L, scope)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tasks.members().markViewed(11L, 17L))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TASK_NOT_FOUND));
        verify(memberRepository, never()).markViewed(11L, 17L);
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

        assertThatThrownBy(() -> tasks.members().setResponsible(10L, 99L, 17L))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(error.getMessageKey()).isEqualTo("error.task.assignee_unavailable");
                });
    }
}
