package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdListViewDtos;
import com.smartup24.cms.instance.md.api.MdListViewDtos.ViewRequest;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportViewItem;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.Dataset;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.DatasetRequest;
import com.smartup24.cms.instance.md.service.MdListViewService;
import com.smartup24.cms.instance.md.service.MdPreferenceSharing;
import com.smartup24.cms.instance.md.service.MdReportCatalogService;
import com.smartup24.cms.instance.md.service.MdReportDatasetService;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageDataset;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageReport;
import com.smartup24.cms.instance.studio.repository.StudioPackageRepository;
import com.smartup24.cms.instance.studio.solution.SolutionItems.Done;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The report datasets and saved reports of a solution package (ADR-0047). A dataset is found by its code and changed
 * when the package's differs; a report has no code, so the report an earlier application created is found by the
 * package and the item's key, and changed when the package's item changed since. The importer owns the reports; a
 * report is shared only with the roles its role placeholders are mapped to.
 */
@Component
public class SolutionReportSteps {
    static final String REPORT = "report";

    private final MdReportDatasetService datasets;
    private final MdListViewService views;
    private final MdReportCatalogService catalog;
    private final MdPreferenceSharing sharing;
    private final StudioPackageRepository objects;
    private final SolutionFormat format;
    private final ObjectMapper json;

    public SolutionReportSteps(
            MdReportDatasetService datasets,
            MdListViewService views,
            MdReportCatalogService catalog,
            MdPreferenceSharing sharing,
            StudioPackageRepository objects,
            SolutionFormat format,
            ObjectMapper json) {
        this.datasets = datasets;
        this.views = views;
        this.catalog = catalog;
        this.sharing = sharing;
        this.objects = objects;
        this.format = format;
        this.json = json;
    }

    public Done dataset(PackageDataset dataset) {
        SolutionCatalogSteps.require("md.report_datasets", "manage");
        DatasetRequest request = new DatasetRequest(
                dataset.code(),
                dataset.name(),
                dataset.entity(),
                dataset.lines(),
                dataset.fields(),
                dataset.filter(),
                dataset.measures());
        Optional<Dataset> current = datasets.find(dataset.code());
        if (current.isEmpty()) {
            datasets.create(request);
            return new Done(SolutionItems.CREATE);
        }
        Dataset held = current.get();
        boolean same = SolutionCatalogSteps.same(held.name(), dataset.name())
                && held.entity().equals(dataset.entity())
                && Objects.equals(held.lines(), dataset.lines())
                && held.fields().equals(dataset.fields())
                && json.valueToTree(held.filter()).equals(json.valueToTree(dataset.filter()))
                && json.valueToTree(held.measures()).equals(json.valueToTree(dataset.measures()));
        if (same) return new Done(SolutionItems.UNCHANGED);
        datasets.update(dataset.code(), Revisions.etag(held.revision()), request);
        return new Done(SolutionItems.UPGRADE);
    }

    public Done report(SolutionRun run, PackageReport report) {
        SolutionCatalogSteps.require("md.profile", "update");
        String digest = format.digest(report);
        ViewRequest request = new ViewRequest(report.name(), report.state(), false, MdListViewDtos.REPORT);
        Optional<StudioPackageRepository.ObjectRow> remembered = objects.object(run.scope(), REPORT, report.key());
        Optional<ReportViewItem> earlier = remembered.flatMap(row -> catalog.find(run.importer(), row.objectId())
                .filter(item -> item.listCode().equals(report.list())));
        Done done;
        long id;
        int revision;
        if (earlier.isEmpty()) {
            var created = views.create(run.importer(), report.list(), request);
            id = created.id();
            revision = created.lockVersion();
            done = new Done(SolutionItems.CREATE);
        } else if (digest.equals(remembered.get().checksum())) {
            id = earlier.get().id();
            revision = earlier.get().lockVersion();
            done = new Done(SolutionItems.UNCHANGED);
        } else {
            if (!earlier.get().owned()) throw new ApiException(ErrorCode.CONFLICT, "error.studio.solution_owner");
            var changed = views.update(
                    run.importer(),
                    report.list(),
                    earlier.get().id(),
                    earlier.get().lockVersion(),
                    request);
            id = changed.id();
            revision = changed.lockVersion();
            done = new Done(SolutionItems.UPGRADE);
        }
        List<Long> roles = report.share().stream()
                .map(run::role)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!roles.isEmpty() && !SolutionItems.UNCHANGED.equals(done.outcome()))
            sharing.shareReport(run.importer(), report.list(), id, revision, roles);
        if (roles.size() < report.share().size()) done = done.with("studio.packages.note.not_shared");
        run.reports().put(report.key(), id);
        if (run.apply()) objects.remember(run.scope(), REPORT, report.key(), id, digest, run.importId());
        return done;
    }
}
