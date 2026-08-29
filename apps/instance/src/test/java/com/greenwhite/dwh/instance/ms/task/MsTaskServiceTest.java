package com.greenwhite.dwh.instance.ms.task;

import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.service.MdCustomFieldService;
import com.greenwhite.dwh.instance.ms.task.repository.*;
import com.greenwhite.dwh.instance.ms.task.service.MsTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class MsTaskServiceTest {

    private final MsTaskRepository taskRepository = Mockito.mock(MsTaskRepository.class);
    private final MsTaskStatusRepository statusRepository = Mockito.mock(MsTaskStatusRepository.class);
    private final MsTaskTypeRepository typeRepository = Mockito.mock(MsTaskTypeRepository.class);
    private final MsTaskMemberRepository memberRepository = Mockito.mock(MsTaskMemberRepository.class);
    private final MsProjectRepository projectRepository = Mockito.mock(MsProjectRepository.class);
    private final MdCustomFieldService customFieldService = Mockito.mock(MdCustomFieldService.class);

    private final org.springframework.context.ApplicationEventPublisher eventPublisher =
            Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);

    private final MsTaskService service = new MsTaskService(
            taskRepository, statusRepository, typeRepository, memberRepository, projectRepository, customFieldService,
            eventPublisher
    );


    @Test
    @DisplayName("Установка задачи самой себе в качестве родительской должна вызывать ошибку TASK_PARENT_CYCLE")
    void shouldPreventSelfParentCycle() {
        var task = new MsTaskRepository.TaskRecord(
                10L, 1L, null, "Задача 1", "", 1L, "medium", 1L, Map.of(), null, null, null,
                Instant.now(), Instant.now(), 1L, 1L
        );
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

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
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(taskRepository.isDescendantOf(20L, 10L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateTask(10L, "Задача 1", "", "medium", 20L, null, null, null, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("цикл");
    }
}
