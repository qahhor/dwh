package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.instance.common.entity.field.EntityFields.bool;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.file;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.instant;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.number;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.select;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.sortable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.common.entity.field.FieldCondition;
import com.smartup24.cms.instance.common.entity.field.FieldDefault;
import com.smartup24.cms.instance.common.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 5.2 (ADR-0032, 4.3–4.4) without a database: the defaults of the moment, of the person and of a
 * fixed flag, a condition that may only be changed while a draft, and a file field when no files module answers.
 */
class EntityFieldValuesTest {

    private static final Instant NOW = Instant.parse("2026-10-01T09:30:00Z");

    private static final EntityDefinition ORDERS = Entity.define("test.orders", "test_orders")
            .table("test_orders", "o")
            .field(text("title", "l").column("title").required().list(sortable()))
            .field(select("status", "l", List.of("draft", "posted"), null).column("status"))
            .field(number("amount", "l").column("amount").readonlyWhen(FieldCondition.eq("status", "posted")))
            .field(instant("startsAt", "l").column("starts_at").defaultValue(FieldDefault.now()))
            .field(number("ownerId", "l").column("owner_id").defaultValue(FieldDefault.currentUser()))
            .field(bool("active", "l").column("active").defaultValue(FieldDefault.fixed("true")))
            .field(file("act", "l").column("act_id"))
            .section("main", "entity.section.main", "title", "status", "amount", "startsAt", "ownerId", "active", "act")
            .defaultSort("title", Entity.Sort.ASC)
            .build();

    private final EntityFieldValues values = new EntityFieldValues(
            mock(EntityEnums.class), null, mock(JdbcClient.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aNewRecordTakesTheMomentThePersonAndAFixedFlag() {
        Map<String, Object> prepared = values.prepare(ORDERS, Map.of("title", "Order"), null, null, 42L);

        assertThat(prepared)
                .containsEntry("startsAt", NOW.toString())
                .containsEntry("ownerId", 42L)
                .containsEntry("active", true);
    }

    @Test
    void aFieldIsLockedOnlyWhileItsConditionHolds() {
        Map<String, Object> posted = Map.of("title", "Order", "status", "posted", "amount", "10");
        Map<String, Object> draft = Map.of("title", "Order", "status", "draft", "amount", "10");

        assertThatThrownBy(() -> values.prepare(ORDERS, Map.of("amount", "11"), posted, 1L, 42L))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(Objects.requireNonNull(e.getFieldErrors()))
                                .extracting(error -> error.field() + ":" + error.code())
                                .containsExactly("amount:readonly"));
        assertThat(values.prepare(ORDERS, Map.of("amount", "11"), draft, 1L, 42L))
                .containsEntry("amount", "11");
    }

    @Test
    void aFileIsNotFoundWithoutTheFilesModule() {
        assertThatThrownBy(() -> values.prepare(
                        ORDERS,
                        Map.of("title", "Order", "act", "6f1c2a52-6b0e-4d3e-9a51-1f2d3c4b5a69"),
                        null,
                        null,
                        42L))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(Objects.requireNonNull(e.getFieldErrors()))
                                .extracting(error -> error.field() + ":" + error.code())
                                .containsExactly("act:not_found"));
    }
}
