package com.smartup24.cms.instance.studio.controller;

import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.studio.api.StudioFormulaDtos.Check;
import com.smartup24.cms.instance.studio.api.StudioFormulaDtos.CheckRequest;
import com.smartup24.cms.instance.studio.service.StudioFormulaChecks;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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
        Check body = StudioFormulaChecks.check(request);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
