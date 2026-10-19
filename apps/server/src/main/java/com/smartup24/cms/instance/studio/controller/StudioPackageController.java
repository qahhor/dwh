package com.smartup24.cms.instance.studio.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ApplyRequest;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ExportPlan;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ExportRequest;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ImportSummary;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ImportView;
import com.smartup24.cms.instance.studio.service.StudioPackageService;
import com.smartup24.cms.instance.studio.solution.SolutionFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Solution packages (ADR-0047): the export of chosen forms with what they use, and the import of a package — upload
 * and check, application with the mapping of its placeholders, history. Applying needs the rights of each kind of item
 * the package creates as well; a package never grants a right, carries no user id or secret and leaves its mailings and
 * automations paused.
 */
@RestController
@Profile("!migrate")
@RequestMapping("/api/v1/studio/packages")
public class StudioPackageController {
    private static final String FORM = "studio.packages";

    private final StudioPackageService packages;

    public StudioPackageController(StudioPackageService packages) {
        this.packages = packages;
    }

    @Operation(
            summary = "Plan a solution package",
            description = "Requires studio.packages.export. Lists what a package of the selection would hold — the"
                    + " selected items and, as dependencies, the forms they stand on, the forms those refer to, the"
                    + " lists of the forms, the datasets of the reports and the reports of the mailings — what else"
                    + " uses its forms, and the placeholders its roles and users become. Each item is read with the"
                    + " right of its own screen; 422 when a filter or a parameter names a record of this installation.")
    @PostMapping(value = "/export/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(form = FORM, action = "export")
    @NoIdempotency
    @ResponseStatus(HttpStatus.OK)
    public ExportPlan plan(@RequestBody ExportRequest request) {
        return packages.plan(request);
    }

    @Operation(
            summary = "Download a solution package",
            description = "Requires studio.packages.export. The package of the selection as a JSON file with its"
                    + " checksum (SHA-256 of its canonical JSON without the checksum). No record data, user id or"
                    + " secret goes in; roles and fixed users become placeholders.")
    @ApiResponse(
            responseCode = "200",
            description = "The package file",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SolutionPackage.class)))
    @PostMapping(value = "/export", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(form = FORM, action = "export")
    @NoIdempotency
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<byte[]> export(@RequestBody ExportRequest request) {
        byte[] file = packages.export(request);
        String name = request.code() + "-" + request.version() + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name).build().toString())
                .body(file);
    }

    @Operation(
            summary = "Upload and check a solution package",
            description = "Requires studio.packages.import. The body is a package file of at most 2 MiB. It is"
                    + " verified — format, checksum, the simple-form format this installation reads, structure,"
                    + " every form as a simple-form package — and checked by applying it in a transaction that always"
                    + " rolls back: each item is to be created, upgraded (a new version of a form under the compatible"
                    + " change rules), left unchanged or skipped, or is refused with its reasons. A suffix copies the"
                    + " solution under other codes of its forms, lists and datasets. 201 with Location; nothing but"
                    + " the import is kept.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = SolutionPackage.class))))
    @PostMapping(value = "/imports", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(form = FORM, action = "import")
    @NoIdempotency
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ImportView> upload(
            @Parameter(schema = @Schema(pattern = StudioPackageDtos.SUFFIX)) @RequestParam(required = false) @Nullable
                    String suffix,
            HttpServletRequest request)
            throws IOException {
        SolutionFormat.requireSize(request.getContentLengthLong());
        byte[] body = request.getInputStream().readNBytes(StudioPackageDtos.MAX_BYTES + 1);
        SolutionFormat.requireSize(body.length);
        return created(packages.upload(body, suffix));
    }

    @Operation(
            summary = "List the imports of solution packages",
            description = "Requires studio.packages.import. Newest first; the default limit is 20, maximum 100.")
    @GetMapping("/imports")
    @RequiresPermission(form = FORM, action = "import")
    @ResponseStatus(HttpStatus.OK)
    public KeysetPage<ImportSummary> imports(
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        return packages.list(limit, cursor);
    }

    @Operation(
            summary = "Read an import of a solution package",
            description = "Requires studio.packages.import. The items with their outcome at the last check or the"
                    + " application, the placeholders to map and the mapping given.")
    @GetMapping("/imports/{id}")
    @RequiresPermission(form = FORM, action = "import")
    @ResponseStatus(HttpStatus.OK)
    public ImportView importOf(@PathVariable long id) {
        return packages.get(id);
    }

    @Operation(
            summary = "Apply an import of a solution package",
            description = "Requires studio.packages.import and the rights of each kind of item it creates. Takes the"
                    + " mapping of the placeholders (a role placeholder: one active role; a placeholder of users:"
                    + " active users; each required one mapped). Applies every item in one transaction: a refused item"
                    + " rolls everything back (422) and the import shows the reasons. Mailings and automations are"
                    + " created paused. With dryRun the mapping is only checked. 409 when already applied; applying"
                    + " the same package again creates nothing new.")
    @PostMapping(value = "/imports/{id}/apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(form = FORM, action = "import")
    @NoIdempotency
    @ResponseStatus(HttpStatus.OK)
    public ImportView apply(@PathVariable long id, @RequestBody ApplyRequest request) {
        return packages.apply(id, request);
    }

    static ResponseEntity<ImportView> created(ImportView view) {
        return ResponseEntity.created(URI.create("/api/v1/studio/packages/imports/" + view.id()))
                .header("Cache-Control", "no-store")
                .body(view);
    }
}
