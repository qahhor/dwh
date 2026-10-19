package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.md.service.MdRoleService;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.FormDetail;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Grant;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageList;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Permission;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Requires;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ExportPlan;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ExportRequest;
import com.smartup24.cms.instance.studio.service.StudioFormViews;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Makes a solution package of the administrator's selection (ADR-0047): the forms the selected items stand on and the
 * forms those refer to, the lists of their fields and columns, the datasets of the selected reports and the reports of
 * the selected mailings are added as dependencies. The plan shows what the package will hold, why, and what else uses
 * its forms; the export writes it with its checksum. No record data, user id or secret goes in.
 */
@Component
@Profile("!migrate")
public class SolutionExporter {
    private final StudioFormViews forms;
    private final SolutionSources sources;
    private final SolutionRelated related;
    private final MdRoleService roles;
    private final MdI18nService i18n;
    private final SolutionFormat format;
    private final String version;

    public SolutionExporter(
            StudioFormViews forms,
            SolutionSources sources,
            SolutionRelated related,
            MdRoleService roles,
            MdI18nService i18n,
            SolutionFormat format,
            ObjectProvider<BuildProperties> build) {
        this.forms = forms;
        this.sources = sources;
        this.related = related;
        this.roles = roles;
        this.i18n = i18n;
        this.format = format;
        this.version = Optional.ofNullable(build.getIfAvailable())
                .map(BuildProperties::getVersion)
                .orElse("development");
    }

    public ExportPlan plan(ExportRequest request) {
        Built built = build(request);
        return new ExportPlan(
                built.selection().items(),
                related.of(built.selection()),
                built.pack().placeholders());
    }

    /** The file of the package, sealed with its checksum. */
    public byte[] export(ExportRequest request) {
        return format.seal(build(request).pack());
    }

    private Built build(ExportRequest request) {
        SolutionSelection selection = new SolutionSelection(forms, sources).pick(request);
        SolutionPlaceholders placeholders = new SolutionPlaceholders(i18n::effectiveDictionary);
        List<FieldErrorItem> problems = new ArrayList<>();
        boolean items = !Boolean.FALSE.equals(request.listItems());
        List<PackageList> lists = selection.lists().stream()
                .map(code -> sources.list(code, items))
                .toList();
        List<DeliveryPackage> declarations = selection.forms().values().stream()
                .map(SolutionExporter::delivery)
                .toList();
        var datasets = selection.datasets().values().stream()
                .map(dataset -> sources.dataset(dataset, problems))
                .toList();
        var reports = selection.reports().values().stream()
                .map(report -> sources.report(report, placeholders, problems))
                .toList();
        var mailings = selection.mailings().values().stream()
                .map(mailing ->
                        sources.mailing(mailing, selection.reports().get(mailing.viewId()), placeholders, problems))
                .toList();
        var automations = selection.automations().values().stream()
                .map(automation -> sources.automation(automation, placeholders))
                .toList();
        List<Grant> grants = grants(selection, placeholders);
        if (!problems.isEmpty()) throw ApiException.validation("error.studio.solution_record_values", problems);
        int newest = declarations.stream()
                .mapToInt(form -> form.declaration().formatVersion())
                .max()
                .orElse(1);
        SolutionPackage pack = new SolutionPackage(
                StudioPackageDtos.FORMAT,
                StudioPackageDtos.KIND,
                request.code(),
                request.version(),
                request.title(),
                request.description(),
                new Requires(newest),
                version,
                placeholders.all(),
                lists,
                declarations,
                datasets,
                reports,
                mailings,
                automations,
                grants,
                null);
        SolutionChecks.require(pack);
        return new Built(selection, pack);
    }

    /** The rights the roles that are not administrative hold on the package's forms, each role a placeholder. */
    private List<Grant> grants(SolutionSelection selection, SolutionPlaceholders placeholders) {
        List<Grant> grants = new ArrayList<>();
        for (var role : roles.grantsOn(selection.forms().keySet())) {
            List<Permission> permissions = role.permissions().stream()
                    .map(pair -> new Permission(pair.formCode(), pair.action()))
                    .toList();
            grants.add(new Grant(placeholders.role(role.id(), role.name()), permissions));
        }
        return List.copyOf(grants);
    }

    private static DeliveryPackage delivery(FormDetail form) {
        Declaration declaration = form.declaration();
        return new DeliveryPackage(1, "simple-form-package", declaration, form.checksum());
    }

    private record Built(SolutionSelection selection, SolutionPackage pack) {}
}
