package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.api.FormLayoutDtos.FormLayoutDeclaration;
import com.smartup24.cms.instance.common.api.FormLayoutDtos.FormLayoutPreview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A read-only administrator prototype: validating a declaration is not publishing it. */
@RestController
@RequestMapping("/api/v1/form-studio")
public class FormStudioController {
    private final FormStudioService studio;

    public FormStudioController(FormStudioService studio) {
        this.studio = studio;
    }

    @Operation(
            summary = "Get the form layout prototype",
            description =
                    "Current authorized product form metadata and its initial presentation declaration; no records are read.")
    @GetMapping("/prototype")
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.OK)
    public FormLayoutPreview prototype() {
        return studio.prototype();
    }

    @Operation(
            summary = "Validate a form layout preview",
            description =
                    "Validates a presentation-only declaration against current authorized metadata, without persistence or publication.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = FormLayoutDeclaration.class))))
    @NoIdempotency
    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.OK)
    public FormLayoutPreview preview(HttpServletRequest request) throws IOException {
        FormLayoutDeclarations.requireBodySize(request.getContentLengthLong());
        byte[] body = request.getInputStream().readNBytes(FormLayoutDeclarations.MAX_BODY_BYTES + 1);
        FormLayoutDeclarations.requireBodySize(body.length);
        return studio.preview(body);
    }
}
