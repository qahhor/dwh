package com.smartup24.cms.instance.studio.controller;

import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.Activated;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.Availability;
import com.smartup24.cms.instance.studio.service.StudioActivationService;
import com.smartup24.cms.instance.studio.service.StudioPackages;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Explicit create-only operation, separate from settings access and CLI preparation (ADR-0038). */
@RestController
@RequestMapping("/api/v1/studio")
public class StudioFormController {
    private final StudioActivationService activation;

    public StudioFormController(StudioActivationService activation) {
        this.activation = activation;
    }

    @Operation(
            summary = "Read simple-form activation availability",
            description =
                    "Requires studio.forms.publish. Reports the activation switch and bounded namespace/form limit; an inactive module returns 404.")
    @GetMapping("/activation")
    @RequiresPermission(form = "studio.forms", action = "publish")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<Availability> availability() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(activation.availability());
    }

    @Operation(
            summary = "Activate an immutable simple form",
            description =
                    "Requires studio.forms.publish and enabled activation. Only the studio namespace is accepted. "
                            + "A new form answers 201 with Location; an exact repeat of an activated package creates "
                            + "nothing and answers 200 with the same body; a different package at an occupied code "
                            + "answers 409.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = DeliveryPackage.class))))
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "The form was activated by this request",
                content = @Content(schema = @Schema(implementation = Activated.class))),
        @ApiResponse(
                responseCode = "200",
                description = "The exact package was already activated; nothing was created",
                content = @Content(schema = @Schema(implementation = Activated.class)))
    })
    @PostMapping(value = "/forms", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(form = "studio.forms", action = "publish")
    @NoIdempotency
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<Activated> activate(HttpServletRequest request) throws IOException {
        StudioPackages.requireSize(request.getContentLengthLong());
        byte[] body = request.getInputStream().readNBytes(StudioPackages.MAX_BYTES + 1);
        StudioPackages.requireSize(body.length);
        StudioActivationService.Outcome outcome = activation.activate(body);
        Activated result = outcome.form();
        if (!outcome.created()) {
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
        }
        return ResponseEntity.created(URI.create("/api/v1/form-meta/" + result.entityCode()))
                .header("Cache-Control", "no-store")
                .body(result);
    }
}
