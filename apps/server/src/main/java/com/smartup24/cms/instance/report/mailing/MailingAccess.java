package com.smartup24.cms.instance.report.mailing;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportViewItem;
import com.smartup24.cms.instance.md.service.MdReportCatalogService;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Who keeps the mailings of a saved report (ADR-0045): a person who may read the report and either holds
 * {@code report.mailings.manage} or owns the report and holds {@code report.mailings.own}. A report the person may not
 * read answers 404, as the catalog does; a readable report without the right answers 403.
 */
@Component
public class MailingAccess {
    public static final String FORM = "report.mailings";
    public static final String OWN = "own";
    public static final String MANAGE = "manage";

    private final MdReportCatalogService catalog;

    public MailingAccess(MdReportCatalogService catalog) {
        this.catalog = catalog;
    }

    /** The saved report whose mailings the current person keeps; refused otherwise. */
    public ReportViewItem report(long viewId) {
        long user = currentUser();
        ReportViewItem report = catalog.find(user, viewId)
                .filter(item -> "report".equals(item.kind()))
                .orElseThrow(MdReportCatalogService::notFound);
        if (SecurityContext.hasPermission(FORM, MANAGE)) return report;
        if (report.owned() && SecurityContext.hasPermission(FORM, OWN)) return report;
        throw ApiException.permissionDenied(FORM, report.owned() ? OWN : MANAGE);
    }

    /** Whether the current person keeps the mailings of every report they may read. */
    public static boolean managesAll() {
        return SecurityContext.hasPermission(FORM, MANAGE);
    }

    public static long currentUser() {
        return Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
    }

    public static ApiException mailingNotFound() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.report.mailing_not_found");
    }
}
