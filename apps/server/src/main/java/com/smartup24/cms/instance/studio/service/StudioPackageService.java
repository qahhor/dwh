package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ApplyRequest;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.Counts;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ExportPlan;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ExportRequest;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ImportItem;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ImportSummary;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ImportView;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.Template;
import com.smartup24.cms.instance.studio.repository.StudioPackageRepository;
import com.smartup24.cms.instance.studio.repository.StudioPackageRepository.ImportRow;
import com.smartup24.cms.instance.studio.solution.SolutionExporter;
import com.smartup24.cms.instance.studio.solution.SolutionFormat;
import com.smartup24.cms.instance.studio.solution.SolutionInstaller;
import com.smartup24.cms.instance.studio.solution.SolutionItems;
import com.smartup24.cms.instance.studio.solution.SolutionMapping;
import com.smartup24.cms.instance.studio.solution.SolutionRun;
import com.smartup24.cms.instance.studio.solution.SolutionSuffix;
import com.smartup24.cms.instance.studio.solution.SolutionTemplates;
import com.smartup24.cms.instance.studio.solution.SolutionViews;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Solution packages and templates (ADR-0047). An upload is verified and checked at once: the package is applied in a
 * transaction that always rolls back, so the outcome of each item is what the application would do, and only then is
 * the import kept. The application takes the mapping of the placeholders and runs the same steps in one transaction
 * that commits only when no item is refused; otherwise nothing changes and the import keeps the outcome with the
 * reasons. An applied import is history; applying the same package again creates nothing new.
 */
@Service
@Profile("!migrate")
public class StudioPackageService {
    private static final String TABLE = "studio_package_imports";

    private final SolutionFormat format;
    private final SolutionSuffix suffixes;
    private final SolutionInstaller installer;
    private final SolutionExporter exporter;
    private final SolutionTemplates templates;
    private final SolutionMapping mappings;
    private final SolutionViews views;
    private final StudioPackageRepository imports;
    private final AuditLogService audit;
    private final TransactionTemplate checking;
    private final TransactionTemplate keeping;

    public StudioPackageService(
            SolutionFormat format,
            SolutionSuffix suffixes,
            SolutionInstaller installer,
            SolutionExporter exporter,
            SolutionTemplates templates,
            SolutionMapping mappings,
            SolutionViews views,
            StudioPackageRepository imports,
            AuditLogService audit,
            PlatformTransactionManager transactions) {
        this.format = format;
        this.suffixes = suffixes;
        this.installer = installer;
        this.exporter = exporter;
        this.templates = templates;
        this.mappings = mappings;
        this.views = views;
        this.imports = imports;
        this.audit = audit;
        this.checking = new TransactionTemplate(transactions);
        this.checking.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.checking.setName("solution-check");
        this.keeping = new TransactionTemplate(transactions);
        this.keeping.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.keeping.setName("solution-import");
    }

    public ExportPlan plan(ExportRequest request) {
        return exporter.plan(request);
    }

    public byte[] export(ExportRequest request) {
        return exporter.export(request);
    }

    public List<Template> templates() {
        return templates.list();
    }

    /** Verifies and checks an uploaded package, then keeps the import with the outcome of its items. */
    public ImportView upload(byte[] body, @Nullable String suffix) {
        return keep(format.read(body), "upload", suffix);
    }

    /** Checks a template like an upload: creating a solution from it is applying this import. */
    public ImportView fromTemplate(String code, @Nullable String suffix) {
        return keep(templates.get(code).pack(), "template", suffix);
    }

    public ImportView get(long id) {
        return views.view(row(id));
    }

    public KeysetPage<ImportSummary> list(@Nullable Integer limit, @Nullable String cursor) {
        TimePage page = TimePage.of(limit, cursor, 20, 100);
        return page.page(imports.page(page), row -> new TimePage.Position(row.createdAt(), row.id()))
                .map(views::summary);
    }

    /**
     * Applies the import with the mapping, or only checks the mapping ({@code dryRun}). A refused item rolls the
     * application back whole: 422, and the import shows each item's outcome and reasons.
     */
    public ImportView apply(long id, ApplyRequest request) {
        ImportRow seen = row(id);
        if ("applied".equals(seen.status())) throw applied();
        SolutionPackage pack = views.pack(seen);
        boolean dryRun = Boolean.TRUE.equals(request.dryRun());
        Map<String, List<Long>> mapping = mappings.check(pack, request.mapping(), !dryRun);
        SolutionPackage named = suffixes.apply(pack, seen.suffix());
        long user = currentUser();
        if (dryRun) {
            List<ImportItem> items = check(named, scope(seen), mapping, id);
            keeping.executeWithoutResult(status -> imports.checked(id, views.write(items), views.write(mapping)));
            return views.view(row(id));
        }
        List<ImportItem> outcome = keeping.execute(status -> {
            ImportRow locked = imports.lock(id).orElseThrow(StudioPackageService::notFound);
            if ("applied".equals(locked.status())) throw applied();
            List<ImportItem> items = installer.run(new SolutionRun(named, scope(locked), mapping, true, id, user));
            if (SolutionItems.counts(items).refused() > 0) {
                status.setRollbackOnly();
                return items;
            }
            imports.applied(id, views.write(items), views.write(mapping), user);
            audit.logChange(
                    TABLE,
                    Long.toString(id),
                    "U",
                    List.of("status", "mapping"),
                    Map.of("status", "preview"),
                    auditedApplication(locked.code(), SolutionItems.counts(items)));
            return items;
        });
        List<ImportItem> items = Objects.requireNonNull(outcome);
        int refused = SolutionItems.counts(items).refused();
        if (refused > 0) {
            keeping.executeWithoutResult(status -> imports.checked(id, views.write(items), views.write(mapping)));
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "error.studio.solution_refused", Map.of("count", refused));
        }
        return views.view(row(id));
    }

    private ImportView keep(SolutionPackage pack, String source, @Nullable String suffix) {
        SolutionPackage named = suffixes.apply(pack, suffix);
        List<ImportItem> items = check(named, scope(pack.code(), suffix), Map.of(), 0);
        long user = currentUser();
        Long id = keeping.execute(status -> {
            long created = imports.insert(
                    pack.code(),
                    pack.version(),
                    views.write(pack.title()),
                    pack.checksum(),
                    source,
                    suffix,
                    views.write(pack),
                    views.write(items),
                    user);
            audit.logChange(
                    TABLE,
                    Long.toString(created),
                    "I",
                    List.of("code", "version", "checksum", "source", "suffix"),
                    Map.of(),
                    Map.of(
                            "code", pack.code(),
                            "version", pack.version(),
                            "checksum", pack.checksum(),
                            "source", source,
                            "suffix", Objects.requireNonNullElse(suffix, "")));
            return created;
        });
        return views.view(row(Objects.requireNonNull(id)));
    }

    /** Applies the package in a transaction that always rolls back: the outcome of each item, nothing kept. */
    private List<ImportItem> check(SolutionPackage named, String scope, Map<String, List<Long>> mapping, long id) {
        long user = currentUser();
        return Objects.requireNonNull(checking.execute(status -> {
            status.setRollbackOnly();
            return installer.run(new SolutionRun(named, scope, mapping, false, id, user));
        }));
    }

    private static Map<String, Object> auditedApplication(String code, Counts counts) {
        return Map.of(
                "status", "applied",
                "code", code,
                "created", counts.create(),
                "upgraded", counts.upgrade(),
                "unchanged", counts.unchanged(),
                "skipped", counts.skip());
    }

    private ImportRow row(long id) {
        return imports.find(id).orElseThrow(StudioPackageService::notFound);
    }

    private static String scope(ImportRow row) {
        return scope(row.code(), row.suffix());
    }

    /** What the objects of an import are remembered under: the package code with the suffix it is applied under. */
    private static String scope(String code, @Nullable String suffix) {
        return code + Objects.requireNonNullElse(suffix, "");
    }

    private static long currentUser() {
        return Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
    }

    private static ApiException applied() {
        return ApiException.conflict(ErrorCode.CONFLICT, "error.studio.solution_applied");
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.studio.solution_not_found");
    }
}
