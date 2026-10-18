package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.LineField;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Lines;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Numbering;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Workflow;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormRuleDtos.Rule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The formulas of a document of simple-form format 6 (ADR-0043). A formula of the header — a field's, a rule's, a
 * transition's condition — reads the shown fields, the number ({@link #NUMBER}), the status ({@link #STATUS}) and the
 * rows of each table: {@code lines.<table>} the rows, {@code lines.<table>.<column>} a column, which only {@code sum}
 * (numbers) and {@code count} take. A formula of a column reads its row's columns and the header's entered fields as
 * {@code doc.<field>} — not a computed one, so no formula of a row waits for the rows. A computed column is optional,
 * of a type a formula computes, and the computed columns of a table never read each other in a cycle.
 */
public final class MdSimpleFormDocumentLogic {
    /** The key of a document's number: a key with a capital letter, which no declared field id has. */
    public static final String NUMBER = "docNumber";

    /** The key of a document's status. */
    public static final String STATUS = "docStatus";

    /** The key of the comment of a document's last transition. */
    public static final String COMMENT = "docComment";

    private MdSimpleFormDocumentLogic() {}

    /** The names a formula of the header reads, by formula type. */
    public static Map<String, FormulaType> header(
            List<Field> fields,
            @Nullable List<Lines> lines,
            @Nullable Workflow workflow,
            @Nullable Numbering numbering) {
        Map<String, FormulaType> types = new LinkedHashMap<>(MdSimpleFormLogic.types(fields));
        if (numbering != null) types.put(NUMBER, FormulaType.TEXT);
        if (workflow != null) types.put(STATUS, FormulaType.OPTION);
        if (lines != null) {
            for (Lines table : lines) {
                types.put("lines." + table.key(), FormulaType.ROWS);
                for (LineField column : table.fields()) {
                    types.put(
                            "lines." + table.key() + "." + column.id(),
                            "number".equals(column.type()) ? FormulaType.NUMBERS : FormulaType.ROWS);
                }
            }
        }
        return types;
    }

    /** The names a formula of a column of {@code table} reads: its row's columns and the header's entered fields. */
    public static Map<String, FormulaType> line(Lines table, List<Field> fields) {
        Map<String, FormulaType> types = new LinkedHashMap<>();
        table.fields().forEach(column -> types.put(column.id(), type(column)));
        for (Field field : fields) {
            if (field.hidden() == null && field.formula() == null) {
                types.put("doc." + field.id(), MdSimpleFormLogic.type(field));
            }
        }
        return types;
    }

    /** The formula type of a column's value, as a field of its type has. */
    public static FormulaType type(LineField column) {
        return MdSimpleFormLogic.type(new Field(
                column.id(),
                column.type(),
                column.labels(),
                column.required(),
                true,
                column.maxLength(),
                column.scale(),
                null,
                null,
                column.options(),
                column.target()));
    }

    /** Checks every formula of the document; 422 naming each problem. */
    static void check(
            List<Field> fields,
            @Nullable List<Rule> rules,
            @Nullable List<Lines> lines,
            @Nullable Workflow workflow,
            @Nullable Numbering numbering) {
        Map<String, FormulaType> header = header(fields, lines, workflow, numbering);
        List<FieldErrorItem> problems = new ArrayList<>(MdSimpleFormLogic.problems(fields, rules, header));
        if (lines != null) {
            for (int index = 0; index < lines.size(); index++) {
                columns(lines.get(index), fields, "lines[" + index + "]", problems);
            }
        }
        if (workflow != null) {
            for (int index = 0; index < workflow.transitions().size(); index++) {
                MdSimpleFormLogic.checked(
                        workflow.transitions().get(index).condition(),
                        header,
                        "workflow.transitions[" + index + "].condition",
                        null,
                        problems);
            }
        }
        if (!problems.isEmpty()) throw ApiException.validation("error.md.simple_form_invalid", problems);
    }

    private static void columns(Lines table, List<Field> fields, String at, List<FieldErrorItem> problems) {
        Map<String, FormulaType> types = line(table, fields);
        Map<String, Formula> computed = new LinkedHashMap<>();
        Map<String, Integer> places = new LinkedHashMap<>();
        List<LineField> columns = table.fields();
        for (int index = 0; index < columns.size(); index++) {
            LineField column = columns.get(index);
            if (column.formula() == null) continue;
            String path = at + ".fields[" + index + "].formula";
            if (!MdSimpleFormLogic.COMPUTED.contains(column.type()) || column.required()) {
                problems.add(MdSimpleFormLogic.logic(path));
                continue;
            }
            Formula formula = MdSimpleFormLogic.checked(column.formula(), types, path, column.type(), problems);
            if (formula != null) {
                computed.put(column.id(), formula);
                places.put(column.id(), index);
            }
        }
        MdSimpleFormLogic.cycles(computed, id -> at + ".fields[" + places.get(id) + "].formula", problems);
    }
}
