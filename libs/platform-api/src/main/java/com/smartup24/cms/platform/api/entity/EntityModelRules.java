package com.smartup24.cms.platform.api.entity;

import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldCondition;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.field.FieldReadonly;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.field.FormPart;
import com.smartup24.cms.platform.api.entity.workflow.EntityState;
import com.smartup24.cms.platform.api.entity.workflow.EntityWorkflow;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The rules across an entity's fields (ADR-0032, 4.4): a condition looks at a form field of the entity that holds one
 * value picked from known ones (select, enumeration, yes/no, reference), and the list fields derived from the fields
 * (the hidden currency of money) take keys no other field uses. A document's collections, process and tabs fit its
 * fields (ADR-0032, 9): money of a row takes its currency from a select of the document, the status of the process is
 * a read-only select of the states that starts in the initial one, a state locks fields and collections it has.
 */
final class EntityModelRules {

    private EntityModelRules() {}

    static void check(String table, List<EntityField> fields) {
        Map<String, EntityField> byKey =
                fields.stream().collect(Collectors.toMap(EntityField::key, Function.identity(), (a, b) -> a));
        for (EntityField field : fields) {
            FormPart form = field.form();
            if (form == null) continue;
            checkCondition(table, field, form.visibleWhen(), byKey);
            checkCondition(
                    table,
                    field,
                    form.readonly() == null ? null : form.readonly().when(),
                    byKey);
        }
        for (EntityField field : fields) {
            String currencyFrom = field.options().currencyFrom();
            if (currencyFrom == null) continue;
            if (!(field.source() instanceof FieldSource.Computed)) {
                throw new IllegalArgumentException("Table " + table + ": " + field.key()
                        + " takes its currency from a field, which only computed money and money of a line do");
            }
            currencyField(table, field, byKey.get(currencyFrom));
        }
        Set<String> listKeys = new HashSet<>();
        for (EntityField field : fields) {
            for (String listed : field.listKeys()) {
                if (!listKeys.add(listed) || (!listed.equals(field.key()) && byKey.containsKey(listed))) {
                    throw new IllegalArgumentException("Table " + table + ": the list field " + listed + " of "
                            + field.key() + " takes the key of another field");
                }
            }
        }
    }

    /**
     * The items of a reference are read whole, for anyone who fills or reads an enumeration of another entity, without
     * the viewer's scope or the reference's own right ({@code EntityEnums}, ADR-0032, 4.5): its rows are therefore
     * every viewer's, {@link EntityScope#all()}.
     */
    static void checkReference(String table, @Nullable EntityReference reference, EntityScope scope) {
        if (reference != null && scope != EntityScope.All.INSTANCE) {
            throw new IllegalArgumentException("Table " + table + ": a reference is read whole for every viewer, so its"
                    + " scope is EntityScope.all(), not " + scope.describe() + " (ADR-0032, 4.5)");
        }
    }

    /**
     * The field money takes its currency from (ADR-0032, 9.1): a select of a column of the record whose every option is
     * a currency the money allows.
     */
    static void currencyField(String table, EntityField money, @Nullable EntityField currency) {
        if (currency == null
                || currency.type() != FieldType.SELECT
                || !(currency.source() instanceof FieldSource.Column)
                || !money.options().currencies().containsAll(currency.options().options())) {
            throw new IllegalArgumentException("Table " + table + ": " + money.key() + " takes its currency from "
                    + money.options().currencyFrom() + ", which is no select column of currencies it allows");
        }
    }

    /** The collections, the process and the tabs of a document against its fields (ADR-0032, 9). */
    static void checkDocument(
            String table,
            List<EntityField> fields,
            List<EntityCollection> collections,
            @Nullable EntityWorkflow workflow,
            List<EntityTab> tabs) {
        Map<String, EntityField> byKey =
                fields.stream().collect(Collectors.toMap(EntityField::key, Function.identity(), (a, b) -> a));
        Set<String> collectionKeys = new HashSet<>();
        for (EntityCollection collection : collections) {
            collectionKeys.add(collection.key());
            for (EntityField row : collection.fields()) {
                if (row.options().currencyFrom() != null) {
                    currencyField(table, row, byKey.get(row.options().currencyFrom()));
                }
            }
        }
        if (workflow != null) {
            checkWorkflow(table, workflow, byKey, collectionKeys);
        }
        Set<String> tabKeys = new HashSet<>();
        for (EntityTab tab : tabs) {
            if (!tabKeys.add(tab.key())) {
                throw new IllegalArgumentException("Table " + table + ": duplicate tab " + tab.key());
            }
            if (tab.kind() == EntityTab.Kind.COLLECTION && !collectionKeys.contains(tab.collection())) {
                throw new IllegalArgumentException(
                        "Table " + table + ": the tab " + tab.key() + " shows an unknown collection");
            }
        }
    }

    private static void checkWorkflow(
            String table, EntityWorkflow workflow, Map<String, EntityField> byKey, Set<String> collectionKeys) {
        EntityField status = byKey.get(workflow.field());
        Set<String> states = workflow.states().stream().map(EntityState::code).collect(Collectors.toUnmodifiableSet());
        FormPart form = status == null ? null : status.form();
        boolean fits = status != null
                && form != null
                && status.type() == FieldType.SELECT
                && status.source() instanceof FieldSource.Column
                && Set.copyOf(status.options().options()).equals(states)
                && form.readonly() != null
                && form.readonly().mode() == FieldReadonly.Mode.ALWAYS
                && form.defaultValue() instanceof FieldDefault.Fixed fixed
                && fixed.value().equals(workflow.initial().code());
        if (!fits) {
            throw new IllegalArgumentException("Table " + table + ": the status " + workflow.field()
                    + " of the process is a read-only select column of its states that starts in the initial one");
        }
        for (EntityState state : workflow.states()) {
            for (String locked : state.locks()) {
                EntityField field = byKey.get(locked);
                if ((field == null || field.form() == null) && !collectionKeys.contains(locked)) {
                    throw new IllegalArgumentException("Table " + table + ": the state " + state.code() + " locks "
                            + locked + ", which is no field of the form or collection");
                }
            }
        }
    }

    private static void checkCondition(
            String table, EntityField field, @Nullable FieldCondition condition, Map<String, EntityField> byKey) {
        if (condition == null) return;
        for (String key : condition.fields()) {
            EntityField tested = byKey.get(key);
            if (tested == null || tested.form() == null || !FieldCondition.TESTED_TYPES.contains(tested.type())) {
                throw new IllegalArgumentException("Table " + table + ": the condition of " + field.key() + " looks at "
                        + key + ", which is no select, enumeration, yes/no or reference of the form");
            }
        }
    }
}
