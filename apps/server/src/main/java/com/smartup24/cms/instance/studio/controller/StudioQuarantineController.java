package com.smartup24.cms.instance.studio.controller;

import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.CatalogStatus;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.QuarantineRequest;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.QuarantinedForm;
import com.smartup24.cms.instance.studio.service.StudioQuarantineService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The state of the stored live forms and their quarantine, for the publisher (ADR-0038). */
@RestController
@RequestMapping("/api/v1/studio")
@Profile("!migrate")
public class StudioQuarantineController {
    private static final String ENTRY = "/api/v1/studio/quarantine/{entityCode}";

    private final StudioQuarantineService quarantine;

    public StudioQuarantineController(StudioQuarantineService quarantine) {
        this.quarantine = quarantine;
    }

    @Operation(
            summary = "Read the state of the stored live forms",
            description = "Requires studio.forms.publish. Counts the stored and served forms and lists the forms that"
                    + " fail to load (left out, never failing the others) and the quarantined ones; both lists are"
                    + " bounded by the catalog. An inactive module returns 404.")
    @GetMapping("/catalog")
    @RequiresPermission(form = "studio.forms", action = "publish")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<CatalogStatus> catalogStatus() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(quarantine.status());
    }

    @Operation(
            summary = "Read one quarantined live form",
            description = "Requires studio.forms.publish. 404 when the form is unknown or not quarantined, or the"
                    + " code is not a live form code (^studio\\.[a-z][a-z0-9_]{0,19}$); the code is not repeated.")
    @GetMapping("/quarantine/{entityCode}")
    @RequiresPermission(form = "studio.forms", action = "publish")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<QuarantinedForm> quarantinedForm(@PathVariable String entityCode) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(quarantine.get(entityCode));
    }

    @Operation(
            summary = "Quarantine a stored live form",
            description = "Requires studio.forms.publish. The form stops being served on every node at its next"
                    + " request and answers as an unknown entity; its declaration, records and rights stay. 404 for an"
                    + " unknown form, 409 when it is already quarantined. Audited.")
    @PostMapping("/quarantine")
    @RequiresPermission(form = "studio.forms", action = "publish")
    @NoIdempotency
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<QuarantinedForm> quarantineForm(@Valid @RequestBody QuarantineRequest body) {
        QuarantinedForm held = quarantine.quarantine(body.entityCode(), body.reason());
        return Created.at(ENTRY, held.entityCode(), held);
    }

    @Operation(
            summary = "Return a quarantined live form to serving",
            description = "Requires studio.forms.publish. 404 when the form is unknown or not quarantined, or the"
                    + " code is not a live form code; the code is not repeated. Audited.")
    @DeleteMapping("/quarantine/{entityCode}")
    @RequiresPermission(form = "studio.forms", action = "publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> releaseForm(@PathVariable String entityCode) {
        quarantine.release(entityCode);
        return ResponseEntity.noContent().build();
    }
}
