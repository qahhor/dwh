package com.smartup24.cms.instance.studio.solution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdListViewDtos.ViewResponse;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.ReportViewItem;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.Dataset;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.service.MdListViewService;
import com.smartup24.cms.instance.md.service.MdPreferenceSharing;
import com.smartup24.cms.instance.md.service.MdReportCatalogService;
import com.smartup24.cms.instance.md.service.MdReportDatasetService;
import com.smartup24.cms.instance.md.service.MdRoleService;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Mailing;
import com.smartup24.cms.instance.report.api.ReportMailingDtos.Options;
import com.smartup24.cms.instance.report.service.ReportMailingService;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Automation;
import com.smartup24.cms.instance.studio.api.StudioListDtos.Item;
import com.smartup24.cms.instance.studio.api.StudioListDtos.ListDetail;
import com.smartup24.cms.instance.studio.api.StudioListDtos.ListSummary;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageDataset;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageList;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageMailing;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageReport;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository.Head;
import com.smartup24.cms.instance.studio.repository.StudioPackageRepository;
import com.smartup24.cms.instance.studio.repository.StudioPackageRepository.ObjectRow;
import com.smartup24.cms.instance.studio.service.StudioActivationService;
import com.smartup24.cms.instance.studio.service.StudioAutomationService;
import com.smartup24.cms.instance.studio.service.StudioListService;
import com.smartup24.cms.instance.studio.service.StudioVersionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ADR-0047: what each step does to an item — create, upgrade, unchanged, skip — and when it refuses it. */
class SolutionStepsTest {
    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");

    private final SolutionFixtures fixtures = new SolutionFixtures();
    private final SolutionPackage pack = fixtures.read(fixtures.sealed(fixtures.order()));
    private final StudioListService lists = mock(StudioListService.class);
    private final StudioActivationService activation = mock(StudioActivationService.class);
    private final StudioVersionService versions = mock(StudioVersionService.class);
    private final StudioFormVersionRepository heads = mock(StudioFormVersionRepository.class);
    private final MdReportDatasetService datasets = mock(MdReportDatasetService.class);
    private final MdListViewService views = mock(MdListViewService.class);
    private final MdReportCatalogService catalog = mock(MdReportCatalogService.class);
    private final MdPreferenceSharing sharing = mock(MdPreferenceSharing.class);
    private final StudioPackageRepository objects = mock(StudioPackageRepository.class);
    private final ReportMailingService mailings = mock(ReportMailingService.class);
    private final StudioAutomationService automations = mock(StudioAutomationService.class);
    private final MdRoleService roles = mock(MdRoleService.class);
    private final SolutionCatalogSteps catalogSteps = new SolutionCatalogSteps(lists, activation, versions, heads);
    private final SolutionReportSteps reportSteps =
            new SolutionReportSteps(datasets, views, catalog, sharing, objects, fixtures.format, fixtures.json);
    private final SolutionRunSteps runSteps =
            new SolutionRunSteps(mailings, automations, roles, objects, fixtures.format);

    @BeforeEach
    void signIn() {
        allow(Set.of("*.*"));
    }

    @AfterEach
    void signOut() {
        SecurityContext.clear();
    }

    @Test
    void aListIsCreatedWithItsInactiveItemsOrGetsWhatItLacks() {
        PackageList regions = pack.lists().getFirst();
        PackageList withInactive = new PackageList(
                "regions",
                regions.name(),
                List.of(new com.smartup24.cms.instance.studio.api.StudioPackageDtos.ListItem(
                        "old", regions.name(), 5, false)));
        when(lists.addItem(anyString(), any())).thenReturn(item("old", regions.name(), 5, true));
        assertThat(catalogSteps.list(withInactive).outcome()).isEqualTo("create");
        verify(lists).updateItem(eq("regions"), eq("old"), anyString(), any());

        when(lists.exists("regions")).thenReturn(true);
        List<Item> held = regions.items().stream()
                .map(item -> item(item.code(), item.name(), item.sortOrder(), item.active()))
                .toList();
        when(lists.get("regions")).thenReturn(new ListDetail(summary(regions.name()), held));
        assertThat(catalogSteps.list(regions).outcome()).isEqualTo("unchanged");

        Labels renamed = new Labels("Области", "Viloyatlar", "Provinces");
        Item changed = item("tashkent", renamed, 10, true);
        when(lists.get("regions"))
                .thenReturn(new ListDetail(summary(renamed), List.of(changed, item("samarkand", renamed, 99, false))));
        assertThat(catalogSteps.list(regions).outcome()).isEqualTo("upgrade");
        verify(lists).rename(eq("regions"), anyString(), any());
        var first = regions.items().getFirst();
        for (Item differs : List.of(
                item(first.code(), first.name(), first.sortOrder() + 1, true),
                item(first.code(), first.name(), first.sortOrder(), false),
                item(
                        first.code(),
                        new Labels(first.name().ru(), "boshqa", first.name().en()),
                        first.sortOrder(),
                        true),
                item(
                        first.code(),
                        new Labels(first.name().ru(), first.name().uz(), "other"),
                        first.sortOrder(),
                        true))) {
            when(lists.get("regions")).thenReturn(new ListDetail(summary(regions.name()), List.of(differs)));
            assertThat(catalogSteps
                            .list(new PackageList("regions", regions.name(), List.of(first)))
                            .outcome())
                    .isEqualTo("upgrade");
        }

        allow(Set.of());
        assertThatThrownBy(() -> catalogSteps.list(regions)).isInstanceOf(ApiException.class);
    }

    @Test
    void aFormIsActivatedLeftAsItIsOrGetsANewVersion() {
        var orders = pack.forms().get(1);
        when(heads.head("studio.orders")).thenReturn(Optional.empty());
        assertThat(catalogSteps.form(orders).outcome()).isEqualTo("create");
        verify(activation).activate(orders);

        when(heads.head("studio.orders")).thenReturn(Optional.of(head(orders.checksum(), "active")));
        assertThat(catalogSteps.form(orders).notes()).isEmpty();
        when(heads.head("studio.orders")).thenReturn(Optional.of(head(orders.checksum(), "archived")));
        assertThat(catalogSteps.form(orders).notes()).containsExactly("studio.packages.note.archived");

        when(heads.head("studio.orders")).thenReturn(Optional.of(head("0".repeat(64), "active")));
        assertThat(catalogSteps.form(orders).outcome()).isEqualTo("upgrade");
        verify(versions).newVersion("studio.orders", 3, orders);
    }

    @Test
    void aDatasetIsCreatedLeftOrChanged() {
        Labels name = new Labels("Продажи", "Savdo", "Sales");
        PackageDataset dataset =
                new PackageDataset("sales", name, "studio.orders", null, List.of("region"), List.of(), List.of());
        when(datasets.find("sales")).thenReturn(Optional.empty());
        assertThat(reportSteps.dataset(dataset).outcome()).isEqualTo("create");
        when(datasets.find("sales")).thenReturn(Optional.of(held(dataset, name)));
        assertThat(reportSteps.dataset(dataset).outcome()).isEqualTo("unchanged");
        when(datasets.find("sales")).thenReturn(Optional.of(held(dataset, new Labels("Другое", "Boshqa", "Other"))));
        assertThat(reportSteps.dataset(dataset).outcome()).isEqualTo("upgrade");
        Labels other = new Labels("Продажи", "Savdo", "Sales");
        List<PackageDataset> changes = List.of(
                new PackageDataset("sales", other, "studio.other", null, List.of("region"), List.of(), List.of()),
                new PackageDataset("sales", other, "studio.orders", "items", List.of("region"), List.of(), List.of()),
                new PackageDataset("sales", other, "studio.orders", null, List.of("total"), List.of(), List.of()),
                new PackageDataset(
                        "sales",
                        other,
                        "studio.orders",
                        null,
                        List.of("region"),
                        List.of(Map.of("field", "x")),
                        List.of()),
                new PackageDataset(
                        "sales",
                        other,
                        "studio.orders",
                        null,
                        List.of("region"),
                        List.of(),
                        List.of(new com.smartup24.cms.instance.md.api.MdReportDatasetDtos.Measure(
                                "m", other, "count"))));
        when(datasets.find("sales")).thenReturn(Optional.of(held(dataset, name)));
        for (PackageDataset change : changes)
            assertThat(reportSteps.dataset(change).outcome()).isEqualTo("upgrade");
        verify(datasets, org.mockito.Mockito.atLeastOnce()).update(eq("sales"), anyString(), any());
    }

    @Test
    void aReportIsFoundByTheObjectOfAnEarlierApplication() {
        PackageReport report = pack.reports().getFirst();
        SolutionRun apply = new SolutionRun(pack, "scope", Map.of("approver", List.of(4L)), true, 8, 1);
        when(objects.object("scope", "report", report.key())).thenReturn(Optional.empty());
        when(views.create(eq(1L), eq("studio.orders"), any())).thenReturn(response(11));
        assertThat(reportSteps.report(apply, report).outcome()).isEqualTo("create");
        verify(sharing).shareReport(1L, "studio.orders", 11, 2, List.of(4L));
        verify(objects).remember(eq("scope"), eq("report"), eq(report.key()), eq(11L), anyString(), eq(8L));
        assertThat(apply.reports()).containsEntry(report.key(), 11L);

        String digest = fixtures.format.digest(report);
        when(objects.object("scope", "report", report.key())).thenReturn(Optional.of(new ObjectRow(11, digest)));
        when(catalog.find(1L, 11L)).thenReturn(Optional.of(item(11, "studio.orders", true)));
        SolutionRun check = new SolutionRun(pack, "scope", Map.of(), false, 0, 1);
        var unchanged = reportSteps.report(check, report);
        assertThat(unchanged.outcome()).isEqualTo("unchanged");
        assertThat(unchanged.notes()).containsExactly("studio.packages.note.not_shared");

        when(objects.object("scope", "report", report.key())).thenReturn(Optional.of(new ObjectRow(11, "x")));
        when(views.update(eq(1L), eq("studio.orders"), eq(11L), anyLong(), any()))
                .thenReturn(response(11));
        assertThat(reportSteps.report(check, report).outcome()).isEqualTo("upgrade");
        when(catalog.find(1L, 11L)).thenReturn(Optional.of(item(11, "studio.orders", false)));
        assertThatThrownBy(() -> reportSteps.report(check, report))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.studio.solution_owner"));
        when(catalog.find(1L, 11L)).thenReturn(Optional.of(item(11, "studio.other", true)));
        assertThat(reportSteps.report(check, report).outcome()).isEqualTo("create");
    }

    @Test
    void aMailingNeedsItsReportAndAChannelAndStartsPaused() {
        PackageMailing mailing = pack.mailings().getFirst();
        SolutionRun check = new SolutionRun(pack, "scope", Map.of(), false, 0, 1);
        assertThatThrownBy(() -> runSteps.mailing(check, mailing))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.studio.solution_dependency"));
        check.reports().put(mailing.report(), 11L);
        when(mailings.options()).thenReturn(new Options(List.of("telegram"), 50, 1, true));
        assertThatThrownBy(() -> runSteps.mailing(check, mailing))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.studio.solution_channels"));

        when(mailings.options()).thenReturn(new Options(List.of("in_app"), 50, 1, true));
        Mailing created = mock(Mailing.class);
        when(created.id()).thenReturn(21L);
        when(created.revision()).thenReturn(1L);
        when(mailings.create(any())).thenReturn(created);
        when(objects.object(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        assertThat(runSteps.mailing(check, mailing).notes())
                .containsExactly(
                        SolutionRunSteps.PAUSED, "studio.packages.note.channels", "studio.packages.note.unmapped");
        verify(objects, never()).remember(anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong());

        when(objects.object("scope", "mailing", mailing.key()))
                .thenReturn(Optional.of(new ObjectRow(21, fixtures.format.digest(mailing))));
        when(mailings.find(21L)).thenReturn(Optional.of(created));
        SolutionRun apply =
                new SolutionRun(pack, "scope", Map.of("mailing-recipients", List.of(5L)), true, 8, 1, check.reports());
        assertThat(runSteps.mailing(apply, mailing).outcome()).isEqualTo("unchanged");
        when(objects.object("scope", "mailing", mailing.key())).thenReturn(Optional.of(new ObjectRow(21, "x")));
        when(mailings.update(eq(21L), anyString(), any())).thenReturn(created);
        assertThat(runSteps.mailing(apply, mailing).outcome()).isEqualTo("upgrade");
        verify(objects, org.mockito.Mockito.atLeastOnce())
                .remember(eq("scope"), eq("mailing"), eq(mailing.key()), eq(21L), anyString(), eq(8L));
    }

    @Test
    void anAutomationStartsPausedAndAGrantNeedsAMappedRole() {
        var automation = pack.automations().getFirst();
        SolutionRun apply = new SolutionRun(pack, "scope", Map.of("approver", List.of(4L)), true, 8, 1);
        Automation created = mock(Automation.class);
        when(created.id()).thenReturn(31L);
        when(created.revision()).thenReturn(2L);
        when(automations.create(any())).thenReturn(created);
        when(objects.object(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        assertThat(runSteps.automation(apply, automation).notes()).containsExactly(SolutionRunSteps.PAUSED);
        when(objects.object("scope", "automation", automation.key()))
                .thenReturn(Optional.of(new ObjectRow(31, fixtures.format.digest(automation))));
        when(automations.find(31L)).thenReturn(Optional.of(created));
        assertThat(runSteps.automation(apply, automation).outcome()).isEqualTo("unchanged");
        when(objects.object("scope", "automation", automation.key())).thenReturn(Optional.of(new ObjectRow(31, "x")));
        when(automations.update(eq(31L), anyString(), any())).thenReturn(created);
        assertThat(runSteps.automation(apply, automation).outcome()).isEqualTo("upgrade");

        var notifies = new com.smartup24.cms.instance.studio.api.StudioPackageDtos.Action(
                "notify",
                null,
                null,
                null,
                null,
                List.of("in_app"),
                new com.smartup24.cms.instance.studio.api.StudioPackageDtos.Recipients(
                        null, null, "mailing-recipients", null),
                automation.actions().get(1).title(),
                null);
        var withUsers = new com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageAutomation(
                "users", automation.form(), "Users", automation.trigger(), null, List.of(notifies), "UTC");
        when(objects.object("scope", "automation", "users")).thenReturn(Optional.empty());
        assertThat(runSteps.automation(apply, withUsers).notes()).contains("studio.packages.note.unmapped");
        SolutionRun mapped = new SolutionRun(pack, "scope", Map.of("mailing-recipients", List.of(5L)), true, 8, 1);
        assertThat(runSteps.automation(mapped, withUsers).notes()).doesNotContain("studio.packages.note.unmapped");
        var grants = pack.grants();
        assertThat(runSteps.grant(apply, grants.getFirst()).outcome()).isEqualTo("skip");
        when(roles.grant(eq(4L), any())).thenReturn(true, false);
        assertThat(runSteps.grant(apply, grants.get(1)).outcome()).isEqualTo("upgrade");
        assertThat(runSteps.grant(apply, grants.get(1)).outcome()).isEqualTo("unchanged");
        allow(Set.of("md.profile.view"));
        assertThatThrownBy(() -> runSteps.grant(apply, grants.get(1))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> runSteps.automation(apply, automation)).isInstanceOf(ApiException.class);
    }

    private static void allow(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "importer", "importer@test.local", null, false, permissions, 0, false, 0, null));
    }

    private static Item item(String code, Labels name, int order, boolean active) {
        return new Item(code, name, order, active, 1, NOW);
    }

    private static ListSummary summary(Labels name) {
        return new ListSummary("regions", name, 5, 5, 1, NOW, NOW);
    }

    private static Head head(String checksum, String status) {
        return new Head(1, "studio.orders", NOW, 1, status, 3, NOW, "{}", checksum, 1, 0, false);
    }

    private static Dataset held(PackageDataset dataset, Labels name) {
        return new Dataset(
                dataset.code(),
                name,
                dataset.entity(),
                dataset.lines(),
                dataset.fields(),
                dataset.filter(),
                dataset.measures(),
                4,
                NOW,
                NOW);
    }

    private ViewResponse response(long id) {
        return new ViewResponse(id, "report", "r", fixtures.json.createObjectNode(), false, 2, NOW);
    }

    private ReportViewItem item(long id, String list, boolean owned) {
        return new ReportViewItem(
                id,
                "studio.orders",
                list,
                "report",
                "r",
                fixtures.json.createObjectNode(),
                2,
                NOW,
                owned,
                owned,
                false,
                owned);
    }
}
