package com.smartup24.cms.instance.studio.solution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportViewItem;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.SharingResponse;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.Dataset;
import com.smartup24.cms.instance.md.api.MdRoleDtos.RoleView;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.LineField;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Lines;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Entity;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Module;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Target;
import com.smartup24.cms.instance.md.service.MdPreferenceSharing;
import com.smartup24.cms.instance.md.service.MdReportCatalogService;
import com.smartup24.cms.instance.md.service.MdReportDatasetService;
import com.smartup24.cms.instance.md.service.MdRoleService;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Mailing;
import com.smartup24.cms.instance.report.service.ReportMailingService;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Automation;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Right;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.FormDetail;
import com.smartup24.cms.instance.studio.api.StudioFormDtos.FormSummary;
import com.smartup24.cms.instance.studio.api.StudioListDtos.Item;
import com.smartup24.cms.instance.studio.api.StudioListDtos.ListDetail;
import com.smartup24.cms.instance.studio.api.StudioListDtos.ListSummary;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageList;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ExportItem;
import com.smartup24.cms.instance.studio.api.StudioPackageImportDtos.ExportRequest;
import com.smartup24.cms.instance.studio.service.StudioAutomationService;
import com.smartup24.cms.instance.studio.service.StudioFormViews;
import com.smartup24.cms.instance.studio.service.StudioListService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** ADR-0047: what a package being made holds and why, and how each item of the installation becomes a part. */
class SolutionExportPartsTest {
    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    private static final Labels NAME = new Labels("Имя", "Nom", "Name");

    private final ObjectMapper json = JsonMapper.shared();
    private final StudioFormViews views = mock(StudioFormViews.class);
    private final StudioListService lists = mock(StudioListService.class);
    private final MdReportDatasetService datasets = mock(MdReportDatasetService.class);
    private final MdReportCatalogService catalog = mock(MdReportCatalogService.class);
    private final MdPreferenceSharing sharing = mock(MdPreferenceSharing.class);
    private final MdRoleService roles = mock(MdRoleService.class);
    private final ReportMailingService mailings = mock(ReportMailingService.class);
    private final StudioAutomationService automations = mock(StudioAutomationService.class);
    private final QueryListRegistry registry = mock(QueryListRegistry.class);
    private final SolutionSources sources =
            new SolutionSources(lists, datasets, catalog, sharing, roles, mailings, automations, registry, json);
    private final SolutionRelated related = new SolutionRelated(datasets, catalog, mailings, automations);

    @AfterEach
    void signOut() {
        SecurityContext.clear();
    }

    @Test
    void theSelectionAddsWhatItStandsOnFormsFirst() {
        allow(Set.of("*.*"));
        stubInstallation();
        ExportRequest request = new ExportRequest(
                "pack",
                "1.0.0",
                NAME,
                null,
                List.of("studio.a"),
                List.of("x"),
                List.of(7L, 7L),
                List.of(9L),
                List.of(5L),
                null);
        SolutionSelection selection = new SolutionSelection(views, sources).pick(request);

        assertThat(selection.forms().keySet()).containsExactly("studio.b", "studio.a", "studio.c");
        assertThat(selection.lists()).containsExactly("l1", "l2");
        List<ExportItem> items = selection.items();
        assertThat(items)
                .extracting(item -> item.kind() + ":" + item.key() + ":" + item.reason())
                .contains(
                        "form:studio.a:selected",
                        "form:studio.b:dependency",
                        "list:l1:dependency",
                        "dataset:x:selected",
                        "report:7:selected",
                        "mailing:9:selected",
                        "automation:5:selected");
        assertThat(selection.holds("report", "7")).isTrue();
        assertThat(selection.holds("report", "8")).isFalse();

        List<ExportItem> others = related.of(selection);
        assertThat(others)
                .extracting(item -> item.kind() + ":" + item.key())
                .containsExactlyInAnyOrder("dataset:y", "report:8", "automation:6", "mailing:10");
        allow(Set.of("md.profile.view"));
        assertThat(related.of(selection)).extracting(ExportItem::kind).containsOnly("report");
    }

    @Test
    void automationsTurnTheirFixedUsersIntoPlaceholders() {
        Automation automation = mock(Automation.class);
        when(automation.id()).thenReturn(5L);
        when(automation.name()).thenReturn("Уведомить");
        List<Long> users = List.of(3L);
        when(automation.actions())
                .thenReturn(List.of(
                        notify(new StudioAutomationDtos.Recipients(null, null, users, null)),
                        notify(new StudioAutomationDtos.Recipients(List.of("manager"), null, users, null)),
                        notify(new StudioAutomationDtos.Recipients(List.of(), true, users, null)),
                        notify(new StudioAutomationDtos.Recipients(
                                null, false, users, List.of(new Right("studio.a", "view")))),
                        notify(new StudioAutomationDtos.Recipients(null, null, List.of(), List.of())),
                        notify(null)));
        SolutionPlaceholders placeholders = new SolutionPlaceholders(language -> Map.of());
        var pack = sources.automation(automation, placeholders);
        assertThat(pack.key()).isEqualTo("automation-5");
        assertThat(pack.actions())
                .extracting(action -> action.recipients() == null
                        ? "none"
                        : String.valueOf(action.recipients().users()))
                .containsExactly(
                        "automation-5-1", "automation-5-2", "automation-5-3", "automation-5-4", "null", "none");
        assertThat(placeholders.all())
                .extracting(placeholder -> placeholder.required())
                .containsExactly(true, false, false, false);
    }

    @Test
    void aReportCarriesItsSharingOnlyForItsOwnerAndItsDatasetMustExist() {
        allow(Set.of("md.settings.update"));
        when(registry.get("studio.a")).thenReturn(mock(QueryList.class));
        when(roles.listRoles()).thenReturn(List.of(new RoleView(4L, "Согласующий", null, "A", 1, NOW, NOW, 1)));
        when(sharing.report(anyLong(), eq("studio.a"), eq(7L))).thenReturn(new SharingResponse(1, List.of(4L, 99L)));
        SolutionPlaceholders placeholders = new SolutionPlaceholders(language -> Map.of());
        List<FieldErrorItem> problems = new ArrayList<>();
        assertThat(sources.report(report(7, "studio.a", null, true), placeholders, problems)
                        .share())
                .containsExactly("role-1");
        assertThat(sources.report(report(7, "studio.a", null, false), placeholders, problems)
                        .share())
                .isEmpty();
        allow(Set.of());
        assertThat(sources.report(report(7, "studio.a", null, true), placeholders, problems)
                        .share())
                .isEmpty();
        when(datasets.find("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> sources.report(report(7, "studio.a", "ds.gone", true), placeholders, problems))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.studio.solution_dependency"));
        assertThat(SolutionSources.datasetOf(json.readTree("{\"dataset\": \"orders.lines\"}")))
                .isNull();
        assertThat(SolutionSources.datasetOf(json.readTree("{}"))).isNull();
        assertThat(problems).isEmpty();
    }

    @Test
    void aListTravelsWithOrWithoutItsItems() {
        allow(Set.of("studio.lists.view"));
        when(lists.get("l1"))
                .thenReturn(new ListDetail(
                        new ListSummary("l1", NAME, 1, 1, 1, NOW, NOW),
                        List.of(new Item("a", NAME, 1, false, 1, NOW))));
        PackageList with = sources.list("l1", true);
        assertThat(with.items())
                .singleElement()
                .satisfies(item -> assertThat(item.active()).isFalse());
        assertThat(sources.list("l1", false).items()).isEmpty();
        allow(Set.of());
        assertThatThrownBy(() -> sources.list("l1", true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sources.dataset("x")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sources.automation(5L)).isInstanceOf(ApiException.class);
    }

    private void stubInstallation() {
        Target formB = new Target("form", "studio.b");
        when(views.get("studio.a"))
                .thenReturn(form(
                        "a",
                        List.of(
                                field(formB),
                                field(new Target("form", "studio.a")),
                                field(new Target("list", "l1")),
                                field(null))));
        when(views.get("studio.b")).thenReturn(form("b", List.of(field(new Target("form", "studio.a")))));
        when(views.get("studio.c")).thenReturn(form("c", List.of()));
        when(lists.get(anyString()))
                .thenAnswer(call ->
                        new ListDetail(new ListSummary(call.getArgument(0), NAME, 0, 0, 1, NOW, NOW), List.of()));
        Dataset x = dataset("x", "ms.tasks");
        when(datasets.get("x")).thenReturn(x);
        when(datasets.list(anyInt(), isNull()))
                .thenReturn(new KeysetPage<>(
                        List.of(x, dataset("y", "studio.a"), dataset("z", "ms.notes")), null, false, 3));
        ReportViewItem seven = report(7, "studio.a", "ds.x", true);
        when(catalog.find(anyLong(), eq(7L))).thenReturn(Optional.of(seven));
        when(catalog.list(anyLong(), anyString(), anyInt(), isNull()))
                .thenReturn(new KeysetPage<>(List.of(seven, report(8, "studio.a", null, true)), null, false, 2));
        Mailing nine = mock(Mailing.class);
        when(nine.id()).thenReturn(9L);
        when(nine.viewId()).thenReturn(7L);
        when(nine.name()).thenReturn("Рассылка");
        Mailing ten = mock(Mailing.class);
        when(ten.id()).thenReturn(10L);
        when(ten.name()).thenReturn("Другая");
        when(mailings.get(9L)).thenReturn(nine);
        when(mailings.list(eq(7L), anyInt(), isNull()))
                .thenReturn(new KeysetPage<>(List.of(nine, ten), null, false, 2));
        Automation five = mock(Automation.class);
        when(five.id()).thenReturn(5L);
        when(five.form()).thenReturn("studio.a");
        when(five.name()).thenReturn("Автоматизация");
        StudioAutomationDtos.Recipients recipients = new StudioAutomationDtos.Recipients(
                null, null, null, List.of(new Right("md.users", "view"), new Right("studio.b", "approve")));
        when(five.actions())
                .thenReturn(List.of(
                        new StudioAutomationDtos.Action(
                                "create_record", List.of(), "studio.c", null, null, null, null, null, null),
                        new StudioAutomationDtos.Action(
                                "notify", null, null, null, null, List.of("in_app"), recipients, null, null),
                        notify(new StudioAutomationDtos.Recipients(null, true, null, null))));
        Automation six = mock(Automation.class);
        when(six.id()).thenReturn(6L);
        when(six.name()).thenReturn("Другая");
        when(automations.get(5L)).thenReturn(five);
        when(automations.list(anyString(), anyInt(), isNull()))
                .thenReturn(new KeysetPage<>(List.of(five, six), null, false, 2));
        when(automations.list(eq("studio.b"), anyInt(), isNull())).thenReturn(KeysetPage.empty());
        when(automations.list(eq("studio.c"), anyInt(), isNull())).thenReturn(KeysetPage.empty());
        when(catalog.list(anyLong(), eq("studio.b"), anyInt(), isNull())).thenReturn(KeysetPage.empty());
        when(catalog.list(anyLong(), eq("studio.c"), anyInt(), isNull())).thenReturn(KeysetPage.empty());
    }

    private static StudioAutomationDtos.Action notify(StudioAutomationDtos.Recipients recipients) {
        return new StudioAutomationDtos.Action(
                "notify", null, null, null, null, List.of("in_app"), recipients, null, null);
    }

    private static Field field(Target target) {
        return new Field("f", target == null ? "text" : "ref", NAME, false, true, null, null, null, null, null, target);
    }

    private static FormDetail form(String name, List<Field> fields) {
        Lines lines = new Lines(
                "items",
                NAME,
                5,
                List.of(
                        new LineField("p", "ref", NAME, false, null, null, null, new Target("list", "l2"), null),
                        new LineField("q", "number", NAME, false, null, null, null, null, null)));
        Declaration declaration = new Declaration(
                6,
                "simple-form",
                new Module("studio", NAME),
                new Entity(name, NAME),
                fields,
                null,
                null,
                "a".equals(name) ? List.of(lines) : null,
                null);
        FormSummary summary = new FormSummary("studio." + name, NAME, "active", 1, 1, 0, false, null, 1, NOW, NOW);
        return new FormDetail(summary, declaration, "0".repeat(64), List.of());
    }

    private static Dataset dataset(String code, String entity) {
        return new Dataset(code, NAME, entity, null, List.of("f"), List.of(), List.of(), 1, NOW, NOW);
    }

    private ReportViewItem report(long id, String list, String dataset, boolean owned) {
        var state = json.createObjectNode();
        if (dataset != null) state.put("dataset", dataset);
        return new ReportViewItem(id, list, list, "report", "Отчёт " + id, state, 1, NOW, owned, owned, false, owned);
    }

    private static void allow(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "exporter", "exporter@test.local", null, false, permissions, 0, false, 0, null));
    }
}
