package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.common.entity.hook.EntityActionCall;
import com.smartup24.cms.instance.common.entity.hook.EntityActionHandler;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatusRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The status of a task as a record action (ADR-0032, 8, step 4): {@code POST
 * /api/v1/entities/ms.tasks/{id}/actions/set_status {"status": "done"}} with If-Match. The status is a status in use of
 * {@code ms.task_statuses}; a terminal one resolves the task now, another one reopens it. The kanban moves a card with
 * it, and a bulk action runs it over many tasks. The runtime writes, audits and publishes the change; the task's hooks
 * tell the participants ({@link MsTaskHooks}), the search indexes the task from the change event.
 */
@Component
public class MsTaskStatusAction implements EntityActionHandler {

    /** The parameter: the code of the new status. */
    public static final String STATUS = "status";

    private final MsTaskStatusRepository statuses;
    private final Clock clock;

    @Autowired
    public MsTaskStatusAction(MsTaskStatusRepository statuses) {
        this(statuses, Clock.systemUTC());
    }

    public MsTaskStatusAction(MsTaskStatusRepository statuses, Clock clock) {
        this.statuses = statuses;
        this.clock = clock;
    }

    @Override
    public String entity() {
        return MsTaskEntity.CODE;
    }

    @Override
    public String action() {
        return MsTaskEntity.SET_STATUS;
    }

    @Override
    public void run(EntityActionCall call) {
        Object code = call.params().get(STATUS);
        Optional<MsTaskStatusRepository.StatusRecord> status =
                code instanceof String text ? statuses.findByCode(text) : Optional.empty();
        if (status.isEmpty()) {
            call.reject("params." + STATUS, "invalid", "error.task.field_status_unknown", Map.of());
            return;
        }
        String next = status.get().code();
        if (next.equals(call.values().text("statusCode"))) return;
        call.values().set("statusCode", next);
        call.values()
                .set(
                        "resolvedTime",
                        status.get().isTerminal() ? Instant.now(clock).toString() : null);
    }
}
