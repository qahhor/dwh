package com.smartup24.cms.instance.warehouse.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.smartup24.cms.instance.common.actor.ActorError;
import com.smartup24.cms.instance.common.actor.AuditActorContext;
import com.smartup24.cms.instance.common.error.ConstraintViolationException;
import com.smartup24.cms.platform.api.actor.AuditActor;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The rules the load ledger checks before it touches the database (plan 10/10, item 4.2). */
class WarehouseLoadServiceRulesTest {

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final AuditActorContext actors = mock(AuditActorContext.class);
    private final WarehouseLoadService loads = new WarehouseLoadService(jdbc, actors);

    @Test
    @DisplayName("4.2: a load is not failed without a reason")
    void failNeedsAReason() {
        assertThatThrownBy(() -> loads.fail(1, "  ", AuditActor.user(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loads.fail(1, null, AuditActor.user(1))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, actors);
    }

    @Test
    @DisplayName("4.2: a log row without an actor is refused with the audit trigger's code")
    void logNeedsAnActor() {
        assertThatThrownBy(() -> loads.log(UUID.randomUUID(), "uploaded", null, "pending", null, null, null))
                .isInstanceOf(ConstraintViolationException.class)
                .satisfies(e -> assertThat(((ConstraintViolationException) e).code())
                        .isEqualTo(ActorError.AUDIT_ACTOR_MISSING));
        verifyNoInteractions(jdbc, actors);
    }
}
