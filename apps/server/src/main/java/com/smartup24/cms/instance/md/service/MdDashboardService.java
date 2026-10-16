package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardDetail;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardRequest;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardSummary;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardWidget;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardWidgetRequest;
import com.smartup24.cms.instance.md.repository.MdDashboardRepository;
import com.smartup24.cms.instance.md.repository.MdDashboardRepository.DashboardRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Named layouts retain unavailable slots but never expose their source definitions (ADR-0036). */
@Service
public class MdDashboardService {
    public static final int MAX_DASHBOARDS = 20;
    private static final String TABLE = "md_dashboards";
    private static final List<String> AUDITED = List.of("name", "widgets", "revision");
    private final MdDashboardRepository repo;
    private final MdReportCatalogService reports;
    private final MdDashboardPeriod periods;
    private final MdReportPreferencesInput input;
    private final AuditLogService audit;
    private final JsonColumns stored;

    public MdDashboardService(
            MdDashboardRepository repo,
            MdReportCatalogService reports,
            MdDashboardPeriod periods,
            MdReportPreferencesInput input,
            AuditLogService audit,
            ObjectMapper json) {
        this.repo = repo;
        this.reports = reports;
        this.periods = periods;
        this.input = input;
        this.audit = audit;
        stored = new JsonColumns(json, TABLE);
    }

    @Transactional(readOnly = true)
    public KeysetPage<DashboardSummary> list(long userId, @Nullable Integer limit, @Nullable String cursor) {
        TimePage page = TimePage.of(limit, cursor, 20, 100);
        return page.page(repo.page(userId, page), row -> new TimePage.Position(row.modifiedAt(), row.id()))
                .map(row -> summary(row, userId));
    }

    @Transactional(readOnly = true)
    public DashboardDetail get(long userId, long id) {
        return detail(repo.readable(userId, id).orElseThrow(MdDashboardService::notFound), userId);
    }

    @Transactional
    public DashboardDetail create(long userId, DashboardRequest request) {
        List<DashboardWidgetRequest> widgets = checked(userId, request.widgets(), List.of());
        repo.lockOwner(userId);
        if (repo.count(userId) >= MAX_DASHBOARDS) throw MdReportPreferencesInput.invalid("name", "limit");
        long id;
        try {
            id = repo.insert(userId, request.name(), stored.write(widgets));
        } catch (DuplicateKeyException duplicate) {
            throw nameTaken();
        }
        DashboardRow created = repo.owned(userId, id, false).orElseThrow();
        audit.logChange(TABLE, Long.toString(id), "I", AUDITED, null, auditRow(created));
        return detail(created, userId);
    }

    @Transactional
    public DashboardDetail update(long userId, long id, long revision, DashboardRequest request) {
        DashboardRow before = owned(userId, id, revision);
        List<DashboardWidgetRequest> widgets = checked(userId, request.widgets(), widgets(before));
        try {
            repo.update(userId, id, revision, request.name(), stored.write(widgets));
        } catch (DuplicateKeyException duplicate) {
            throw nameTaken();
        }
        DashboardRow after = repo.owned(userId, id, false).orElseThrow();
        audit.logChange(TABLE, Long.toString(id), "U", AUDITED, auditRow(before), auditRow(after));
        return detail(after, userId);
    }

    @Transactional
    public void delete(long userId, long id, long revision) {
        DashboardRow before = owned(userId, id, revision);
        repo.delete(userId, id, revision);
        audit.logChange(TABLE, Long.toString(id), "D", AUDITED, auditRow(before), null);
    }

    @Transactional(readOnly = true)
    public DashboardWidget widget(long userId, long id, long revision, long viewId) {
        DashboardRow row = repo.readable(userId, id).orElseThrow(MdDashboardService::notFound);
        if (row.revision() != revision) throw Revisions.conflict();
        DashboardWidgetRequest saved = widgets(row).stream()
                .filter(widget -> widget.viewId() == viewId)
                .findFirst()
                .orElseThrow(MdReportCatalogService::notFound);
        DashboardWidget result = visibleWidget(userId, saved);
        if (!result.available()) throw MdReportCatalogService.notFound();
        return result;
    }

    private DashboardRow owned(long userId, long id, long revision) {
        DashboardRow row = repo.owned(userId, id, true).orElseThrow(MdDashboardService::notFound);
        if (row.revision() != revision) throw Revisions.conflict();
        return row;
    }

    private List<DashboardWidgetRequest> checked(
            long userId, List<DashboardWidgetRequest> proposed, List<DashboardWidgetRequest> previous) {
        if (proposed.size() > MdReportPreferencesInput.MAX_WIDGETS)
            throw MdReportPreferencesInput.invalid("widgets", "limit");
        Map<Long, DashboardWidgetRequest> old =
                previous.stream().collect(Collectors.toMap(DashboardWidgetRequest::viewId, Function.identity()));
        List<DashboardWidgetRequest> result = new ArrayList<>();
        for (DashboardWidgetRequest widget : proposed) {
            DashboardWidgetRequest before = old.get(widget.viewId());
            if (before != null && !visibleWidget(userId, before).available()) {
                if (widget.periodField() != null) throw MdReportCatalogService.notFound();
                result.add(new DashboardWidgetRequest(widget.viewId(), before.periodField(), widget.width()));
                continue;
            }
            var report = reports.require(userId, widget.viewId());
            if (widget.periodField() != null)
                periods.field(report.entity(), MdReportViewState.dataset(report.state()), widget.periodField());
            result.add(widget);
        }
        return List.copyOf(result);
    }

    private DashboardWidget visibleWidget(long userId, DashboardWidgetRequest widget) {
        var report = reports.find(userId, widget.viewId());
        if (report.isEmpty()) return unavailable(widget);
        if (widget.periodField() != null) {
            try {
                periods.field(
                        report.get().entity(),
                        MdReportViewState.dataset(report.get().state()),
                        widget.periodField());
            } catch (ApiException failure) {
                if (failure.getErrorCode() == ErrorCode.VALIDATION_FAILED
                        || failure.getErrorCode() == ErrorCode.NOT_FOUND) {
                    return unavailable(widget);
                }
                throw failure;
            }
        }
        return new DashboardWidget(widget.viewId(), widget.width(), true, widget.periodField(), report.get());
    }

    private static DashboardWidget unavailable(DashboardWidgetRequest widget) {
        return new DashboardWidget(widget.viewId(), widget.width(), false, null, null);
    }

    private DashboardSummary summary(DashboardRow row, long userId) {
        boolean owner = row.ownerId() == userId;
        return new DashboardSummary(
                row.id(),
                row.name(),
                row.revision(),
                row.modifiedAt(),
                owner && SecurityContext.hasPermission("md.profile", "update"),
                owner && SecurityContext.hasPermission("md.settings", "update"),
                row.shared(),
                widgets(row).size());
    }

    private DashboardDetail detail(DashboardRow row, long userId) {
        DashboardSummary summary = summary(row, userId);
        var visible = widgets(row).stream()
                .map(widget -> visibleWidget(userId, widget))
                .toList();
        return new DashboardDetail(
                summary.id(),
                summary.name(),
                summary.revision(),
                summary.modifiedAt(),
                summary.canEdit(),
                summary.canShare(),
                summary.shared(),
                summary.widgetCount(),
                visible);
    }

    private List<DashboardWidgetRequest> widgets(DashboardRow row) {
        return input.widgets(stored.tree(row.widgetsJson()));
    }

    private static Map<String, Object> auditRow(DashboardRow row) {
        return Map.of("name", row.name(), "widgets", row.widgetsJson(), "revision", row.revision());
    }

    public static ApiException notFound() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.md.dashboard_not_found");
    }

    private static ApiException nameTaken() {
        return ApiException.validation("error.md.dashboard_name_taken", List.of());
    }
}
