package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.md.api.MdSimpleFormDraftDtos.Declaration;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.md.service.MdSimpleFormDrafts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Checks only the structure of a local draft so incomplete input can be recovered (ADR-0037). */
@RestController
public class MdSimpleFormDraftController {
    private final MdSimpleFormDrafts drafts;

    public MdSimpleFormDraftController(MdSimpleFormDrafts drafts) {
        this.drafts = drafts;
    }

    @Operation(
            summary = "Check an unfinished local simple form draft",
            description =
                    "Checks bounded JSON structure and known field types, preserving empty identifiers, labels "
                            + "and unfinished numeric limits. Returns authoring input only, never a checksum or delivery package. "
                            + "Preparation independently enforces the complete declaration contract. Nothing is stored or activated.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = Declaration.class))))
    @PostMapping(value = "/api/v1/form-studio/new-forms/check-draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(form = "md.settings", action = "update")
    @NoIdempotency
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<Declaration> check(HttpServletRequest request) throws IOException {
        MdSimpleFormDeclarations.requireSize(request.getContentLengthLong());
        byte[] body = request.getInputStream().readNBytes(MdSimpleFormDeclarations.MAX_BYTES + 1);
        MdSimpleFormDeclarations.requireSize(body.length);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(drafts.check(body));
    }
}
