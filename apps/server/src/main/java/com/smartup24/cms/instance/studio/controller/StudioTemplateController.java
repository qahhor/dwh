package com.smartup24.cms.instance.studio.controller;

import com.smartup24.cms.instance.common.annotation.NoIdempotency;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ImportView;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.Template;
import com.smartup24.cms.instance.studio.service.StudioPackageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The template gallery (ADR-0047): solution packages the product ships. Creating a solution from a template is an
 * import of its package, checked and then applied with the mapping like an upload.
 */
@RestController
@RequestMapping("/api/v1/studio/templates")
public class StudioTemplateController {
    private final StudioPackageService packages;

    public StudioTemplateController(StudioPackageService packages) {
        this.packages = packages;
    }

    @Operation(
            summary = "List the templates of solutions",
            description = "Requires studio.packages.import. The packages the product ships, each verified like an"
                    + " upload, with its description and what it holds.")
    @GetMapping
    @RequiresPermission(form = "studio.packages", action = "import")
    @ResponseStatus(HttpStatus.OK)
    public List<Template> templates() {
        return packages.templates();
    }

    @Operation(
            summary = "Create a solution from a template",
            description = "Requires studio.packages.import. Checks the template's package like an upload and keeps"
                    + " the import (201 with Location); applying it with the mapping creates the solution. A suffix"
                    + " creates a second copy under other codes.")
    @PostMapping("/{code}/imports")
    @RequiresPermission(form = "studio.packages", action = "import")
    @NoIdempotency
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ImportView> fromTemplate(
            @PathVariable String code,
            @Parameter(schema = @Schema(pattern = StudioPackageDtos.SUFFIX)) @RequestParam(required = false) @Nullable
                    String suffix) {
        return StudioPackageController.created(packages.fromTemplate(code, suffix));
    }
}
