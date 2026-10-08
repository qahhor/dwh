package com.smartup24.cms.instance.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.example.service.ExampleRequestsEntity;
import com.smartup24.cms.instance.example.service.ExampleRequestsHooks;
import com.smartup24.cms.platform.api.actor.AuditActor;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.hook.EntityOperation;
import com.smartup24.cms.platform.api.entity.hook.EntitySave;
import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The hooks of the reference requests with the API's own types and no platform behind them (ADR-0033, 3.2). */
class ExampleRequestsHooksTest {

    private static final EntityDefinition REQUESTS = ExampleRequestsEntity.DEFINITION;

    private final ExampleRequestsHooks hooks =
            new ExampleRequestsHooks(Clock.fixed(Instant.parse("2026-10-03T09:15:00Z"), ZoneOffset.UTC));

    @Test
    void aDecisionStampsItsMomentAndOtherSavesDoNot() {
        assertThat(hooks.entity()).isEqualTo(ExampleRequestsEntity.CODE);
        EntityValues approved = save(EntityOperation.ACTION, "approve");
        assertThat(approved.text("decidedAt")).isEqualTo("2026-10-03T09:15Z");
        assertThat(save(EntityOperation.ACTION, "reject").text("decidedAt")).isEqualTo("2026-10-03T09:15Z");
        assertThat(save(EntityOperation.ACTION, "submit").has("decidedAt")).isFalse();
        assertThat(save(EntityOperation.UPDATE, null).has("decidedAt")).isFalse();
    }

    /** Runs {@code beforeSave} on a save of the operation and returns the values it would write. */
    private EntityValues save(EntityOperation operation, @Nullable String action) {
        EntityValues values =
                EntityValues.writable(REQUESTS, new HashMap<>(Map.of("status", "submitted")), (e, k, v) -> false);
        hooks.beforeSave(new EntitySave() {
            @Override
            public EntityDefinition entity() {
                return REQUESTS;
            }

            @Override
            public EntityOperation operation() {
                return operation;
            }

            @Override
            public Long id() {
                return 7L;
            }

            @Override
            public @Nullable EntityValues before() {
                return null;
            }

            @Override
            public EntityValues values() {
                return values;
            }

            @Override
            public @Nullable String action() {
                return action;
            }

            @Override
            public AuditActor actor() {
                return AuditActor.user(1L);
            }

            @Override
            public boolean changed(String key) {
                return false;
            }

            @Override
            public void reject(String fieldPath, String code, String messageKey, Map<String, ?> params) {
                throw new AssertionError("no problem expected");
            }
        });
        return values;
    }
}
