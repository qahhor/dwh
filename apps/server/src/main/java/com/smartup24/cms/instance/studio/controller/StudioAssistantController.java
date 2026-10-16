package com.smartup24.cms.instance.studio.controller;

import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.studio.api.StudioAssistantDtos;
import com.smartup24.cms.instance.studio.service.StudioAssistantInput;
import com.smartup24.cms.instance.studio.service.StudioAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A read-only generation request: explicit user adoption is a separate action (ADR-0039). */
@RestController
@RequestMapping("/api/v1/studio/assistant")
public class StudioAssistantController {
    private final StudioAssistantService service;

    public StudioAssistantController(StudioAssistantService service) {
        this.service = service;
    }

    @Operation(
            summary = "Read assistant availability",
            description =
                    "Requires studio.forms.assist and md.settings.update. Disabled by default; no provider request is made.")
    @GetMapping
    @RequiresPermission(form = "studio.forms", action = "assist")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<StudioAssistantDtos.Availability> availability() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.availability());
    }

    @Operation(
            summary = "Propose a validated simple-form draft",
            description =
                    "Requires studio.forms.assist and md.settings.update. Sends only the explicit prompt to the configured provider. No save, package, activation or replay; 200 returns an unsaved declaration.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = StudioAssistantDtos.Request.class))))
    @PostMapping(value = "/drafts", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(form = "studio.forms", action = "assist")
    @NoIdempotency
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<Declaration> propose(HttpServletRequest request) throws IOException {
        StudioAssistantInput.requireSize(request.getContentLengthLong());
        byte[] body = request.getInputStream().readNBytes(StudioAssistantInput.MAX_BYTES + 1);
        StudioAssistantInput.requireSize(body.length);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.propose(body));
    }
}
