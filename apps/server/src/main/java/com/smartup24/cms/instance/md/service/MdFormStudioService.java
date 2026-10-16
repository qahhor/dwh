package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.api.FormLayoutDtos.FormLayoutDeclaration;
import com.smartup24.cms.instance.common.entity.FormLayoutDeclarations;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormMeta;
import com.smartup24.cms.instance.common.entity.FormStudioService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioDraft;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioDraftResponse;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioVerification;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioVersionPackage;
import com.smartup24.cms.instance.md.api.MdFormStudioDtos.FormStudioVersionSummary;
import com.smartup24.cms.instance.md.repository.MdFormStudioRepository;
import com.smartup24.cms.instance.md.repository.MdFormStudioRepository.Draft;
import com.smartup24.cms.instance.md.repository.MdFormStudioRepository.StoredVersion;
import com.smartup24.cms.instance.md.repository.MdFormStudioRepository.VersionHeader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Shared CAS draft and append-only prepared versions; this service has no runtime activation path (ADR-0035). */
@Service
public class MdFormStudioService {
    private final MdFormStudioRepository repo;
    private final FormStudioService studio;
    private final FormLayoutDeclarations declarations;
    private final MdFormStudioPackages packages;
    private final AuditLogService audit;
    private final JsonColumns stored;

    public MdFormStudioService(
            MdFormStudioRepository repo,
            FormStudioService studio,
            FormLayoutDeclarations declarations,
            MdFormStudioPackages packages,
            AuditLogService audit,
            ObjectMapper json) {
        this.repo = repo;
        this.studio = studio;
        this.declarations = declarations;
        this.packages = packages;
        this.audit = audit;
        stored = new JsonColumns(json, "md_form_layout_drafts");
    }

    @Transactional(readOnly = true)
    public FormStudioDraftResponse get(String code) {
        FormMeta meta = studio.metadata(code);
        return response(repo.findDraft(code, false).orElse(null), meta);
    }

    @Transactional
    public FormStudioDraftResponse create(String code, byte[] body) {
        FormMeta meta = studio.metadata(code);
        String declaration = stored.write(declarations.validate(body, meta));
        Draft row = repo.create(code, declaration, actor())
                .orElseThrow(() -> ApiException.conflict(ErrorCode.CONFLICT, "error.md.form_studio_draft_exists"));
        auditDraft(row, null);
        return response(row, meta);
    }

    @Transactional
    public FormStudioDraftResponse update(String code, long expected, byte[] body) {
        FormMeta meta = studio.metadata(code);
        String declaration = stored.write(declarations.validate(body, meta));
        Draft before = repo.findDraft(code, false).orElseThrow(MdFormStudioService::draftMissing);
        if (before.revision() != expected) throw Revisions.conflict();
        declaration(before, meta);
        Draft row = repo.update(code, expected, declaration, actor()).orElseThrow(Revisions::conflict);
        auditDraft(row, before);
        return response(row, meta);
    }

    @Transactional
    public FormStudioVersionPackage prepare(String code, long expected) {
        FormMeta meta = studio.metadata(code);
        Draft draft = repo.findDraft(code, true).orElseThrow(MdFormStudioService::draftMissing);
        if (draft.revision() != expected) throw Revisions.conflict();
        var declaration = declaration(draft, meta);
        var existing = repo.bySource(code, expected);
        if (existing.isPresent()) {
            var prepared = envelope(existing.get(), meta);
            packages.requireCompatible(prepared.artifact(), meta);
            return prepared;
        }
        var prepared = packages.prepare(repo.nextVersion(code), expected, declaration, meta);
        StoredVersion row = repo.insertVersion(
                code, prepared.version(), expected, prepared.checksum(), packages.write(prepared.artifact()), actor());
        audit.logChange(
                "md_form_layout_versions",
                Long.toString(row.id()),
                "I",
                List.of("entity_code", "source_revision", "checksum"),
                null,
                Map.of("entity_code", code, "source_revision", expected, "checksum", prepared.checksum()));
        return prepared;
    }

    @Transactional(readOnly = true)
    public KeysetPage<FormStudioVersionSummary> versions(
            String code, @Nullable Integer limit, @Nullable String cursor) {
        studio.metadata(code);
        TimePage page = TimePage.of(limit, cursor, 20, 100);
        List<VersionHeader> rows = repo.versions(code, page);
        var result = page.page(rows, row -> new TimePage.Position(row.createdAt(), row.id()));
        return new KeysetPage<>(
                result.items().stream()
                        .map(row -> new FormStudioVersionSummary(
                                row.version(), row.sourceRevision(), row.checksum(), row.createdAt()))
                        .toList(),
                result.nextCursor(),
                result.hasMore(),
                result.totalEstimated(),
                result.totalExact());
    }

    @Transactional(readOnly = true)
    public FormStudioVersionPackage version(String code, long version) {
        FormMeta meta = studio.metadata(code);
        return envelope(findVersion(code, version), meta);
    }

    @Transactional(readOnly = true)
    public FormStudioVerification verify(String code, long version, byte[] body) {
        FormMeta meta = studio.metadata(code);
        var trusted = envelope(findVersion(code, version), meta);
        var supplied = packages.validate(body, meta);
        if (!trusted.equals(supplied)) throw MdFormStudioPackages.invalid();
        packages.requireCompatible(trusted.artifact(), meta);
        return new FormStudioVerification(true, trusted.version(), trusted.sourceRevision(), trusted.checksum());
    }

    private StoredVersion findVersion(String code, long version) {
        return repo.findVersion(code, version)
                .orElseThrow(
                        () -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.form_studio_version_not_found"));
    }

    private FormStudioVersionPackage envelope(StoredVersion row, FormMeta meta) {
        var artifact = packages.read(row.artifact());
        packages.requireVisible(artifact, meta);
        if (artifact.version() != row.version()
                || artifact.sourceRevision() != row.sourceRevision()
                || !artifact.entityCode().equals(row.entityCode())
                || !packages.digest(artifact).equals(row.checksum())) {
            throw MdFormStudioPackages.invalid();
        }
        return new FormStudioVersionPackage(row.version(), row.sourceRevision(), row.checksum(), artifact);
    }

    private FormStudioDraftResponse response(@Nullable Draft draft, FormMeta meta) {
        return new FormStudioDraftResponse(
                draft == null
                        ? null
                        : new FormStudioDraft(draft.entityCode(), draft.revision(), declaration(draft, meta)),
                meta);
    }

    private FormLayoutDeclaration declaration(Draft draft, FormMeta meta) {
        return declarations.validate(
                stored.write(stored.tree(draft.declaration())).getBytes(StandardCharsets.UTF_8), meta);
    }

    private void auditDraft(Draft row, @Nullable Draft before) {
        audit.logChange(
                "md_form_layout_drafts",
                row.entityCode(),
                before == null ? "I" : "U",
                List.of("revision", "declaration_checksum"),
                before == null
                        ? null
                        : Map.of(
                                "revision",
                                before.revision(),
                                "declaration_checksum",
                                packages.digest(stored.tree(before.declaration()))),
                Map.of(
                        "revision",
                        row.revision(),
                        "declaration_checksum",
                        packages.digest(stored.tree(row.declaration()))));
    }

    private static long actor() {
        return Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
    }

    private static ApiException draftMissing() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.form_studio_draft_not_found");
    }
}
