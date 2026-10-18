package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.common.formula.FormulaException;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos;
import com.smartup24.cms.instance.md.service.MdSimpleFormDocumentLogic;
import com.smartup24.cms.instance.md.service.MdSimpleFormLogic;
import com.smartup24.cms.instance.studio.api.StudioFormulaDtos.Check;
import com.smartup24.cms.instance.studio.api.StudioFormulaDtos.CheckRequest;
import com.smartup24.cms.instance.studio.api.StudioFormulaDtos.Field;
import com.smartup24.cms.instance.studio.api.StudioFormulaDtos.Lines;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The check of one formula while the editor types it (ADR-0042, ADR-0043), with the parser and checker the activation
 * runs: a computed value or a default gives its field's type, a condition, a rule and a transition's condition a
 * yes/no; a condition may not read the field it shows. A formula of the header of a document also reads its number,
 * status and rows; a formula of a column reads its row and the header's entered fields as {@code doc.<field>}.
 */
public final class StudioFormulaChecks {
    private StudioFormulaChecks() {}

    public static Check check(CheckRequest request) {
        List<MdSimpleFormDtos.Field> header = new ArrayList<>();
        request.fields().forEach(field -> header.add(declared(field)));
        List<MdSimpleFormDocumentDtos.Lines> lines = new ArrayList<>();
        if (request.lines() != null) request.lines().forEach(table -> lines.add(table(table)));
        String kind = request.kind();
        boolean value = "formula".equals(kind) || "defaultFormula".equals(kind);
        String owner = request.field();
        Map<String, FormulaType> types;
        String fieldType;
        String line = request.line();
        if (line != null) {
            MdSimpleFormDocumentDtos.Lines table = lines.stream()
                    .filter(candidate -> candidate.key().equals(line))
                    .findFirst()
                    .orElse(null);
            types = table == null ? Map.of() : MdSimpleFormDocumentLogic.line(table, header);
            fieldType = table == null || owner == null ? null : typeOf(table.fields(), owner);
        } else {
            types = header(header, lines, request);
            fieldType = owner == null
                    ? null
                    : header.stream()
                            .filter(field -> field.id().equals(owner))
                            .map(MdSimpleFormDtos.Field::type)
                            .findFirst()
                            .orElse(null);
        }
        if (!value) fieldType = null;
        String self = "visibleIf".equals(kind) ? owner : null;
        if (value && fieldType == null)
            return answer(new MdSimpleFormLogic.Checked(null, new FormulaException("result", 1, null)));
        return answer(MdSimpleFormLogic.check(request.formula(), types, fieldType, self));
    }

    private static Map<String, FormulaType> header(
            List<MdSimpleFormDtos.Field> fields, List<MdSimpleFormDocumentDtos.Lines> lines, CheckRequest request) {
        Map<String, FormulaType> types = new LinkedHashMap<>(
                MdSimpleFormDocumentLogic.header(fields, lines.isEmpty() ? null : lines, null, null));
        if (Boolean.TRUE.equals(request.numbered())) types.put(MdSimpleFormDocumentLogic.NUMBER, FormulaType.TEXT);
        if (Boolean.TRUE.equals(request.statuses())) types.put(MdSimpleFormDocumentLogic.STATUS, FormulaType.OPTION);
        return types;
    }

    private static @Nullable String typeOf(List<MdSimpleFormDocumentDtos.LineField> columns, String id) {
        return columns.stream()
                .filter(column -> column.id().equals(id))
                .map(MdSimpleFormDocumentDtos.LineField::type)
                .findFirst()
                .orElse(null);
    }

    private static Check answer(MdSimpleFormLogic.Checked checked) {
        FormulaException problem = checked.problem();
        FormulaType type = checked.type();
        return problem == null
                ? new Check(true, Objects.requireNonNull(type).wire(), null, null, null, null)
                : new Check(false, null, problem.reason(), problem.position(), problem.name(), problem.messageKey());
    }

    private static MdSimpleFormDocumentDtos.Lines table(Lines table) {
        List<MdSimpleFormDocumentDtos.LineField> columns = new ArrayList<>();
        for (Field field : table.fields()) {
            MdSimpleFormDtos.Field declared = declared(field);
            columns.add(new MdSimpleFormDocumentDtos.LineField(
                    declared.id(),
                    declared.type(),
                    declared.labels(),
                    false,
                    null,
                    null,
                    null,
                    declared.target(),
                    null));
        }
        return new MdSimpleFormDocumentDtos.Lines(table.key(), names(table.key()), 1, columns);
    }

    private static MdSimpleFormDtos.Field declared(Field field) {
        MdSimpleFormDtos.Target target =
                field.target() == null ? null : new MdSimpleFormDtos.Target(field.target(), field.id());
        return new MdSimpleFormDtos.Field(
                field.id(),
                field.type(),
                names(field.id()),
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                target,
                Boolean.TRUE.equals(field.computed()) ? "computed" : null,
                null,
                null,
                null,
                null,
                null);
    }

    private static MdSimpleFormDtos.Labels names(String id) {
        return new MdSimpleFormDtos.Labels(id, id, id);
    }
}
