package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.service.MdPrincipals;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Grant;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageAutomation;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageDataset;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageList;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageMailing;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageReport;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ImportItem;
import com.smartup24.cms.instance.studio.service.StudioDefinitions;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Applies the items of a solution package in the order they depend on each other (ADR-0047): lists, forms, then — with
 * the catalog and the importer's rights read again, since the new forms and their rights exist only in this
 * transaction — grants, datasets, reports, mailings and automations. The caller owns the transaction: a check rolls it
 * back, an application commits it only when no item was refused.
 */
@Component
@Profile("!migrate")
public class SolutionInstaller {
    private final SolutionItems items;
    private final SolutionCatalogSteps catalog;
    private final SolutionReportSteps reports;
    private final SolutionRunSteps runs;
    private final StudioDefinitions definitions;
    private final MdPrincipals principals;

    public SolutionInstaller(
            SolutionItems items,
            SolutionCatalogSteps catalog,
            SolutionReportSteps reports,
            SolutionRunSteps runs,
            StudioDefinitions definitions,
            MdPrincipals principals) {
        this.items = items;
        this.catalog = catalog;
        this.reports = reports;
        this.runs = runs;
        this.definitions = definitions;
        this.principals = principals;
    }

    public List<ImportItem> run(SolutionRun run) {
        SolutionPackage pack = run.pack();
        List<ImportItem> done = new ArrayList<>();
        for (PackageList list : pack.lists()) {
            done.add(items.run("list", list.code(), list.name(), () -> catalog.list(list)));
        }
        for (DeliveryPackage form : pack.forms()) {
            String code = "studio." + form.declaration().entity().name();
            done.add(items.run("form", code, form.declaration().entity().title(), () -> catalog.form(form)));
            // The next form may refer to this one: the request reads the catalog of its own transaction again.
            definitions.repin();
        }
        principals.runAs(run.importer(), () -> done.addAll(dependents(run)));
        return List.copyOf(done);
    }

    private List<ImportItem> dependents(SolutionRun run) {
        SolutionPackage pack = run.pack();
        List<ImportItem> done = new ArrayList<>();
        for (Grant grant : pack.grants()) {
            done.add(items.run("grant", grant.role(), run.title(grant.role()), () -> runs.grant(run, grant)));
        }
        for (PackageDataset dataset : pack.datasets()) {
            done.add(items.run("dataset", dataset.code(), dataset.name(), () -> reports.dataset(dataset)));
        }
        for (PackageReport report : pack.reports()) {
            done.add(items.run(
                    "report", report.key(), SolutionItems.same(report.name()), () -> reports.report(run, report)));
        }
        for (PackageMailing mailing : pack.mailings()) {
            done.add(items.run(
                    "mailing", mailing.key(), SolutionItems.same(mailing.name()), () -> runs.mailing(run, mailing)));
        }
        for (PackageAutomation automation : pack.automations()) {
            done.add(items.run(
                    "automation",
                    automation.key(),
                    SolutionItems.same(automation.name()),
                    () -> runs.automation(run, automation)));
        }
        return done;
    }
}
