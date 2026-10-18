package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.report.EntityReports;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardRequest;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardWidgetRequest;
import com.smartup24.cms.instance.md.repository.MdDashboardRepository;
import com.smartup24.cms.instance.md.repository.MdListViewRepository;
import com.smartup24.cms.instance.md.repository.MdReportAccessRepository;
import com.smartup24.cms.instance.md.repository.MdSharingRepository;
import com.smartup24.cms.instance.md.repository.MdSharingRepository.Resource;
import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.smartup24.cms.instance.md.service.MdDashboardPeriod;
import com.smartup24.cms.instance.md.service.MdDashboardService;
import com.smartup24.cms.instance.md.service.MdPreferenceSharing;
import com.smartup24.cms.instance.md.service.MdReportCatalogService;
import com.smartup24.cms.instance.md.service.MdReportPreferencesInput;
import com.smartup24.cms.instance.md.service.MdReportViewState;
import com.smartup24.cms.instance.md.service.MdReportWidgetService;
import com.smartup24.cms.instance.support.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Real database concurrency, rollback and authorization across independent service instances (ADR-0036). */
class MdDashboardLifecycleTest {
    private static final String CODE = "test.reports";
    private final JsonMapper json = JsonMapper.shared();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private EntityReports runtime;
    private AuditLogService audit;
    private MdDashboardRepository repo;
    private MdListViewRepository views;
    private MdReportCatalogService catalog;
    private MdPreferenceSharing sharing;
    private MdDashboardService boards;
    private long author;
    private long reader;
    private long role;

    @BeforeEach
    void setup() {
        pool = (HikariDataSource) TestDatabases.migratedCopy("report_layout");
        jdbc = JdbcClient.create(pool);
        author = MdAuditActors.ensureSystemUser(jdbc);
        reader = jdbc.sql(
                        "insert into md_users (name, login, email) values ('TEST Reader', 'test-reader', 'reader@test.local') returning id")
                .query(Long.class)
                .single();
        role = jdbc.sql("insert into md_roles (name) values ('TEST Reader role') returning id")
                .query(Long.class)
                .single();
        jdbc.sql("insert into md_user_roles (user_id, role_id) values (:user, :role)")
                .param("user", reader)
                .param("role", role)
                .update();
        tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
        runtime = mock(EntityReports.class);
        when(runtime.entityOfList(CODE)).thenReturn(Optional.of(CODE));
        when(runtime.list(CODE, null)).thenReturn(list());
        audit = new AuditLogService(new AuditLogRepository(jdbc, json), null, new AuditDataRedactor());
        repo = new MdDashboardRepository(jdbc);
        views = new MdListViewRepository(jdbc);
        catalog = catalog();
        boards = boards(audit);
        sharing = sharing(audit);
        signIn(author, true);
    }

    @AfterEach
    void close() {
        SecurityContext.clear();
        pool.close();
    }

    @Test
    void independentNodesSeeChangesAndConcurrentEditorsHaveOneWinner() throws Exception {
        var made = transaction(() -> boards.create(author, new DashboardRequest("Panel", List.of())));
        var other = boards(audit);
        assertThat(other.get(author, made.id()).name()).isEqualTo("Panel");
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> updateAfter(start, boards, made.id(), "First"));
            var second = threads.submit(() -> updateAfter(start, other, made.id(), "Second"));
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(other.get(author, made.id()).revision()).isEqualTo(2);
        assertThat(audits("md_dashboards")).isEqualTo(2);
    }

    @Test
    void failedAuditRollsBackBothSharingAndItsParentRevision() {
        var made = transaction(() -> boards.create(author, new DashboardRequest("Panel", List.of())));
        var broken = mock(AuditLogService.class);
        doThrow(new IllegalStateException("test audit fault"))
                .when(broken)
                .logChange(anyString(), anyString(), anyString(), any(), any(), any());
        assertThatThrownBy(() -> transaction(() -> sharing(broken).shareDashboard(author, made.id(), 1, List.of(role))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("test audit fault");
        assertThat(repo.owned(author, made.id(), false).orElseThrow().revision())
                .isEqualTo(1);
        assertThat(new MdSharingRepository(jdbc).roles(Resource.DASHBOARD, made.id()))
                .isEmpty();
        assertThatThrownBy(() -> transaction(() ->
                        boards(broken).update(author, made.id(), 1, new DashboardRequest("Lost edit", List.of()))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(boards.get(author, made.id()).name()).isEqualTo("Panel");
    }

    @Test
    void roleRemovalAndInactiveRoleRevokeBothDefinitionsOnTheNextRead() {
        long view = report("Visible", false);
        var made = transaction(() -> boards.create(author, request("Panel", view, null)));
        transaction(() -> sharing.shareReport(author, CODE, view, 0, List.of(role)));
        transaction(() -> sharing.shareDashboard(author, made.id(), 1, List.of(role)));
        var otherCatalog = catalog();
        var otherBoards = boards(audit);
        signIn(reader, false);
        assertThat(otherCatalog.find(reader, view)).isPresent();
        assertThat(otherBoards.get(reader, made.id()).widgets().getFirst().available())
                .isTrue();
        jdbc.sql("delete from md_user_roles where user_id = :user and role_id = :role")
                .param("user", reader)
                .param("role", role)
                .update();
        assertThat(otherCatalog.find(reader, view)).isEmpty();
        assertThatThrownBy(() -> otherBoards.get(reader, made.id())).isInstanceOf(ApiException.class);
        jdbc.sql("insert into md_user_roles (user_id, role_id) values (:user, :role)")
                .param("user", reader)
                .param("role", role)
                .update();
        jdbc.sql("update md_roles set state = 'P' where id = :id")
                .param("id", role)
                .update();
        assertThat(otherCatalog.find(reader, view)).isEmpty();
        assertThatThrownBy(() -> otherBoards.get(reader, made.id())).isInstanceOf(ApiException.class);
    }

    @Test
    void hiddenReportFieldsDisappearFromEveryDefinitionResponse() {
        long view = report("Sensitive definition", true);
        var made = transaction(() -> boards.create(author, request("Panel", view, null)));
        transaction(() -> sharing.shareReport(author, CODE, view, 0, List.of(role)));
        transaction(() -> sharing.shareDashboard(author, made.id(), 1, List.of(role)));
        signIn(reader, false);
        assertThat(catalog.find(reader, view)).isEmpty();
        var detail = boards.get(reader, made.id());
        assertThat(detail.widgets().getFirst().available()).isFalse();
        assertThat(json.writeValueAsString(detail))
                .doesNotContain("Sensitive definition", "secret", "\"state\"", "\"entity\"");
        signIn(author, false);
        assertThat(new MdReportWidgetService(views, catalog).widgets(author)).isEmpty();
    }

    @Test
    void retainingAnUnavailableSlotPreservesItsHiddenPeriodBinding() {
        long view = report("Visible", false);
        var made = transaction(() -> boards.create(author, request("Panel", view, "secretDate")));
        signIn(author, false);
        assertThat(boards.get(author, made.id()).widgets().getFirst().available())
                .isFalse();
        var changed = transaction(() -> boards.update(author, made.id(), 1, request("Renamed", view, null)));
        assertThat(json.writeValueAsString(changed)).doesNotContain("secretDate");
        assertThat(repo.owned(author, made.id(), false).orElseThrow().widgetsJson())
                .contains("secretDate");
        signIn(author, true);
        assertThat(boards.get(author, made.id()).widgets().getFirst().periodField())
                .isEqualTo("secretDate");
    }

    @Test
    void anEmptyFilteredPageStillAdvancesToTheNextVisibleReport() {
        long visible = report("Visible", false);
        transaction(() -> sharing.shareReport(author, CODE, visible, 0, List.of(role)));
        long hidden = report("Sensitive definition", true);
        transaction(() -> sharing.shareReport(author, CODE, hidden, 0, List.of(role)));
        signIn(reader, false);
        var first = catalog.list(reader, CODE, 1, null);
        assertThat(first.items()).isEmpty();
        assertThat(first.hasMore()).isTrue();
        assertThat(first.totalEstimated()).isZero();
        var next = catalog.list(reader, CODE, 1, first.nextCursor());
        assertThat(next.items())
                .singleElement()
                .satisfies(item -> assertThat(item.id()).isEqualTo(visible));
        assertThat(next.hasMore()).isFalse();
        assertThat(next.nextCursor()).isNull();
    }

    @Test
    void concurrentCreationCannotExceedTheOwnerLimit() throws Exception {
        for (int i = 0; i < MdDashboardService.MAX_DASHBOARDS - 1; i++) {
            String name = "Panel " + i;
            transaction(() -> boards.create(author, new DashboardRequest(name, List.of())));
        }
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> createAfter(start, "First"));
            var second = threads.submit(() -> createAfter(start, "Second"));
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 422);
        }
        assertThat(repo.count(author)).isEqualTo(MdDashboardService.MAX_DASHBOARDS);
    }

    private int updateAfter(CountDownLatch start, MdDashboardService service, long id, String name)
            throws InterruptedException {
        start.await();
        signIn(author, true);
        try {
            transaction(() -> service.update(author, id, 1, new DashboardRequest(name, List.of())));
            return 200;
        } catch (ApiException failure) {
            assertThat(failure.getMessageKey()).isEqualTo("error.common.revision_conflict");
            return 409;
        } finally {
            SecurityContext.clear();
        }
    }

    private int createAfter(CountDownLatch start, String name) throws InterruptedException {
        start.await();
        signIn(author, true);
        try {
            transaction(() -> boards(audit).create(author, new DashboardRequest(name, List.of())));
            return 201;
        } catch (ApiException failure) {
            assertThat(failure.getMessageKey()).isEqualTo("error.md.report_preferences_invalid");
            return 422;
        } finally {
            SecurityContext.clear();
        }
    }

    private long report(String name, boolean secret) {
        String state = json.writeValueAsString(Map.of(
                "groupBy",
                secret ? List.of(Map.of("field", "secret")) : List.of(),
                "measures",
                List.of(Map.of("op", "count")),
                "chart",
                secret ? "bar" : "kpi",
                "filter",
                List.of()));
        return views.insert(author, CODE, "widget", name, state, false);
    }

    private MdDashboardService boards(AuditLogService logging) {
        return new MdDashboardService(
                new MdDashboardRepository(jdbc),
                catalog(),
                new MdDashboardPeriod(runtime, json),
                new MdReportPreferencesInput(json),
                logging,
                json);
    }

    private MdReportCatalogService catalog() {
        return new MdReportCatalogService(
                new MdReportAccessRepository(jdbc),
                runtime,
                new MdReportViewState(json, java.time.Clock.systemUTC()),
                json);
    }

    private MdPreferenceSharing sharing(AuditLogService logging) {
        return new MdPreferenceSharing(
                new MdSharingRepository(jdbc),
                new MdReportAccessRepository(jdbc),
                new MdDashboardRepository(jdbc),
                catalog(),
                logging);
    }

    private static DashboardRequest request(String name, long view, String field) {
        return new DashboardRequest(name, List.of(new DashboardWidgetRequest(view, field, 1)));
    }

    private long audits(String table) {
        return jdbc.sql("select count(*) from audit_log where table_name = :table")
                .param("table", table)
                .query(Long.class)
                .single();
    }

    private <T> T transaction(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private static void signIn(long user, boolean secret) {
        Set<String> rights = secret ? Set.of("*.*") : Set.of("md.profile.view", "md.profile.update", CODE + ".view");
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                user, "test", "test@example.test", null, false, rights, 1, false, 1, null));
    }

    private static QueryList list() {
        return new QueryList(
                CODE,
                CODE,
                "view",
                "r.id",
                "test_records r",
                "r.id",
                List.of(
                        QueryField.of("id", "id", QueryFieldType.NUMBER, "r.id").asSortable(),
                        QueryField.of("secret", "secret", QueryFieldType.BOOLEAN, "r.secret")
                                .requires("test.secret", "view"),
                        QueryField.of("secretDate", "date", QueryFieldType.DATE, "r.secret_date")
                                .requires("test.secret", "view")),
                "id");
    }
}
