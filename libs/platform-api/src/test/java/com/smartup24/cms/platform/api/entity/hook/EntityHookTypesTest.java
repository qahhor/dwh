package com.smartup24.cms.platform.api.entity.hook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.platform.api.actor.AuditActor;
import com.smartup24.cms.platform.api.entity.EntityDeclarationTestAccess;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.event.EntityChanged;
import com.smartup24.cms.platform.api.entity.event.EntityEventType;
import com.smartup24.cms.platform.api.entity.field.FieldCondition;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a hook sees and reports, with the API alone (ADR-0032, 6.5–6.9; ADR-0033, 3.2). */
class EntityHookTypesTest {

    private static final EntityDefinition ORDERS = EntityDeclarationTestAccess.orders();

    @Test
    void valuesReadTypedAndChangeOnlyThroughTheCheck() {
        EntityValues before = EntityValues.readOnly(
                ORDERS,
                Map.of(
                        "customer",
                        "Ann",
                        "orderDate",
                        "2026-10-03",
                        "total",
                        Map.of("amount", "12.50", "currency", "UZS"),
                        "orgUnitId",
                        7,
                        "lines",
                        List.of(Map.of("id", 1, "product", "Flour"))));
        assertThat(before.has("customer")).isTrue();
        assertThat(before.text("customer")).isEqualTo("Ann");
        assertThat(before.date("orderDate")).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(before.decimal("total")).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(before.money("total")).containsEntry("currency", "UZS");
        assertThat(before.ref("orgUnitId")).isEqualTo(7L);
        assertThat(before.collection("lines"))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("product", "Flour"));
        assertThat(before.refs("nothing")).isEmpty();
        assertThat(before.bool("nothing")).isNull();
        assertThatThrownBy(() -> before.set("customer", "Bob")).isInstanceOf(UnsupportedOperationException.class);

        EntityValues save = EntityValues.writable(
                ORDERS, Map.of("customer", "Ann"), (entity, key, value) -> "refused".equals(value));
        save.set("customer", "Bob");
        assertThat(save.asMap()).containsEntry("customer", "Bob");
        assertThatThrownBy(() -> save.set("customer", "refused")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> save.set("total", "1")).as("computed").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> save.set("nothing", "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rulesReportProblemsByField() {
        RuleErrors errors = new RuleErrors();
        Rules.notBefore("endsOn", "startsOn")
                .check(
                        EntityValues.readOnly(ORDERS, Map.of("startsOn", "2026-10-03", "endsOn", "2026-10-01")),
                        null,
                        errors);
        Rules.requiredIf("comment", FieldCondition.eq("customer", "Ann"))
                .check(EntityValues.readOnly(ORDERS, Map.of("customer", "Ann")), null, errors);
        Rules.atLeastOne("customer", "comment").check(EntityValues.readOnly(ORDERS, Map.of()), null, errors);
        errors.record("conflict", "error.acme.record");

        assertThat(errors.isEmpty()).isFalse();
        assertThat(errors.items())
                .extracting(RuleErrors.Problem::field)
                .containsExactly("endsOn", "comment", RuleErrors.RECORD, RuleErrors.RECORD);
        assertThatThrownBy(() -> new RuleErrors.Problem("x", null, "k", Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aRefusalNamesItsKindAndTheKeyOfItsText() {
        EntityRefusal conflict = EntityRefusal.conflict("error.acme.in_use", Map.of("id", 3));
        assertThat(conflict.kind()).isEqualTo(EntityRefusal.Kind.CONFLICT);
        assertThat(conflict.messageKey()).isEqualTo("error.acme.in_use");
        assertThat(conflict.params()).containsEntry("id", 3);
        assertThat(conflict.getStackTrace())
                .as("a refusal is no failure to trace")
                .isEmpty();
        assertThat(EntityRefusal.forbidden("error.acme.closed").kind()).isEqualTo(EntityRefusal.Kind.FORBIDDEN);
        assertThat(EntityRefusal.unprocessable("error.acme.whole", Map.of()).kind())
                .isEqualTo(EntityRefusal.Kind.UNPROCESSABLE);
        assertThatThrownBy(() -> EntityRefusal.forbidden("A sentence, not a key"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hookRecordsAndEventsCarryWhatTheRuntimeKnows() {
        EntityValues before = EntityValues.readOnly(ORDERS, Map.of());
        AuditActor actor = AuditActor.user(5L);
        assertThat(actor.name()).isEqualTo("5");
        assertThatThrownBy(() -> new AuditActor(0, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new EntityArchive(ORDERS, 1L, before, true, actor).archived())
                .isTrue();
        assertThat(new EntityDelete(ORDERS, 1L, before, actor).id()).isEqualTo(1L);

        EntityChanged posted = new EntityChanged(
                "acme.orders",
                "acme.orders",
                1L,
                3L,
                EntityEventType.ACTION,
                "post",
                List.of("status"),
                5L,
                Instant.EPOCH,
                UUID.randomUUID());
        assertThat(posted.eventName()).isEqualTo("acme.orders.post");
        EntityChanged updated = new EntityChanged(
                "acme.orders",
                "acme.orders",
                1L,
                2L,
                EntityEventType.UPDATED,
                null,
                List.of(),
                null,
                Instant.EPOCH,
                UUID.randomUUID());
        assertThat(updated.eventName()).isEqualTo("acme.orders." + EntityEventType.UPDATED.wire());
        assertThatThrownBy(() -> new EntityChanged(
                        "acme.orders",
                        "acme.orders",
                        1L,
                        2L,
                        EntityEventType.UPDATED,
                        "post",
                        List.of(),
                        null,
                        Instant.EPOCH,
                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        EntityHooks nothing = () -> "acme.orders";
        nothing.beforeSave(null);
        assertThat(nothing.entity()).isEqualTo("acme.orders");
    }
}
