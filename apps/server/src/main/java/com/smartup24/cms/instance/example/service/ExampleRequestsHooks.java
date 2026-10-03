package com.smartup24.cms.instance.example.service;

import com.smartup24.cms.platform.api.entity.hook.EntityDelete;
import com.smartup24.cms.platform.api.entity.hook.EntityHooks;
import com.smartup24.cms.platform.api.entity.hook.EntityOperation;
import com.smartup24.cms.platform.api.entity.hook.EntityRefusal;
import com.smartup24.cms.platform.api.entity.hook.EntitySave;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What the declaration of the requests cannot say (ADR-0032, 6.5): a decision ({@code approve}, {@code reject}) stamps
 * the moment it was taken, and only a draft is deleted — a request that was submitted stays as the
 * trace of the process.
 */
@Component
public class ExampleRequestsHooks implements EntityHooks {

    private final Clock clock;

    @Autowired
    public ExampleRequestsHooks() {
        this(Clock.systemUTC());
    }

    /** The hooks on a clock of the caller's: a test fixes the moment a decision stamps. */
    public ExampleRequestsHooks(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String entity() {
        return ExampleRequestsEntity.CODE;
    }

    /** A transition is a save with {@link EntityOperation#ACTION} and the transition's code. */
    @Override
    public void beforeSave(EntitySave save) {
        if (save.operation() == EntityOperation.ACTION && ExampleRequestsEntity.DECISIONS.contains(save.action())) {
            save.values().set("decidedAt", OffsetDateTime.now(clock).toString());
        }
    }

    @Override
    public void beforeDelete(EntityDelete delete) {
        if (!"draft".equals(delete.before().text("status"))) {
            throw EntityRefusal.conflict("error.example.request_not_draft", Map.of());
        }
    }
}
