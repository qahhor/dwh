package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdReportCatalogService;
import com.smartup24.cms.instance.md.service.MdReportDatasetService;
import com.smartup24.cms.instance.report.service.ReportMailingService;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ExportItem;
import com.smartup24.cms.instance.studio.service.StudioAutomationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * What else uses the forms of a package being made (ADR-0047): the datasets over them, the reports on their lists that
 * the administrator may read, the mailings of the package's reports and the automations of its forms — offered to add,
 * each read with the right its own screen asks. At most one page of each is offered.
 */
@Component
public class SolutionRelated {
    private static final int PAGE = 50;
    private static final String RELATED = "related";

    private final MdReportDatasetService datasets;
    private final MdReportCatalogService catalog;
    private final ReportMailingService mailings;
    private final StudioAutomationService automations;

    public SolutionRelated(
            MdReportDatasetService datasets,
            MdReportCatalogService catalog,
            ReportMailingService mailings,
            StudioAutomationService automations) {
        this.datasets = datasets;
        this.catalog = catalog;
        this.mailings = mailings;
        this.automations = automations;
    }

    List<ExportItem> of(SolutionSelection selection) {
        List<ExportItem> related = new ArrayList<>();
        long user = Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
        if (SecurityContext.hasPermission("md.report_datasets", "view")) {
            for (var dataset : datasets.list(PAGE, null).items()) {
                if (selection.forms().containsKey(dataset.entity()) && !selection.holds("dataset", dataset.code()))
                    related.add(new ExportItem(
                            "dataset", dataset.code(), dataset.name(), RELATED, "form:" + dataset.entity()));
            }
        }
        for (String form : selection.forms().keySet()) {
            for (var report : catalog.list(user, form, PAGE, null).items()) {
                if ("report".equals(report.kind()) && !selection.holds("report", Long.toString(report.id())))
                    related.add(new ExportItem(
                            "report",
                            Long.toString(report.id()),
                            SolutionItems.same(report.name()),
                            RELATED,
                            "form:" + form));
            }
            if (!SecurityContext.hasPermission("studio.automations", "view")) continue;
            for (var automation : automations.list(form, PAGE, null).items()) {
                if (!selection.holds("automation", Long.toString(automation.id())))
                    related.add(new ExportItem(
                            "automation",
                            Long.toString(automation.id()),
                            SolutionItems.same(automation.name()),
                            RELATED,
                            "form:" + form));
            }
        }
        if (SecurityContext.hasPermission("report.mailings", "manage")) {
            for (Long report : selection.reports().keySet()) {
                for (var mailing : mailings.list(report, PAGE, null).items()) {
                    if (!selection.holds("mailing", Long.toString(mailing.id())))
                        related.add(new ExportItem(
                                "mailing",
                                Long.toString(mailing.id()),
                                SolutionItems.same(mailing.name()),
                                RELATED,
                                "report:" + report));
                }
            }
        }
        return List.copyOf(related);
    }
}
