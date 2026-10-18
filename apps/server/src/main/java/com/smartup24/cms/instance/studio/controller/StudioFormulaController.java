package com.smartup24.cms.instance.studio.controller;

import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.formula.FormulaException;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos;
import com.smartup24.cms.instance.md.service.MdSimpleFormLogic;
import com.smartup24.cms.instance.studio.api.StudioFormulaDtos.Check;
import com.smartup24.cms.instance.studio.api.StudioFormulaDtos.CheckRequest;
import com.smartup24.cms.instance.studio.api.StudioFormulaDtos.Field;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/studio/formulas/check} (ADR-0042): the editor of a live form checks one formula as it is typed,
 * with the same parser and checker the activation runs, and shows the reason and the position inline. Nothing is
 * stored; the activation checks every formula of the form again, cycles among the computed fields included.
 */
@RestController
@RequestMapping("/api/v1/studio/formulas")
public class StudioFormulaController {

    @Operation(
            summary = "Check a formula of a live form",
            description = "Requires studio.forms.publish. Parses and checks one formula against the shown fields of the"
                    + " form: a computed value or a default gives its field's type, a condition and a rule a yes/no;"
                    + " a condition may not read the field it shows. Answers the type or the first problem with its"
                    + " 1-based position; stores nothing.")
    @PostMapping(value = "/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(form = "studio.forms", action = "publish")
    @NoIdempotency
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<Check> check(@Valid @RequestBody CheckRequest request) {
        Map<String, FormulaType> types = new LinkedHashMap<>();
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (Field field : request.fields()) {
            types.put(field.id(), MdSimpleFormLogic.type(declared(field)));
            fieldTypes.put(field.id(), field.type());
        }
        String fieldType = null;
        String self = null;
        switch (request.kind()) {
            case "formula", "defaultFormula" ->
                fieldType = request.field() == null ? null : fieldTypes.get(request.field());
            case "visibleIf" -> self = request.field();
            default -> {
                // A rule, required and read-only conditions take a yes/no and may read every field.
            }
        }
        boolean value = "formula".equals(request.kind()) || "defaultFormula".equals(request.kind());
        if (value && fieldType == null) {
            return answer(new MdSimpleFormLogic.Checked(null, new FormulaException("result", 1, null)));
        }
        return answer(MdSimpleFormLogic.check(request.formula(), types, fieldType, self));
    }

    private static ResponseEntity<Check> answer(MdSimpleFormLogic.Checked checked) {
        FormulaException problem = checked.problem();
        FormulaType type = checked.type();
        Check body = problem == null
                ? new Check(true, type == null ? null : type.wire(), null, null, null, null)
                : new Check(false, null, problem.reason(), problem.position(), problem.name(), problem.messageKey());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    private static MdSimpleFormDtos.Field declared(Field field) {
        MdSimpleFormDtos.Target target =
                field.target() == null ? null : new MdSimpleFormDtos.Target(field.target(), field.id());
        return new MdSimpleFormDtos.Field(
                field.id(),
                field.type(),
                new MdSimpleFormDtos.Labels(field.id(), field.id(), field.id()),
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                target);
    }
}
