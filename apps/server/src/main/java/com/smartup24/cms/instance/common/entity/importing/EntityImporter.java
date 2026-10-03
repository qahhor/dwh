package com.smartup24.cms.instance.common.entity.importing;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What the general runtime offers an import of entity records (ADR-0032, 10.1): the columns of the template a person
 * may fill and the rows of a file run through the steps of a save (ADR-0032, 6.3) as that person — the entity's rights,
 * the rights on the fields, the scope, the rules, the hooks, the audit and the events, as for a save through the API.
 * {@code common} knows no module: the report module keeps the journal, runs the job and reads and writes the files.
 */
public interface EntityImporter {

    /** Where the problem of a whole row is addressed, and the prefix of the problems of its fields. */
    static String rowAddress(int number) {
        return "rows[" + number + "]";
    }

    /**
     * The entity, when the signed-in person may import into it: 404 as for an unknown entity when they may not see it,
     * 404 {@code entity_action_not_found} when it does not declare IMPORT, 403 without the right's {@code import}.
     */
    String importable(String code);

    /** The template of the entity for the signed-in person: the key and the fields they may write, in form order. */
    Template template(String code);

    /**
     * Runs a batch of rows as the signed-in person, in one transaction with a savepoint per row: a row's problem undoes
     * only that row. A dry run checks everything — the hooks included — and writes nothing. A choice's cell may give
     * the name the template shows, in the language {@code text} renders dictionary keys in. {@code checkpoint} gets the
     * outcomes inside the batch's transaction when it is applied, so a journal written there commits with the rows;
     * after the rollback of a dry run.
     */
    List<RowOutcome> run(
            String code,
            boolean apply,
            List<Row> rows,
            Function<String, String> text,
            Consumer<List<RowOutcome>> checkpoint);

    /**
     * The template of an entity.
     *
     * @param entity  the entity's code
     * @param key     the key of the upsert's field (ADR-0032, 10.1)
     * @param columns the fields the person may write, in form order; the key among them
     */
    record Template(String entity, String key, List<Column> columns) {
        public Template {
            columns = List.copyOf(columns);
        }
    }

    /**
     * A column of the template.
     *
     * @param key      the field's key: the hidden second row of the template names the columns by it
     * @param labelKey the dictionary key of the field's label; empty when {@code label} is given
     * @param label    a ready label, or null
     * @param type     the kind of value
     * @param required a create needs a value for it (it has no default)
     * @param options  the values a choice takes, by code, with the dictionary key or the text of each name
     */
    record Column(
            String key,
            String labelKey,
            @Nullable String label,
            FieldType type,
            boolean required,
            List<Option> options) {
        public Column {
            options = List.copyOf(options);
        }
    }

    /** A value of a choice: its code and the dictionary key of its name, or the name itself. */
    record Option(
            String code,
            @Nullable String labelKey,
            @Nullable String label) {}

    /**
     * A row of the file.
     *
     * @param number the row's number in the file, from 1: the problems of the row are addressed {@code rows[17].qty}
     * @param cells  the row's filled cells by field key: a text, a number ({@code BigDecimal}, a date or time is the
     *               number of days Excel counts) or a flag
     */
    record Row(int number, Map<String, Object> cells) {
        public Row {
            cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        }
    }

    /** What became of a row. */
    enum Result {
        CREATED,
        UPDATED,
        FAILED
    }

    /**
     * The outcome of a row: what it did and the record it wrote, or its problems, addressed {@code rows[17].qty} or
     * {@code rows[17]} for the whole row.
     */
    record RowOutcome(int number, Result result, @Nullable Long id, List<FieldErrorItem> errors) {
        public RowOutcome {
            errors = List.copyOf(errors);
        }
    }
}
