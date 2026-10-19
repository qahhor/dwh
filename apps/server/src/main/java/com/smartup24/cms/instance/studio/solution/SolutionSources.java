package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.report.EntityReportDatasetStore;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportViewItem;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.Dataset;
import com.smartup24.cms.instance.md.api.MdRoleDtos.RoleView;
import com.smartup24.cms.instance.md.service.MdPreferenceSharing;
import com.smartup24.cms.instance.md.service.MdReportCatalogService;
import com.smartup24.cms.instance.md.service.MdReportDatasetService;
import com.smartup24.cms.instance.md.service.MdRoleService;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Mailing;
import com.smartup24.cms.instance.report.service.ReportMailingService;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Automation;
import com.smartup24.cms.instance.studio.api.StudioListDtos.ListDetail;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Action;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.ListItem;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageAutomation;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageDataset;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageList;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageMailing;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageReport;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Recipients;
import com.smartup24.cms.instance.studio.service.StudioAutomationService;
import com.smartup24.cms.instance.studio.service.StudioListService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the lists, datasets, reports, mailings and automations of this installation as parts of a package (ADR-0047),
 * each through the path and with the rights its own screen uses. Fixed users and roles become placeholders; a value
 * that names a record of this installation is reported, never carried.
 */
@Component
@Profile("!migrate")
public class SolutionSources {
    static final String AUTOMATION_TEXT = "studio.packages.placeholder.automation";
    static final String MAILING_TEXT = "studio.packages.placeholder.mailing";

    private final StudioListService lists;
    private final MdReportDatasetService datasets;
    private final MdReportCatalogService catalog;
    private final MdPreferenceSharing sharing;
    private final MdRoleService roles;
    private final ReportMailingService mailings;
    private final StudioAutomationService automations;
    private final QueryListRegistry registry;
    private final ObjectMapper json;

    public SolutionSources(
            StudioListService lists,
            MdReportDatasetService datasets,
            MdReportCatalogService catalog,
            MdPreferenceSharing sharing,
            MdRoleService roles,
            ReportMailingService mailings,
            StudioAutomationService automations,
            QueryListRegistry registry,
            ObjectMapper json) {
        this.lists = lists;
        this.datasets = datasets;
        this.catalog = catalog;
        this.sharing = sharing;
        this.roles = roles;
        this.mailings = mailings;
        this.automations = automations;
        this.registry = registry;
        this.json = json;
    }

    public PackageList list(String code, boolean withItems) {
        SolutionCatalogSteps.require("studio.lists", "view");
        ListDetail detail = lists.get(code);
        List<ListItem> items = withItems
                ? detail.items().stream()
                        .map(item -> new ListItem(item.code(), item.name(), item.sortOrder(), item.active()))
                        .toList()
                : List.of();
        return new PackageList(code, detail.list().name(), items);
    }

    public Dataset dataset(String code) {
        SolutionCatalogSteps.require("md.report_datasets", "view");
        return datasets.get(code);
    }

    public PackageDataset dataset(Dataset dataset, List<FieldErrorItem> problems) {
        QueryList fields = datasets.fields(dataset.entity(), dataset.lines());
        SolutionValues.filter(fields, json.valueToTree(dataset.filter()), "datasets." + dataset.code(), problems);
        return new PackageDataset(
                dataset.code(),
                dataset.name(),
                dataset.entity(),
                dataset.lines(),
                dataset.fields(),
                dataset.filter(),
                dataset.measures());
    }

    public ReportViewItem report(long id) {
        long user = Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
        return catalog.find(user, id)
                .filter(item -> "report".equals(item.kind()))
                .orElseThrow(MdReportCatalogService::notFound);
    }

    /** The dataset code a report reads, or null when it reads its list. */
    public static @Nullable String datasetOf(JsonNode state) {
        String dataset = state.path("dataset").asString(null);
        return dataset != null && dataset.startsWith(EntityReportDatasetStore.PREFIX)
                ? dataset.substring(EntityReportDatasetStore.PREFIX.length())
                : null;
    }

    public PackageReport report(ReportViewItem item, SolutionPlaceholders placeholders, List<FieldErrorItem> problems) {
        QueryList fields = fields(item);
        String at = "reports." + item.id();
        SolutionValues.filter(fields, item.state().get("filter"), at, problems);
        SolutionValues.parameters(fields, item.state().get("parameters"), at, problems);
        List<String> share = List.of();
        if (item.owned() && SecurityContext.hasPermission("md.settings", "update")) {
            long user = Objects.requireNonNull(SecurityContext.getCurrentUserId(), "user");
            Map<Long, String> names =
                    roles.listRoles().stream().collect(Collectors.toMap(RoleView::id, RoleView::name));
            share = sharing.report(user, item.listCode(), item.id()).roleIds().stream()
                    .filter(names::containsKey)
                    .map(role -> placeholders.role(role, names.get(role)))
                    .toList();
        }
        return new PackageReport("report-" + item.id(), item.name(), item.listCode(), item.state(), share);
    }

    public Mailing mailing(long id) {
        return mailings.get(id);
    }

    public PackageMailing mailing(
            Mailing mailing, ReportViewItem report, SolutionPlaceholders placeholders, List<FieldErrorItem> problems) {
        SolutionValues.values(
                fields(report),
                report.state().get("parameters"),
                mailing.parameters(),
                "mailings." + mailing.id(),
                problems);
        String recipients = placeholders.users("mailing-" + mailing.id(), MAILING_TEXT, mailing.name(), true);
        return new PackageMailing(
                "mailing-" + mailing.id(),
                "report-" + mailing.viewId(),
                mailing.name(),
                recipients,
                mailing.channels(),
                mailing.attachXlsx(),
                mailing.includeLink(),
                mailing.schedule(),
                mailing.parameters(),
                mailing.condition());
    }

    public Automation automation(long id) {
        SolutionCatalogSteps.require("studio.automations", "view");
        return automations.get(id);
    }

    public PackageAutomation automation(Automation automation, SolutionPlaceholders placeholders) {
        List<StudioAutomationDtos.Action> actions = automation.actions();
        Function<Integer, Action> part = index -> action(automation, index, actions.get(index), placeholders);
        return new PackageAutomation(
                "automation-" + automation.id(),
                automation.form(),
                automation.name(),
                automation.trigger(),
                automation.condition(),
                IntStream.range(0, actions.size()).boxed().map(part).toList(),
                automation.timeZone());
    }

    private static Action action(
            Automation automation, int index, StudioAutomationDtos.Action action, SolutionPlaceholders placeholders) {
        StudioAutomationDtos.Recipients recipients = action.recipients();
        Recipients portable = null;
        if (recipients != null) {
            String users = null;
            if (recipients.users() != null && !recipients.users().isEmpty()) {
                boolean alone = (recipients.fields() == null
                                || recipients.fields().isEmpty())
                        && !Boolean.TRUE.equals(recipients.author())
                        && (recipients.rights() == null || recipients.rights().isEmpty());
                users = placeholders.users(
                        "automation-" + automation.id() + "-" + (index + 1), AUTOMATION_TEXT, automation.name(), alone);
            }
            portable = new Recipients(recipients.fields(), recipients.author(), users, recipients.rights());
        }
        return new Action(
                action.type(),
                action.values(),
                action.form(),
                action.transition(),
                action.comment(),
                action.channels(),
                portable,
                action.title(),
                action.body());
    }

    /** The fields a report filters on: its dataset's, or its list's. */
    private QueryList fields(ReportViewItem item) {
        String dataset = datasetOf(item.state());
        if (dataset != null) {
            Dataset source = datasets.find(dataset)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.VALIDATION_FAILED, "error.studio.solution_dependency", Map.of("key", dataset)));
            return datasets.fields(source.entity(), source.lines());
        }
        return registry.get(item.listCode());
    }
}
