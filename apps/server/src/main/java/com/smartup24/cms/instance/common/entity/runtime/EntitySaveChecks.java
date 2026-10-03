package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityAttributes;
import com.smartup24.cms.instance.common.entity.EntityFieldValues;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.FieldValueRules;
import com.smartup24.cms.instance.common.entity.store.EntityStoreRepository;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.hook.EntityRule;
import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import com.smartup24.cms.platform.api.entity.hook.RuleErrors;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The checks of step 8 of ADR-0032, 6.3 that the field rules cannot make alone: a reference names a row of its target
 * entity that the saver sees in that entity's scope and, when the value changes, one in use (ADR-0032, 4.2, 5.1 and
 * 5.4) — one statement per field; the unit of an org-unit record lies in the saver's scope; the cross-field rules of the
 * declaration (ADR-0032, 6.6); the custom field values (ADR-0019, 2.3). Each returns its problems, so the runtime
 * answers them with the problems of the fields in one 422.
 */
@Component
public class EntitySaveChecks {

    /** The field error of a reference to a row that does not exist or that the saver does not see. */
    public static final String NOT_FOUND = "not_found";

    private final EntityRegistry registry;
    private final EntityStoreRepository store;
    private final EntityScopes scopes;
    private final @Nullable EntityAttributes attributes;

    public EntitySaveChecks(
            EntityRegistry registry,
            EntityStoreRepository store,
            EntityScopes scopes,
            ObjectProvider<EntityAttributes> attributes) {
        this.registry = registry;
        this.store = store;
        this.scopes = scopes;
        this.attributes = attributes.getIfAvailable();
    }

    /**
     * The problems of the references a save changes: a key the saver does not see in the target's scope — or whose
     * target they may not view — is {@code not_found}, the same answer as a missing one; a new key naming an archived
     * row is {@code archived}. An unchanged old value is kept.
     */
    public List<FieldErrorItem> references(
            EntityDefinition entity, Map<String, ?> values, Map<String, ?> before, long userId) {
        List<FieldErrorItem> errors = new ArrayList<>();
        for (EntityField field : model(entity).fields()) {
            String target = field.options().target();
            FormField form = field.formField();
            if (target == null || form == null || !values.containsKey(field.key())) continue;
            Object value = values.get(field.key());
            if (value == null || FieldValueRules.same(form, value, before.get(field.key()))) continue;
            Set<Long> keys = keys(value);
            keys.removeAll(keys(before.get(field.key())));
            if (keys.isEmpty()) continue;
            problem(field.key(), target, keys, userId).ifPresent(errors::add);
        }
        return errors;
    }

    private Optional<FieldErrorItem> problem(String key, String target, Set<Long> keys, long userId) {
        EntityDefinition named = registry.find(target).orElseThrow();
        if (keys.contains(-1L) || !SecurityContext.hasPermission(named.form(), "view")) {
            return Optional.of(FieldErrorItem.keyed(key, NOT_FOUND, "error.field.ref_not_found"));
        }
        Map<Long, Boolean> rows = store.visibleRows(named, keys, scopes.rows(named, userId));
        if (!rows.keySet().containsAll(keys)) {
            return Optional.of(FieldErrorItem.keyed(key, NOT_FOUND, "error.field.ref_not_found"));
        }
        if (rows.containsValue(true)) {
            return Optional.of(FieldErrorItem.keyed(key, EntityFieldValues.ARCHIVED, "error.field.ref_archived"));
        }
        return Optional.empty();
    }

    /** The keys of a reference value; a value that is no key reads as {@code -1}, which no row has. */
    private static Set<Long> keys(@Nullable Object value) {
        Set<Long> keys = new LinkedHashSet<>();
        if (value == null) return keys;
        for (Object item : value instanceof List<?> list ? list : List.of(value)) {
            if (item instanceof Number number) {
                keys.add(number.longValue());
            } else {
                String text = String.valueOf(item).strip();
                keys.add(!text.isEmpty() && text.chars().allMatch(Character::isDigit) ? Long.parseLong(text) : -1L);
            }
        }
        return keys;
    }

    /** The problem of an org-unit record put in a unit outside the saver's scope (ADR-0032, 5.1), or none. */
    public List<FieldErrorItem> unit(
            EntityDefinition entity, Map<String, ?> values, Map<String, ?> before, long userId) {
        EntityModel model = model(entity);
        if (!(model.scope() instanceof EntityScope.OrgUnit unit)) return List.of();
        for (EntityField field : model.fields()) {
            if (field.source() instanceof FieldSource.Column column
                    && column.name().equals(unit.orgUnitColumn())
                    && values.get(field.key()) instanceof Number id
                    && !Objects.equals(String.valueOf(id), String.valueOf(before.get(field.key())))) {
                return scopes.unitProblem(entity, field.key(), userId, id.longValue()).stream()
                        .toList();
            }
        }
        return List.of();
    }

    /** The problems of the declaration's cross-field rules, in the order they were declared (ADR-0032, 6.6). */
    public List<FieldErrorItem> rules(EntityDefinition entity, EntityValues record, @Nullable EntityValues before) {
        RuleErrors errors = new RuleErrors();
        for (EntityRule rule : model(entity).rules().values()) {
            rule.check(record, before, errors);
        }
        return problems(errors);
    }

    /** The problems a rule or a hook reported, as the field errors of the answer (ADR-0021, ADR-0033 3.2). */
    public static List<FieldErrorItem> problems(RuleErrors errors) {
        return errors.items().stream()
                .map(problem ->
                        FieldErrorItem.keyed(problem.field(), problem.code(), problem.messageKey(), problem.params()))
                .toList();
    }

    /**
     * The custom field values as they are kept, or their problems (addressed {@code attributes.<code>}): an entity
     * without custom fields keeps the values it is sent.
     */
    public Checked attributes(EntityDefinition entity, @Nullable Map<String, Object> sent) {
        EntityAttributes checker = attributes;
        String type = entity.customEntity();
        if (sent == null || checker == null || type == null) return new Checked(sent, List.of());
        try {
            return new Checked(checker.checked(type, sent), List.of());
        } catch (ApiException refused) {
            List<FieldErrorItem> problems = refused.getFieldErrors();
            if (problems == null || problems.isEmpty()) throw refused;
            return new Checked(sent, problems);
        }
    }

    /** The custom field values to keep and the problems found in them. */
    public record Checked(@Nullable Map<String, Object> attributes, List<FieldErrorItem> errors) {}

    private static EntityModel model(EntityDefinition entity) {
        return Objects.requireNonNull(entity.model(), entity.code());
    }
}
