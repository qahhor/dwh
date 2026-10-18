package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.entity.report.EntityReports;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportCatalogFilter;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportViewItem;
import com.smartup24.cms.instance.md.repository.MdListViewRepository.ListView;
import com.smartup24.cms.instance.md.repository.MdReportAccessRepository;
import com.smartup24.cms.instance.md.repository.MdReportAccessRepository.ReportRow;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** A shared definition is returned only after validating its complete state for the current reader (ADR-0036). */
@Service
public class MdReportCatalogService {
    private final MdReportAccessRepository repo;
    private final EntityReports reports;
    private final MdReportViewState validation;
    private final JsonColumns stored;

    public MdReportCatalogService(
            MdReportAccessRepository repo, EntityReports reports, MdReportViewState validation, ObjectMapper json) {
        this.repo = repo;
        this.reports = reports;
        this.validation = validation;
        stored = new JsonColumns(json, "md_list_views");
    }

    @Transactional(readOnly = true)
    public KeysetPage<ReportViewItem> list(
            long userId, @Nullable String listCode, @Nullable Integer limit, @Nullable String cursor) {
        return list(userId, new ReportCatalogFilter(listCode, null, null, null), limit, cursor);
    }

    /**
     * The catalog of reports the reader may open (ADR-0036): owned and role-shared reports and widgets, narrowed by list,
     * name, ownership and kind, each revalidated for the reader's fields; an incomplete page may still go on.
     */
    @Transactional(readOnly = true)
    public KeysetPage<ReportViewItem> list(
            long userId, ReportCatalogFilter filter, @Nullable Integer limit, @Nullable String cursor) {
        TimePage page = TimePage.of(limit, cursor, 20, 100);
        var candidates = page.page(
                repo.page(userId, filter, page),
                row -> new TimePage.Position(row.view().modifiedAt(), row.view().id()));
        var visible = candidates.items().stream()
                .flatMap(row -> visible(row).stream())
                .toList();
        return new KeysetPage<>(
                visible, candidates.nextCursor(), candidates.hasMore(), visible.size(), candidates.totalExact());
    }

    @Transactional(readOnly = true)
    public Optional<ReportViewItem> find(long userId, long id) {
        return repo.readable(userId, id).flatMap(this::visible);
    }

    public ReportViewItem require(long userId, long id) {
        return find(userId, id).orElseThrow(MdReportCatalogService::notFound);
    }

    public Optional<ReportViewItem> visible(ReportRow row) {
        return visible(row.view(), row.ownerId(), row.shared());
    }

    public Optional<ReportViewItem> visible(ListView view, long ownerId, boolean shared) {
        var entity = reports.entityOfList(view.listCode());
        if (entity.isEmpty()) return Optional.empty();
        try {
            var state = stored.tree(view.stateJson());
            String canonical =
                    validation.canonical(reports.list(entity.get(), MdReportViewState.dataset(state)), state);
            boolean owner = Long.valueOf(ownerId).equals(SecurityContext.getCurrentUserId());
            return Optional.of(new ReportViewItem(
                    view.id(),
                    entity.get(),
                    view.listCode(),
                    view.kind(),
                    view.name(),
                    stored.tree(canonical),
                    view.lockVersion(),
                    view.modifiedAt(),
                    owner && SecurityContext.hasPermission("md.profile", "update"),
                    owner && SecurityContext.hasPermission("md.settings", "update"),
                    shared,
                    owner));
        } catch (ApiException failure) {
            if (failure.getErrorCode() == ErrorCode.VALIDATION_FAILED
                    || failure.getErrorCode() == ErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw failure;
        }
    }

    public static ApiException notFound() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.report_not_found");
    }
}
