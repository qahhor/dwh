package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.bool;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.enumeration;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.file;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.instant;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.DataScopes;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.FieldCondition;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, items 5.2 and 5.3 (ADR-0032, 4.3–4.4, 5.1–5.2, 5.4) without a database: the defaults of the moment, of
 * the person, of their home unit and of a fixed flag, a condition that may only be changed while a draft, a field
 * read-only by the declaration or by the saver's right — one {@code readonly} error either way — a field the saver may
 * not see, an archived item of an enumeration, and a file field when no files module answers.
 */
class EntityFieldValuesTest {

    private static final Instant NOW = Instant.parse("2026-10-01T09:30:00Z");
    private static final String UNITS = "test.units";

    private static final EntityDefinition ORDERS = Entity.define("test.orders", "test_orders")
            .table("test_orders", "o")
            .scope(EntityScope.orgUnit("org_unit_id", "created_by"))
            .field(text("title", "l").column("title").required().list(sortable()))
            .field(select("status", "l", List.of("draft", "posted"), null).column("status"))
            .field(number("amount", "l").column("amount").readonlyWhen(FieldCondition.eq("status", "posted")))
            .field(instant("startsAt", "l").column("starts_at").defaultValue(FieldDefault.now()))
            .field(number("ownerId", "l").column("owner_id").defaultValue(FieldDefault.currentUser()))
            .field(number("unitId", "l").column("org_unit_id").defaultValue(FieldDefault.currentOrgUnit()))
            .field(bool("active", "l").column("active").defaultValue(FieldDefault.fixed("true")))
            .field(file("act", "l").column("act_id"))
            .field(enumeration("unit", "l", UNITS).column("unit"))
            .field(text("discount", "l")
                    .column("discount")
                    .readonlyUnless("test_orders", "discount")
                    .readonlyOnUpdate())
            .field(text("margin", "l").column("margin").requires("test_orders", "margin"))
            .section(
                    "main",
                    "entity.section.main",
                    "title",
                    "status",
                    "amount",
                    "startsAt",
                    "ownerId",
                    "unitId",
                    "active",
                    "act",
                    "unit",
                    "discount",
                    "margin")
            .defaultSort("title", Entity.Sort.ASC)
            .build();

    private static final Set<String> CLERK = Set.of("test_orders.view", "test_orders.update");

    private final EntityEnums enums = mock(EntityEnums.class);
    private final DataScopes scopes = mock(DataScopes.class);
    private final EntityFieldValues values =
            new EntityFieldValues(enums, null, scopes, mock(JdbcClient.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    private static void signIn(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                42L, "clerk", "clerk@example.test", 1L, false, permissions, 1L, false, 1L, null));
    }

    @Test
    void aNewRecordTakesTheMomentThePersonTheirUnitAndAFixedFlag() {
        when(scopes.homeUnit(42L)).thenReturn(Optional.of(5L));
        Map<String, Object> prepared = values.prepare(ORDERS, Map.of("title", "Order"), null, null, 42L);

        assertThat(prepared)
                .containsEntry("startsAt", NOW.toString())
                .containsEntry("ownerId", 42L)
                .containsEntry("unitId", 5L)
                .containsEntry("active", true);
    }

    @Test
    void aUserWithoutAHomeUnitLeavesTheUnitEmpty() {
        when(scopes.homeUnit(42L)).thenReturn(Optional.empty());

        assertThat(values.prepare(ORDERS, Map.of("title", "Order"), null, null, 42L))
                .containsEntry("unitId", null);
        EntityFieldValues withoutOrgUnits =
                new EntityFieldValues(enums, null, mock(JdbcClient.class), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(withoutOrgUnits.prepare(ORDERS, Map.of("title", "Order"), null, null, 42L))
                .containsEntry("unitId", null);
    }

    @Test
    void aFieldIsLockedOnlyWhileItsConditionHolds() {
        Map<String, Object> posted = Map.of("title", "Order", "status", "posted", "amount", "10");
        Map<String, Object> draft = Map.of("title", "Order", "status", "draft", "amount", "10");

        assertThat(errors(() -> values.prepare(ORDERS, Map.of("amount", "11"), posted, 1L, 42L)))
                .containsExactly("amount:readonly");
        assertThat(values.prepare(ORDERS, Map.of("amount", "11"), draft, 1L, 42L))
                .containsEntry("amount", "11");
    }

    @Test
    void aFieldIsReadOnlyByItsDeclarationOrByTheSaversRight() {
        Map<String, Object> current = Map.of("title", "Order", "discount", "5");

        signIn(CLERK);
        // Without the right the field is read-only on creation too; an empty value is no value.
        assertThat(errors(() -> values.prepare(ORDERS, Map.of("title", "Order", "discount", "7"), null, null, 42L)))
                .containsExactly("discount:readonly");
        assertThat(values.prepare(ORDERS, Map.of("title", "Order", "discount", ""), null, null, 42L))
                .doesNotContainKey("discount");
        // Read-only by the declaration and by the right at once: still one error.
        assertThat(errors(() -> values.prepare(ORDERS, Map.of("discount", "7"), current, 1L, 42L)))
                .containsExactly("discount:readonly");
        // The value sent back unchanged passes and is not written.
        assertThat(values.prepare(ORDERS, Map.of("discount", "5"), current, 1L, 42L))
                .doesNotContainKey("discount");

        signIn(Set.of("test_orders.view", "test_orders.update", "test_orders.discount"));
        assertThat(values.prepare(ORDERS, Map.of("title", "Order", "discount", "7"), null, null, 42L))
                .containsEntry("discount", "7");
        assertThat(errors(() -> values.prepare(ORDERS, Map.of("discount", "7"), current, 1L, 42L)))
                .as("set on creation only")
                .containsExactly("discount:readonly");
    }

    @Test
    void aFieldTheSaverMayNotSeeIsAnUnknownField() {
        signIn(CLERK);

        assertThat(errors(() -> values.prepare(ORDERS, Map.of("title", "Order", "margin", "9"), null, null, 42L)))
                .containsExactly("margin:unknown_field");

        signIn(Set.of("test_orders.view", "test_orders.update", "test_orders.margin"));
        assertThat(values.prepare(ORDERS, Map.of("title", "Order", "margin", "9"), null, null, 42L))
                .containsEntry("margin", "9");
    }

    @Test
    void anArchivedItemIsNoNewValueButAnOldOneIsKept() {
        when(enums.all(UNITS)).thenReturn(new EntityEnums.Items(Map.of("kg", "Kilogram", "lb", "Pound"), Set.of("lb")));
        Map<String, Object> current = Map.of("title", "Order", "unit", "lb");

        assertThat(errors(() -> values.prepare(ORDERS, Map.of("title", "Order", "unit", "lb"), null, null, 42L)))
                .containsExactly("unit:archived");
        assertThat(errors(() -> values.prepare(ORDERS, Map.of("title", "Order", "unit", "oz"), null, null, 42L)))
                .containsExactly("unit:invalid");
        assertThat(values.prepare(ORDERS, Map.of("unit", "lb"), current, 1L, 42L))
                .containsEntry("unit", "lb");
        assertThat(values.prepare(ORDERS, Map.of("unit", "kg"), current, 1L, 42L))
                .containsEntry("unit", "kg");
    }

    @Test
    void aFileIsNotFoundWithoutTheFilesModule() {
        assertThat(errors(() -> values.prepare(
                        ORDERS,
                        Map.of("title", "Order", "act", "6f1c2a52-6b0e-4d3e-9a51-1f2d3c4b5a69"),
                        null,
                        null,
                        42L)))
                .containsExactly("act:not_found");
    }

    private static List<String> errors(ThrowingCallable save) {
        Throwable thrown = catchThrowable(save);
        assertThat(thrown).isInstanceOf(ApiException.class);
        return Objects.requireNonNull(((ApiException) thrown).getFieldErrors()).stream()
                .map(error -> error.field() + ":" + error.code())
                .toList();
    }

    @Test
    void theItemsOfferOnlyTheActiveOnes() {
        EntityEnums.Items items = new EntityEnums.Items(Map.of("kg", "Kilogram", "lb", "Pound"), Set.of("lb"));

        assertThat(items.offered()).containsOnlyKeys("kg");
        assertThat(items.names()).containsOnlyKeys("kg", "lb");
        assertThat(EntityEnums.Items.active(Map.of("kg", "Kilogram")).offered()).containsOnlyKeys("kg");
        assertThat(EntityEnums.Items.NONE.offered()).isEmpty();
        assertThatThrownBy(() -> items.names().put("oz", "Ounce")).isInstanceOf(UnsupportedOperationException.class);
    }
}
