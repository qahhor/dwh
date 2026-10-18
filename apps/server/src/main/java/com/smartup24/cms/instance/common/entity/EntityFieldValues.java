package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityFiles.FileFacts;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.FormulaScope;
import com.smartup24.cms.instance.common.security.DataScopes;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.field.FieldParams;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Prepares the values of a save of an entity record (ADR-0032, 4.2–4.4) in the order the runtime of plan 10/10, item
 * 5.4 runs them: read-only fields the save would change are refused — read-only by the declaration or because the
 * saver lacks the field's right (ADR-0032, 5.2), and a field they may not see is refused as unknown — values are put in
 * their kept form, a new record takes the defaults of the fields it lacks, a field its condition hides keeps no value,
 * and then every field is checked — by its rules ({@link EntityValidator}) and against the database: an
 * enumeration's item exists in its reference entity and, when the value changes, is not archived (ADR-0032, 5.4), a
 * file may be put in the field and has an allowed type and size. All problems answer one 422.
 */
@Component
public class EntityFieldValues {

    /** The field error of a new value that is an archived item (ADR-0032, 5.4). */
    public static final String ARCHIVED = "archived";

    private final EntityEnums enums;
    private final @Nullable EntityFiles files;
    private final @Nullable DataScopes scopes;
    private final JdbcClient jdbc;
    private final Clock clock;
    private @Nullable LiveEntities live;

    @Autowired
    public EntityFieldValues(
            EntityEnums enums,
            ObjectProvider<EntityFiles> files,
            ObjectProvider<DataScopes> scopes,
            JdbcClient jdbc,
            ObjectProvider<LiveEntities> live) {
        this(enums, files.getIfAvailable(), scopes.getIfAvailable(), jdbc, Clock.systemUTC());
        this.live = live.getIfAvailable();
    }

    /** Values without org units: a {@code currentOrgUnit()} default stays empty. */
    public EntityFieldValues(EntityEnums enums, @Nullable EntityFiles files, JdbcClient jdbc, Clock clock) {
        this(enums, files, null, jdbc, clock);
    }

    public EntityFieldValues(
            EntityEnums enums, @Nullable EntityFiles files, @Nullable DataScopes scopes, JdbcClient jdbc, Clock clock) {
        this.enums = enums;
        this.files = files;
        this.scopes = scopes;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * The values to write, by field key.
     *
     * @param values   the values the client sent
     * @param current  the record's values before the save, or null when the save creates it
     * @param recordId the record, or null when the save creates it
     * @param userId   who saves
     * @throws ApiException 422 with every problem addressed to its field
     */
    public Map<String, Object> prepare(
            EntityDefinition entity,
            Map<String, ?> values,
            @Nullable Map<String, ?> current,
            @Nullable Long recordId,
            long userId) {
        Prepared prepared = prepareAll(entity, values, current, recordId, userId);
        if (!prepared.errors().isEmpty()) {
            throw ApiException.validation("error.common.record_fields_invalid", prepared.errors());
        }
        return prepared.values();
    }

    /**
     * The values to write and every problem found, without refusing: the runtime adds the problems of the body, the
     * references and the rules and answers them all in one 422 (ADR-0032, 6.3, step 8).
     *
     * @param values the values to write by field key; complete only when {@code errors} is empty
     * @param errors the problems, each addressed to its field
     */
    public record Prepared(Map<String, Object> values, List<FieldErrorItem> errors) {
        public Prepared {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            errors = List.copyOf(errors);
        }
    }

    /** {@link #prepare} without refusing: the values and the problems. */
    public Prepared prepareAll(
            EntityDefinition entity,
            Map<String, ?> values,
            @Nullable Map<String, ?> current,
            @Nullable Long recordId,
            long userId) {
        return prepareAll(entity, values, current, recordId, userId, record -> Map.of());
    }

    /**
     * {@link #prepare} without refusing, with the rows of the document prepared in its order (ADR-0043): after the
     * entered values and the defaults, before the computed fields — a row's formula reads the header's entered fields,
     * a computed field of the header reads the rows.
     *
     * @param rows prepares the rows the save sends over the record as it is then and gives them by collection key
     */
    public Prepared prepareAll(
            EntityDefinition entity,
            Map<String, ?> values,
            @Nullable Map<String, ?> current,
            @Nullable Long recordId,
            long userId,
            Function<Map<String, Object>, Map<String, Object>> rows) {
        boolean creating = current == null;
        Map<String, ?> before = current == null ? Map.of() : current;
        EntityLogicRun logic = logic(entity);
        Map<String, ?> sent = logic == null ? values : logic.unsent(values);
        List<FieldErrorItem> errors = writeProblems(entity, sent, current, before, creating);
        Set<String> unwritable = new HashSet<>(EntityFieldRights.hidden(entity));
        unwritable.addAll(EntityFieldRights.readonly(entity));
        if (logic != null && !creating) unwritable.addAll(logic.readonlyProblems(sent, before, errors));
        Map<String, Object> prepared = new LinkedHashMap<>();
        for (FormField field : entity.fields()) {
            if (field.attribute() != null || !sent.containsKey(field.key())) continue;
            var readonly = field.flags().readonly();
            if ((readonly != null && readonly.applies(creating, before)) || unwritable.contains(field.key())) continue;
            Object value = sent.get(field.key());
            prepared.put(field.key(), value == null ? null : FieldValueRules.normalize(field, value));
        }
        if (creating) defaults(entity, prepared, logic, userId, errors);
        Map<String, Object> record = new LinkedHashMap<>(before);
        record.putAll(prepared);
        record.putAll(rows.apply(record));
        Set<String> hidden = shown(entity, logic, prepared, record, errors);
        Set<String> required = logic == null ? Set.of() : logic.required(record, errors);
        errors.addAll(EntityValidator.problems(entity, record, false, hidden, required));
        if (errors.isEmpty()) {
            errors.addAll(lookupProblems(entity, prepared, before, recordId, userId));
        }
        return new Prepared(prepared, errors);
    }

    /** The defaults of a new record's empty fields: the declared ones, then the default formulas (ADR-0042). */
    private void defaults(
            EntityDefinition entity,
            Map<String, Object> prepared,
            @Nullable EntityLogicRun logic,
            long userId,
            List<FieldErrorItem> errors) {
        for (FormField field : entity.fields()) {
            FieldDefault value = field.flags().defaultValue();
            if (value != null && field.attribute() == null && prepared.get(field.key()) == null) {
                prepared.put(field.key(), defaultOf(entity, field, value, userId));
            }
        }
        if (logic != null) logic.defaults(prepared, new LinkedHashMap<>(prepared), errors);
    }

    /**
     * Computes the computed fields, clears the fields their conditions hide and computes again over what is left
     * (ADR-0042); the keys of the fields a formula hides.
     */
    private static Set<String> shown(
            EntityDefinition entity,
            @Nullable EntityLogicRun logic,
            Map<String, Object> prepared,
            Map<String, Object> record,
            List<FieldErrorItem> errors) {
        if (logic != null) logic.compute(prepared, record, errors);
        Set<String> hidden = logic == null ? Set.of() : logic.hidden(record, errors);
        for (FormField field : entity.fields()) {
            if (field.attribute() == null
                    && !field.computed()
                    && (!EntityValidator.visible(field, record) || hidden.contains(field.key()))) {
                prepared.put(field.key(), null);
                record.put(field.key(), null);
            }
        }
        if (logic != null && !hidden.isEmpty()) logic.compute(prepared, record, errors);
        return hidden;
    }

    /**
     * The problems of a record as the save writes it, by the field rules — the values a hook changed pass them too —
     * with the fields its form logic hides left out and the fields it makes required checked as required (ADR-0042).
     */
    public List<FieldErrorItem> recordProblems(EntityDefinition entity, Map<String, ?> record) {
        EntityLogicRun logic = logic(entity);
        if (logic == null) return EntityValidator.problems(entity, record, false);
        List<FieldErrorItem> errors = new ArrayList<>();
        Set<String> hidden = logic.hidden(record, errors);
        Set<String> required = logic.required(record, errors);
        errors.addAll(EntityValidator.problems(entity, record, false, hidden, required));
        return errors;
    }

    /** The form logic of the entity in the request's time zone, or null when the entity has none. */
    private @Nullable EntityLogicRun logic(EntityDefinition entity) {
        LiveEntities served = live;
        if (served == null) return null;
        EntityLogic logic = served.logic(entity.code());
        return logic.empty() ? null : new EntityLogicRun(entity, logic, FormulaScope.current(clock));
    }

    /**
     * What the save may not write: a changed value of a field read-only by its declaration, then — once per field —
     * the field rights of the saver: {@code unknown_field} for a field they may not see, {@code readonly} for a
     * changed value of one they may not change.
     */
    private static List<FieldErrorItem> writeProblems(
            EntityDefinition entity,
            Map<String, ?> values,
            @Nullable Map<String, ?> current,
            Map<String, ?> before,
            boolean creating) {
        List<FieldErrorItem> errors =
                new ArrayList<>(EntityValidator.readonlyProblems(entity, values, before, creating));
        Set<String> refused = new HashSet<>();
        errors.forEach(error -> refused.add(error.field()));
        for (FieldErrorItem error : EntityFieldRights.writeProblems(entity, values, current)) {
            if (refused.add(error.field())) errors.add(error);
        }
        return errors;
    }

    /**
     * The problems of the values that need the database: an item of an enumeration that exists and is in use, a file the
     * saver may attach with its type and size; a value equal to the record's {@code before} is kept. Also run on the
     * values a hook or an action's handler changed (ADR-0032, 6.5).
     */
    public List<FieldErrorItem> lookupProblems(
            EntityDefinition entity,
            Map<String, Object> prepared,
            Map<String, ?> before,
            @Nullable Long recordId,
            long userId) {
        List<FieldErrorItem> errors = new ArrayList<>();
        for (FormField field : entity.fields()) {
            Object value = prepared.get(field.key());
            if (value == null || FieldValueRules.same(field, value, before.get(field.key()))) continue;
            if (field.type() == FieldType.ENUM) {
                String reference = Objects.requireNonNull(field.params().enumeration());
                EntityEnums.Items items = enums.all(reference);
                String code = String.valueOf(value);
                if (!items.names().containsKey(code)) {
                    errors.add(
                            FieldErrorItem.keyed(field.key(), EntityValidator.INVALID, "error.field.option_required"));
                } else if (items.archived().contains(code)) {
                    errors.add(FieldErrorItem.keyed(field.key(), ARCHIVED, "error.field.ref_archived"));
                }
            } else if (field.type() == FieldType.FILE || field.type() == FieldType.IMAGE) {
                // A file the record already holds stays; each new one is checked (ADR-0043: several files).
                List<UUID> kept = EntityFileValues.checked(before.get(field.key()));
                for (UUID id : EntityFileValues.checked(value)) {
                    if (kept.contains(id)) continue;
                    Optional<FieldErrorItem> problem = fileProblem(entity, field, id, recordId, userId);
                    if (problem.isPresent()) {
                        errors.add(problem.get());
                        break;
                    }
                }
            }
        }
        return errors;
    }

    private Optional<FieldErrorItem> fileProblem(
            EntityDefinition entity, FormField field, UUID id, @Nullable Long recordId, long userId) {
        Optional<FileFacts> file = files == null
                ? Optional.empty()
                : files.attachable(id, entity.code(), recordId, userId, EntityFieldRights.visibleFiles(entity));
        if (file.isEmpty()) {
            return Optional.of(FieldErrorItem.keyed(field.key(), "not_found", "error.field.file_not_found"));
        }
        FieldParams params = field.params();
        if (!params.contentTypes().isEmpty()
                && !params.contentTypes().contains(file.get().contentType())) {
            return Optional.of(FieldErrorItem.keyed(
                    field.key(),
                    EntityValidator.INVALID,
                    "error.field.file_type_not_allowed",
                    Map.of("allowed", String.join(", ", params.contentTypes()))));
        }
        if (params.maxBytes() != null && file.get().size() > params.maxBytes()) {
            return Optional.of(FieldErrorItem.keyed(
                    field.key(),
                    FieldValueRules.TOO_LARGE,
                    "error.field.file_too_large",
                    Map.of("max", params.maxBytes())));
        }
        return Optional.empty();
    }

    /** A default's value; null when there is none to give — a user without a home unit. */
    private @Nullable Object defaultOf(EntityDefinition entity, FormField field, FieldDefault value, long userId) {
        return switch (value) {
            case FieldDefault.Fixed fixed -> fixedValue(field, fixed.value());
            case FieldDefault.Now _ -> clock.instant().toString();
            case FieldDefault.Today _ ->
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString();
            case FieldDefault.CurrentUser _ -> userId;
            case FieldDefault.CurrentOrgUnit _ ->
                scopes == null ? null : scopes.homeUnit(userId).orElse(null);
            case FieldDefault.Sequence sequence -> sequence.format(next(entity, sequence.name()));
        };
    }

    /** A fixed default as the field takes it: yes/no as a flag, the rest as written. */
    private static Object fixedValue(FormField field, String value) {
        return field.type() == FieldType.BOOLEAN ? Boolean.valueOf(value) : value;
    }

    private long next(EntityDefinition entity, String sequence) {
        Long number = jdbc.sql("select nextval(cast(:sequence as regclass))")
                .param("sequence", sequence)
                .query(Long.class)
                .single();
        return Objects.requireNonNull(number, entity.code() + ": " + sequence);
    }
}
