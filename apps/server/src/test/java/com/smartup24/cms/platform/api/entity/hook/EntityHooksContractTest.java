package com.smartup24.cms.platform.api.entity.hook;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.date;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.ref;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.EntityRecordStore;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.FieldCondition;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an entity's author writes besides the declaration (ADR-0032, 6.5–6.7), checked at the start and in use: hooks
 * and action handlers belong to declared entities with a table, once each; every action of its own has a handler; a
 * reference names a declared entity; a hook changes only written fields with values their type takes; the ready rules
 * report their problems on the right address.
 */
class EntityHooksContractTest {

    private static final Supplier<EntityRecordStore> NO_STORE = () -> null;

    private static final EntityDefinition ORDERS = Entity.define("test.orders", "notes")
            .table("test_orders", "o")
            .scope(EntityScope.all())
            .field(text("title", "t").column("title").required())
            .field(ref("noteId", "n").column("note_id").target(MsNoteEntity.CODE, "title"))
            .field(date("startsOn", "s").column("starts_on"))
            .field(date("endsOn", "e").column("ends_on"))
            .section("main", "m", "title", "noteId", "startsOn", "endsOn")
            .actions("create", "update")
            .action("post", "update")
            .defaultSort("title", Entity.Sort.ASC)
            .build();

    @Test
    @DisplayName("6.5–6.7: hooks and handlers of undeclared entities, doubles and a declared action without a handler"
            + " fail the start")
    void theStartRefusesWhatDoesNotFit() {
        List<EntityDefinition> entities = List.of(MsNoteEntity.DEFINITION, ORDERS);
        assertThat(registry(entities, List.of(), List.of(handler("test.orders", "post")))
                        .handler("test.orders", "post"))
                .isPresent();
        assertThatThrownBy(() -> registry(entities, List.of(), List.of()))
                .hasMessageContaining("post without its EntityActionHandler");
        assertThatThrownBy(
                        () -> registry(entities, List.of(hooks("test.nope")), List.of(handler("test.orders", "post"))))
                .hasMessageContaining("without a table: test.nope");
        assertThatThrownBy(() -> registry(
                        entities,
                        List.of(hooks("test.orders"), hooks("test.orders")),
                        List.of(handler("test.orders", "post"))))
                .hasMessageContaining("Duplicate hooks");
        assertThatThrownBy(() -> registry(
                        entities, List.of(), List.of(handler("test.orders", "post"), handler("test.orders", "update"))))
                .hasMessageContaining("not declared");
        assertThatThrownBy(() -> registry(List.of(ORDERS), List.of(), List.of(handler("test.orders", "post"))))
                .hasMessageContaining("refers to ms.notes, which is no entity with a table");
    }

    @Test
    @DisplayName("6.5: a hook changes a written field with a value its type takes, and nothing else")
    void aHookChangesOnlyWrittenFields() {
        EntityValues values = EntityValues.writable(ORDERS, Map.of("title", "x"), EntityValidator::refuses);
        values.set("startsOn", "2026-10-01");
        assertThat(values.date("startsOn")).hasToString("2026-10-01");
        assertThatThrownBy(() -> values.set("startsOn", "01.10.2026")).hasMessageContaining("refused by its type");
        assertThatThrownBy(() -> values.set("id", 5)).hasMessageContaining("no written field");
        assertThatThrownBy(() -> EntityValues.readOnly(ORDERS, Map.of()).set("title", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(EntityValues.readOnly(ORDERS, Map.of("noteId", "7")).ref("noteId"))
                .isEqualTo(7L);
        assertThat(EntityValues.readOnly(ORDERS, Map.of("total", Map.of("amount", "1.50")))
                        .decimal("total"))
                .isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("6.6: the ready rules report on the field, or on the record for at least one of several")
    void theReadyRulesReportTheirProblems() {
        assertThat(check(
                        Rules.notBefore("endsOn", "startsOn"),
                        Map.of("startsOn", "2026-10-02", "endsOn", "2026-10-01")))
                .containsExactly("endsOn:before_start");
        assertThat(check(Rules.notBefore("endsOn", "startsOn"), Map.of("startsOn", "bad", "endsOn", "2026-10-01")))
                .isEmpty();
        assertThat(check(Rules.requiredIf("noteId", FieldCondition.eq("title", "linked")), Map.of("title", "linked")))
                .containsExactly("noteId:required");
        assertThat(check(Rules.atLeastOne("startsOn", "endsOn"), Map.of("title", "x")))
                .containsExactly(":required");
    }

    private static List<String> check(EntityRule rule, Map<String, Object> record) {
        RuleErrors errors = new RuleErrors();
        rule.check(EntityValues.readOnly(ORDERS, record), null, errors);
        return errors.items().stream()
                .map(item -> item.field() + ":" + item.code())
                .toList();
    }

    private static EntityRegistry registry(
            List<EntityDefinition> entities, List<EntityHooks> hooks, List<EntityActionHandler> handlers) {
        return new EntityRegistry(entities, List.of(), List.of(), hooks, handlers, NO_STORE);
    }

    private static EntityHooks hooks(String entity) {
        return () -> entity;
    }

    private static EntityActionHandler handler(String entity, String action) {
        return new EntityActionHandler() {
            @Override
            public String entity() {
                return entity;
            }

            @Override
            public String action() {
                return action;
            }

            @Override
            public void run(EntityActionCall call) {}
        };
    }
}
