package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.SharingResponse;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.SharingRole;
import com.smartup24.cms.instance.md.repository.MdDashboardRepository;
import com.smartup24.cms.instance.md.repository.MdReportAccessRepository;
import com.smartup24.cms.instance.md.repository.MdSharingRepository;
import com.smartup24.cms.instance.md.repository.MdSharingRepository.Resource;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only owners with settings access can replace a preference's explicit role ACL (ADR-0036). */
@Service
public class MdPreferenceSharing {
    private final MdSharingRepository shares;
    private final MdReportAccessRepository reports;
    private final MdDashboardRepository dashboards;
    private final MdReportCatalogService catalog;
    private final AuditLogService audit;

    public MdPreferenceSharing(
            MdSharingRepository shares,
            MdReportAccessRepository reports,
            MdDashboardRepository dashboards,
            MdReportCatalogService catalog,
            AuditLogService audit) {
        this.shares = shares;
        this.reports = reports;
        this.dashboards = dashboards;
        this.catalog = catalog;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public KeysetPage<SharingRole> roles(@Nullable Integer limit, @Nullable String cursor) {
        requirePermission();
        TimePage page = TimePage.of(limit, cursor, 20, 100);
        return page.page(
                        shares.page(page),
                        row -> new TimePage.Position(
                                row.modifiedAt(), row.value().id()))
                .map(MdSharingRepository.RoleRow::value);
    }

    @Transactional(readOnly = true)
    public SharingResponse report(long userId, String listCode, long id) {
        requirePermission();
        var row = reports.owned(userId, listCode, id, false).orElseThrow(MdReportCatalogService::notFound);
        if (catalog.visible(row).isEmpty()) throw MdReportCatalogService.notFound();
        return new SharingResponse(row.view().lockVersion(), shares.roles(Resource.REPORT, id));
    }

    @Transactional
    public SharingResponse shareReport(long userId, String listCode, long id, long revision, List<Long> roles) {
        requirePermission();
        var row = reports.owned(userId, listCode, id, true).orElseThrow(MdReportCatalogService::notFound);
        if (catalog.visible(row).isEmpty()) throw MdReportCatalogService.notFound();
        if (row.view().lockVersion() != revision) throw Revisions.conflict();
        validate(roles);
        long next = reports.touch(userId, id, revision);
        replace(Resource.REPORT, id, roles);
        return new SharingResponse(next, shares.roles(Resource.REPORT, id));
    }

    @Transactional(readOnly = true)
    public SharingResponse dashboard(long userId, long id) {
        requirePermission();
        var row = dashboards.owned(userId, id, false).orElseThrow(MdDashboardService::notFound);
        return new SharingResponse(row.revision(), shares.roles(Resource.DASHBOARD, id));
    }

    @Transactional
    public SharingResponse shareDashboard(long userId, long id, long revision, List<Long> roles) {
        requirePermission();
        var row = dashboards.owned(userId, id, true).orElseThrow(MdDashboardService::notFound);
        if (row.revision() != revision) throw Revisions.conflict();
        validate(roles);
        long next = dashboards.touch(userId, id, revision);
        replace(Resource.DASHBOARD, id, roles);
        return new SharingResponse(next, shares.roles(Resource.DASHBOARD, id));
    }

    private void validate(List<Long> roles) {
        if (roles.size() > MdReportPreferencesInput.MAX_ROLES)
            throw MdReportPreferencesInput.invalid("roleIds", "limit");
        if (!shares.active(roles))
            throw ApiException.validation(
                    "error.md.report_preferences_invalid",
                    List.of(FieldErrorItem.keyed("roleIds", "ROLE_INVALID", "error.md.report_sharing_role_invalid")));
    }

    private void replace(Resource resource, long id, List<Long> roles) {
        List<Long> before = shares.roles(resource, id);
        shares.replace(resource, id, roles);
        audit.logChange(
                resource.table(),
                Long.toString(id),
                "U",
                List.of("role_ids"),
                Map.of("role_ids", before),
                Map.of("role_ids", List.copyOf(roles)));
    }

    private static void requirePermission() {
        if (!SecurityContext.hasPermission("md.settings", "update")) throw new ApiException(ErrorCode.FORBIDDEN);
    }
}
