package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Prepares a local delivery artifact; no source code, database schema or runtime definition is changed (ADR-0037). */
@RestController
@RequestMapping("/api/v1/form-studio/new-forms")
public class MdSimpleFormController {
    private final MdSimpleFormDeclarations declarations;

    public MdSimpleFormController(MdSimpleFormDeclarations declarations) {
        this.declarations = declarations;
    }

    @Operation(
            summary = "Prepare a simple new form for CLI delivery",
            description =
                    "Validates a bounded create-only declaration and returns a deterministic SHA-256 package. "
                            + "Requires current settings update permission. No draft, entity or schema is created. "
                            + "The CLI independently validates the package and target checkout before generating sources and Flyway migrations.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = Declaration.class))))
    @PostMapping(value = "/prepare", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(form = "md.settings", action = "update")
    @NoIdempotency
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<DeliveryPackage> prepare(HttpServletRequest request) throws IOException {
        MdSimpleFormDeclarations.requireSize(request.getContentLengthLong());
        byte[] body = request.getInputStream().readNBytes(MdSimpleFormDeclarations.MAX_BYTES + 1);
        MdSimpleFormDeclarations.requireSize(body.length);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(declarations.prepare(body));
    }
}
