package com.smartup24.cms.instance.upl.upload;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.actor.AuditActor;
import com.smartup24.cms.instance.common.actor.AuditActorContext;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.upl.api.UplPackageDtos.PackageErrors;
import com.smartup24.cms.instance.upl.api.UplPackageDtos.PackageItem;
import com.smartup24.cms.instance.upl.parse.UplParseResult;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.ErrorRow;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.ErrorsView;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.NewPackage;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upload packages: recording an accepted file, the parse result and reading the list with errors.
 * Applying a package is not handled here (see {@link UplApplyService}).
 */
@Service
public class UplPackageService {

    private static final int MAX_LIMIT = 200;

    private final UplPackageRepository repo;
    private final AuditActorContext actors;

    public UplPackageService(UplPackageRepository repo, AuditActorContext actors) {
        this.repo = repo;
        this.actors = actors;
    }

    /**
     * Records an accepted file as a package in status "received". Joins the caller's transaction:
     * the package and its parse job appear together or not at all.
     */
    @Transactional
    public PackageRow register(NewPackage p) {
        AuditActor actor = actors.user(p.uploadedById());
        actors.apply(actor);
        long id = repo.insert(p, actor.name());
        return repo.findById(id)
                .orElseThrow(() -> new IllegalStateException("Пакет " + id + " не найден сразу после записи"));
    }

    /** Package by its API identifier; a string that is not a uuid or a missing package gives 404. */
    @Transactional(readOnly = true)
    public PackageRow get(String publicId) {
        return find(toUuid(publicId))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.upl.pkg_not_found"));
    }

    @Transactional(readOnly = true)
    public Optional<PackageRow> find(UUID publicId) {
        return repo.findByPublicId(publicId);
    }

    /** Package errors: how many were found in total and the stored records in order. */
    @Transactional(readOnly = true)
    public ErrorsView errors(String publicId) {
        PackageRow row = get(publicId);
        int total = row.errorsTotal() == null ? 0 : row.errorsTotal();
        return new ErrorsView(total, repo.findErrors(row.id()));
    }

    /** Package list by the registry: newest first by default; filter, sort and search follow ADR-0016. */
    @Transactional(readOnly = true)
    public KeysetPage<PackageRow> list(int limit, String cursor) {
        return list(limit, cursor, null, null, null);
    }

    @Transactional(readOnly = true)
    public KeysetPage<PackageRow> list(Integer limit, String cursor, String filter, String sort, String search) {
        return repo.pagePackages(QueryCompiler.compile(UplPackageQuery.LIST, filter, sort, limit, cursor, search));
    }

    /** One package as the API answers it (plan 10/10, item 3.2: the controller sees DTOs, not rows). */
    @Transactional(readOnly = true)
    public PackageItem item(String publicId) {
        return PackageItem.of(get(publicId));
    }

    /** A page of packages as the API answers it. */
    @Transactional(readOnly = true)
    public KeysetPage<PackageItem> items(Integer limit, String cursor, String filter, String sort, String search) {
        // KeysetPage.map keeps whether the total is a count or an estimate.
        return list(limit, cursor, filter, sort, search).map(PackageItem::of);
    }

    /** The errors of a package as the API answers them. */
    @Transactional(readOnly = true)
    public PackageErrors errorItems(String publicId) {
        return PackageErrors.of(errors(publicId));
    }

    /** The errors of a package as an xlsx file, its texts from {@code text} (a dictionary key and its parameters). */
    @Transactional(readOnly = true)
    public UplErrorReportBuilder.ReportFile errorReport(
            String publicId, UplErrorReportBuilder reports, BiFunction<String, Map<String, Object>, String> text) {
        return reports.build(get(publicId), errors(publicId), text);
    }

    /** Records the parse result: status, counters and the first stored errors. */
    @Transactional
    public void saveParseResult(long id, UplParseResult result) {
        actors.apply(actors.system());
        int updated = result.outcome() == UplParseResult.Outcome.VERIFIED
                ? repo.markVerified(
                        id, result.rowsTotal(), result.rowsAccepted(), result.rowsRejected(), result.errorsTotal())
                : repo.markRejected(id, result.rejectCode(), result.rejectParams(), result.errorsTotal());
        if (updated == 0) {
            throw new IllegalStateException("Пакет " + id + " уже не в статусе «получен»");
        }
        repo.insertErrors(id, numbered(result.errors()));
    }

    /**
     * Rejects the package in a separate transaction: it is called from a failed job whose transaction will be
     * rolled back. A package that is already closed is not an error.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejectInNewTransaction(long id, String rejectCode) {
        actors.apply(actors.system());
        repo.markRejected(id, rejectCode, Map.of(), null);
    }

    private static List<ErrorRow> numbered(List<UplParseResult.ErrorRecord> errors) {
        List<ErrorRow> rows = new ArrayList<>(errors.size());
        int ordinal = 1;
        for (UplParseResult.ErrorRecord error : errors) {
            rows.add(new ErrorRow(
                    ordinal,
                    error.sheet(),
                    error.rowNo(),
                    error.columnName(),
                    error.cellValue(),
                    error.code(),
                    error.params()));
            ordinal++;
        }
        return rows;
    }

    private static UUID toUuid(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.upl.pkg_not_found");
        }
        try {
            return UUID.fromString(publicId);
        } catch (IllegalArgumentException notUuid) {
            throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.upl.pkg_not_found");
        }
    }
}
