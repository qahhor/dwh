package com.smartup24.cms.instance.ms.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatusRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatusRepository.StatusRecord;
import com.smartup24.cms.instance.ms.task.repository.MsTaskTypeRepository;
import com.smartup24.cms.instance.ms.task.service.MsTaskStatusService;
import com.smartup24.cms.instance.search.SearchChangePublisher;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** Plan 10/10, item 3.1: the refusals of the task dictionaries carry their code and catalog key. */
class MsTaskStatusServiceTest {

    private final MsTaskStatusRepository statuses = mock(MsTaskStatusRepository.class);
    private final MsTaskTypeRepository types = mock(MsTaskTypeRepository.class);
    private final MsTaskStatusService service =
            new MsTaskStatusService(statuses, types, mock(SearchChangePublisher.class));

    @Test
    void statusRefusals() {
        refused(() -> service.createStatus(null, " ", "gray", 1, false), ErrorCode.BAD_REQUEST, "status_name_required");

        when(statuses.findById(9L)).thenReturn(Optional.empty());
        refused(
                () -> service.updateStatusRecord(9L, "X", null, null, null, 1L),
                ErrorCode.NOT_FOUND,
                "status_not_found");
        refused(() -> service.deleteStatus(9L), ErrorCode.NOT_FOUND, "status_not_found");

        when(statuses.findById(1L)).thenReturn(Optional.of(new StatusRecord(1L, "new", "New", "gray", 1, false, 1L)));
        refused(() -> service.deleteStatus(1L), ErrorCode.BAD_REQUEST, "status_system_delete");

        when(statuses.findById(2L)).thenReturn(Optional.of(new StatusRecord(2L, null, "Own", "gray", 2, false, 1L)));
        when(statuses.delete(2L)).thenReturn(false);
        refused(() -> service.deleteStatus(2L), ErrorCode.BAD_REQUEST, "status_in_use");
    }

    @Test
    void typeRefusals() {
        refused(() -> service.createType("", "Name", null, null, 1), ErrorCode.BAD_REQUEST, "type_code_name_required");
        refused(() -> service.createType("bug", " ", null, null, 1), ErrorCode.BAD_REQUEST, "type_code_name_required");

        when(types.findById(9L)).thenReturn(Optional.empty());
        refused(() -> service.updateType(9L, "X", null, null, null, 1L), ErrorCode.NOT_FOUND, "type_not_found");
        refused(() -> service.deleteType(9L), ErrorCode.NOT_FOUND, "type_not_found");
        verify(types, never()).delete(any());
    }

    private static void refused(ThrowingCallable call, ErrorCode code, String key) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(code);
            assertThat(error.getMessageKey()).isEqualTo("error.task." + key);
        });
    }
}
