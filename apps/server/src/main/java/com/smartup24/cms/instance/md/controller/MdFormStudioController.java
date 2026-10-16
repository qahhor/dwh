package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.api.FormLayoutDtos.FormLayoutDeclaration;
import com.smartup24.cms.instance.common.entity.FormLayoutDeclarations;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioDraftResponse;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioVerification;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioVersionPackage;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioVersionSummary;
import com.smartup24.cms.instance.md.service.MdFormStudioPackages;
import com.smartup24.cms.instance.md.service.MdFormStudioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Authoring endpoints never activate a package or change the data API (ADR-0035). */
@RestController
@RequestMapping("/api/v1/form-studio/drafts/{entityCode}")
public class MdFormStudioController {
    private final MdFormStudioService studio;

    public MdFormStudioController(MdFormStudioService studio) {
        this.studio = studio;
    }

    @Operation(
            summary = "Read the shared form layout draft",
            description = "Returns the saved draft, or null when none exists, with current authorized form metadata. "
                    + "A saved draft is revalidated against visible fields and its revision is returned as ETag.")
    @GetMapping
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<FormStudioDraftResponse> get(@PathVariable String entityCode) {
        var body = studio.get(entityCode);
        if (body.draft() == null) return ResponseEntity.ok(body);
        return ResponseEntity.ok().eTag(Revisions.etag(body.draft().revision())).body(body);
    }

    @Operation(
            summary = "Create a shared form layout draft",
            description = "Validates the declaration against current authorized metadata and creates one shared draft "
                    + "for the prototype form. Returns its Location and ETag; an existing draft causes 409.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = FormLayoutDeclaration.class))))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @NoIdempotency
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.CREATED)
    @AnswersRevision
    public ResponseEntity<FormStudioDraftResponse> create(@PathVariable String entityCode, HttpServletRequest request)
            throws IOException {
        var body = studio.create(entityCode, declarationBody(request));
        var draft = Objects.requireNonNull(body.draft());
        return ResponseEntity.created(URI.create("/api/v1/form-studio/drafts/" + draft.entityCode()))
                .eTag(Revisions.etag(draft.revision()))
                .body(body);
    }

    @Operation(
            summary = "Save a shared form layout draft",
            description = "Replaces the shared declaration only when If-Match names its current revision. Both the "
                    + "stored and submitted layouts must remain visible and valid. Returns the new ETag; "
                    + "missing If-Match causes 428 and a stale revision causes 409.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = FormLayoutDeclaration.class))))
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @NoIdempotency
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.OK)
    @AnswersRevision
    public ResponseEntity<FormStudioDraftResponse> update(
            @PathVariable String entityCode,
            @RequestHeader(value = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest request)
            throws IOException {
        var body = studio.update(entityCode, Revisions.required(ifMatch), declarationBody(request));
        return ResponseEntity.ok()
                .eTag(Revisions.etag(Objects.requireNonNull(body.draft()).revision()))
                .body(body);
    }

    @Operation(
            summary = "Prepare an immutable form layout version",
            description = "Requires If-Match for the current draft and seals its declaration, dependency fingerprint "
                    + "and checksum atomically. Repeating preparation of the same current revision returns its "
                    + "existing compatible package. Returns 201 with Location and never activates the package.")
    @PostMapping("/versions")
    @NoIdempotency
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<FormStudioVersionPackage> prepare(
            @PathVariable String entityCode,
            @RequestHeader(value = Revisions.IF_MATCH, required = false) @Nullable String ifMatch) {
        var version = studio.prepare(entityCode, Revisions.required(ifMatch));
        return Created.at(
                "/api/v1/form-studio/drafts/{entityCode}/versions/{version}",
                new Object[] {entityCode, version.version()},
                version);
    }

    @Operation(
            summary = "List prepared form layout versions",
            description = "Returns version, source revision, checksum and creation time in descending keyset order. "
                    + "The default limit is 20, the maximum is 100 and an invalid limit causes 422. "
                    + "Each request checks current access; declaration contents are not included.")
    @GetMapping("/versions")
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.OK)
    public KeysetPage<FormStudioVersionSummary> versions(
            @PathVariable String entityCode,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        return studio.versions(entityCode, limit, cursor);
    }

    @Operation(
            summary = "Read a prepared form layout package",
            description = "Returns the immutable stored package after checking current permissions and field "
                    + "visibility. Use the verification action to check its dependency compatibility. "
                    + "Reading a package never changes the draft or activates a runtime definition.")
    @GetMapping("/versions/{version}")
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.OK)
    public FormStudioVersionPackage version(@PathVariable String entityCode, @PathVariable long version) {
        return studio.version(entityCode, version);
    }

    @Operation(
            summary = "Verify a prepared form package",
            description = "Checks a strict JSON package of at most 64 KiB against the trusted stored version, checksum "
                    + "and current authorized metadata. Returns 200 with the verification result, 422 for an invalid "
                    + "package or 409 for changed dependencies. This action stores and activates nothing.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = FormStudioVersionPackage.class))))
    @PostMapping(value = "/versions/{version}/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    @NoIdempotency
    @RequiresPermission(form = "md.settings", action = "update")
    @ResponseStatus(HttpStatus.OK)
    public FormStudioVerification verify(
            @PathVariable String entityCode, @PathVariable long version, HttpServletRequest request)
            throws IOException {
        MdFormStudioPackages.requireSize(request.getContentLengthLong());
        byte[] body = request.getInputStream().readNBytes(MdFormStudioPackages.MAX_BYTES + 1);
        MdFormStudioPackages.requireSize(body.length);
        return studio.verify(entityCode, version, body);
    }

    private static byte[] declarationBody(HttpServletRequest request) throws IOException {
        FormLayoutDeclarations.requireBodySize(request.getContentLengthLong());
        byte[] body = request.getInputStream().readNBytes(FormLayoutDeclarations.MAX_BODY_BYTES + 1);
        FormLayoutDeclarations.requireBodySize(body.length);
        return body;
    }
}
