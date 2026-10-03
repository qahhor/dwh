package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.importing.EntityImportCells;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Column;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Option;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Result;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.Row;
import com.smartup24.cms.instance.common.entity.importing.EntityImporter.RowOutcome;
import com.smartup24.cms.instance.common.entity.store.EntityKeyLookup;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * A batch of import rows (ADR-0032, 10.1 and 6.4): one transaction for the batch and a savepoint for each row, so a
 * row's problem — a refused value, a hook's refusal, a broken constraint — undoes that row only. A row whose key names
 * a record in the importer's scope changes it from its current revision (no If-Match: ADR-0032, 19, question 7), any
 * other creates one; each needs its own right, and the steps of the save are the runtime's. A dry run rolls the whole
 * batch back, so its hooks and events run and nothing stays.
 */
@Component
public class EntityImportBatch {

    private final EntityWrites writes;
    private final EntityReads reads;
    private final EntityScopes scopes;
    private final EntityKeyLookup keys;
    private final ObjectMapper mapper;
    private final TransactionTemplate batch;
    private final TransactionTemplate row;

    public EntityImportBatch(
            EntityWrites writes,
            EntityReads reads,
            EntityScopes scopes,
            EntityKeyLookup keys,
            ObjectMapper mapper,
            PlatformTransactionManager transactions) {
        this.writes = writes;
        this.reads = reads;
        this.scopes = scopes;
        this.keys = keys;
        this.mapper = mapper;
        this.batch = new TransactionTemplate(transactions);
        this.batch.setName("entity-import");
        this.row = new TransactionTemplate(transactions);
        this.row.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    List<RowOutcome> run(
            EntityDefinition entity,
            EntityImporter.Template template,
            boolean apply,
            List<Row> rows,
            Function<String, String> text,
            Consumer<List<RowOutcome>> checkpoint) {
        Map<String, Column> columns = new LinkedHashMap<>();
        template.columns().forEach(column -> columns.put(column.key(), named(column, text)));
        List<RowOutcome> outcomes = Objects.requireNonNull(batch.execute(status -> {
            List<RowOutcome> done = new ArrayList<>();
            for (Row each : rows) {
                done.add(one(entity, template.key(), columns, each));
            }
            if (apply) {
                checkpoint.accept(done);
            } else {
                status.setRollbackOnly();
            }
            return done;
        }));
        if (!apply) checkpoint.accept(outcomes);
        return outcomes;
    }

    /** The column with the names of its choices in the import's language, so a cell may give a name. */
    private static Column named(Column column, Function<String, String> text) {
        List<Option> options = column.options().stream()
                .map(option -> option.label() == null && option.labelKey() != null
                        ? new Option(option.code(), option.labelKey(), text.apply(option.labelKey()))
                        : option)
                .toList();
        return new Column(column.key(), column.labelKey(), column.label(), column.type(), column.required(), options);
    }

    private RowOutcome one(EntityDefinition entity, String key, Map<String, Column> columns, Row each) {
        String at = EntityImporter.rowAddress(each.number());
        EntityModel model = Objects.requireNonNull(entity.model(), entity.code());
        Map<String, Object> values = new LinkedHashMap<>();
        List<FieldErrorItem> errors = new ArrayList<>();
        each.cells().forEach((field, raw) -> {
            Column column = columns.get(field);
            Optional<EntityField> declared = model.field(field);
            if (column == null || declared.isEmpty()) {
                errors.add(
                        FieldErrorItem.keyed(at + "." + field, EntityFieldRights.UNKNOWN_FIELD, "error.field.unknown"));
                return;
            }
            EntityImportCells.Cell cell =
                    EntityImportCells.read(column, declared.get().options(), raw);
            FieldErrorItem problem = cell.problem();
            if (problem != null) {
                errors.add(problem.at(at + "." + problem.field()));
            } else {
                values.put(field, cell.value());
            }
        });
        if (!errors.isEmpty()) return failed(each, errors);
        @Nullable String found = values.get(key) instanceof String text && !text.isBlank() ? text : null;
        try {
            return Objects.requireNonNull(row.execute(status -> write(entity, each, at, found, values)));
        } catch (ApiException refused) {
            return failed(each, problems(at, refused));
        } catch (DataIntegrityViolationException broken) {
            String code = broken instanceof DuplicateKeyException ? "already_exists" : "conflict";
            return failed(each, List.of(FieldErrorItem.keyed(at, code, "error.integrity_violation")));
        }
    }

    private RowOutcome write(
            EntityDefinition entity, Row each, String at, @Nullable String key, Map<String, Object> values) {
        Optional<Long> found = key == null
                ? Optional.empty()
                : keys.find(entity, reads.list(entity), key, scopes.rows(entity, EntityReads.userId()));
        if (found.isPresent()) {
            if (!EntityImports.allowed(entity, "update")) {
                return failed(each, List.of(EntityImports.forbidden(at, entity, "update")));
            }
            writes.importUpdate(entity, found.get(), mapper.valueToTree(values));
            return new RowOutcome(each.number(), Result.UPDATED, found.get(), List.of());
        }
        if (!EntityImports.allowed(entity, "create")) {
            return failed(each, List.of(EntityImports.forbidden(at, entity, "create")));
        }
        Map<String, Object> created = writes.create(entity, mapper.valueToTree(values), true);
        return new RowOutcome(each.number(), Result.CREATED, EntityReads.id(created), List.of());
    }

    /** The problems of a refused save, addressed to the row: its fields' as {@code rows[17].qty}, else the row's. */
    private static List<FieldErrorItem> problems(String at, ApiException refused) {
        List<FieldErrorItem> fields = refused.getFieldErrors();
        if (fields != null && !fields.isEmpty()) {
            return fields.stream()
                    .map(error -> error.at(error.field().isEmpty() ? at : at + "." + error.field()))
                    .toList();
        }
        return List.of(FieldErrorItem.keyed(
                at, refused.getErrorCode().getCode(), refused.getMessageKey(), refused.getParams()));
    }

    private static RowOutcome failed(Row each, List<FieldErrorItem> errors) {
        return new RowOutcome(each.number(), Result.FAILED, null, errors);
    }
}
