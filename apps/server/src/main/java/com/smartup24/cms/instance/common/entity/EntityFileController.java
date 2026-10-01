package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.entity.EntityFiles.FileFacts;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.spi.storage.FileDownloadStream;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/entities/{code}/{id}/files/{fileId}} (ADR-0032, 4.7): the file of a file or image field, read
 * through its record. The viewer must see the entity and the record (its module's scope, {@link EntityRecords}), and
 * the file must be attached to that record; anything else answers the same 404, so the answer tells neither which
 * records nor which files exist. The files list of the files module keeps its own rule (the owner's files); it is not
 * widened to the records of every entity.
 */
@RestController
@RequestMapping("/api/v1/entities")
public class EntityFileController {

    /** Content types a browser may show in place: the image field's own; anything else is downloaded. */
    private static final List<String> INLINE = List.of("image/png", "image/jpeg", "image/webp");

    private final EntityRegistry registry;
    private final @Nullable EntityFiles files;

    @Autowired
    public EntityFileController(EntityRegistry registry, @Nullable EntityFiles files) {
        this.registry = registry;
        this.files = files;
    }

    /** Anyone signed in may ask; the entity's right, the record's scope and the attachment are checked below. */
    @Operation(
            summary = "Download a file of a record",
            description = "The content of a file attached to a file or image field of a record the viewer may see.")
    @GetMapping("/{code}/{id}/files/{fileId}")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable String code, @PathVariable long id, @PathVariable UUID fileId) {
        EntityDefinition entity = registry.find(code)
                .filter(found -> SecurityContext.hasPermission(found.form(), "view"))
                .orElseThrow(EntityFileController::notFound);
        EntityRecords records = registry.records(code).orElseThrow(EntityFileController::notFound);
        records.requireVisible(id);
        if (files == null) throw notFound();
        FileFacts file = files.attached(entity.code(), id, fileId).orElseThrow(EntityFileController::notFound);
        FileDownloadStream stream = files.open(file.id());
        String name = URLEncoder.encode(file.name(), StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = INLINE.contains(file.contentType()) ? "inline" : "attachment";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename*=UTF-8''" + name)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .body(new InputStreamResource(stream.inputStream()));
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.record_file_not_found");
    }
}
