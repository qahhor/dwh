package com.greenwhite.dwh.instance.report;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditDataRedactor;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.config.error.GlobalExceptionHandler;
import com.greenwhite.dwh.instance.kauth.security.RequiresPermissionInterceptor;
import com.greenwhite.dwh.instance.md.repository.MdOrgUnitRepository;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdScopeRepository;
import com.greenwhite.dwh.instance.md.service.MdOrgUnitService;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.md.service.MdScopeService;
import com.greenwhite.dwh.instance.report.controller.ReportController;
import com.greenwhite.dwh.instance.report.service.ReportService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.w3c.dom.Element;
import tools.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises HTTP export serialization with the real SQL authorization predicate. */
@Testcontainers
class ReportExportIntegrationTest {
    private static final String SS = "urn:schemas-microsoft-com:office:spreadsheet";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("report_export_test").withUsername("test_user").withPassword("test_pass");

    static JdbcClient jdbc;
    static DataSourceTransactionManager transactions;
    static MdScopeService scopes;
    static MdScopeRepository scopeRepository;
    static MdRoleRepository roles;
    static MdOrgUnitService orgUnits;
    static ReportService reports;
    static MockMvc mvc;
    TransactionStatus transaction;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);
        transactions = new DataSourceTransactionManager(ds);
        scopeRepository = new MdScopeRepository(jdbc);
        roles = new MdRoleRepository(jdbc);
        var orgRepository = new MdOrgUnitRepository(jdbc);
        var permissions = new MdPermissionService(new MdPermissionRepository(jdbc));
        var audit = new AuditLogService(new AuditLogRepository(jdbc, new ObjectMapper()), null,
                new AuditDataRedactor());
        scopes = new MdScopeService(scopeRepository, orgRepository, permissions, audit);
        orgUnits = new MdOrgUnitService(orgRepository, scopes, audit);
        reports = new ReportService(jdbc, scopes);
        mvc = MockMvcBuilders.standaloneSetup(new ReportController(reports))
                .addInterceptors(new RequiresPermissionInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @BeforeEach
    void begin() {
        transaction = transactions.getTransaction(new DefaultTransactionDefinition());
    }

    @AfterEach
    void cleanup() {
        SecurityContext.clear();
        if (transaction != null) transactions.rollback(transaction);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "csv", "CSV", "unknown", "xlsx", "XLSX", "excel", "EXCEL"})
    void selfExportsOnlyCreatorReporterAndEveryMembershipKind(String format) throws Exception {
        Long viewer = user("viewer", null);
        Long outsider = user("outsider", null);
        assignScope(viewer, "SELF");
        List<Long> expected = new ArrayList<>();
        expected.add(task("Только автор", viewer, outsider));
        expected.add(task("Только постановщик", outsider, viewer));
        for (String kind : List.of("R", "E", "P", "A", "O")) {
            Long taskId = task("Участник " + kind, outsider, outsider);
            jdbc.sql("insert into ms_task_members (task_id, user_id, involve_kind, is_viewed) values (:task, :user, :kind, false)")
                    .param("task", taskId).param("user", viewer).param("kind", kind).update();
            expected.add(taskId);
        }
        task("Скрытая задача", outsider, outsider);
        signIn(viewer, Set.of("tasks.items.view"), false);

        assertIds(export(format), format, expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "csv", "CSV", "unknown", "xlsx", "XLSX", "excel", "EXCEL"})
    void unitsExcludeDescendantsButSubtreeIncludesThemAndNeitherIncludesSibling(String format) throws Exception {
        Long root = orgUnits.create(null, "ROOT", "Компания", "company", 1).id();
        Long region = orgUnits.create(root, "REGION", "Регион", "region", 1).id();
        Long branch = orgUnits.create(region, "BRANCH", "Филиал", "branch", 1).id();
        Long sibling = orgUnits.create(root, "SIBLING", "Другой регион", "region", 2).id();
        Long viewer = user("viewer", null);
        Long primary = user("primary", region);
        Long child = user("child", branch);
        Long foreign = user("foreign", sibling);
        Long secondary = user("secondary", sibling);
        scopes.assignUserOrgUnits(secondary, List.of(region));
        Long primaryTask = task("Основной узел", primary, foreign);
        Long childTask = task("Дочерний узел", foreign, child);
        Long secondaryTask = task("Дополнительный узел наблюдателя", foreign, foreign);
        jdbc.sql("insert into ms_task_members (task_id, user_id, involve_kind, is_viewed) values (:task, :user, 'O', false)")
                .param("task", secondaryTask).param("user", secondary).update();
        task("Соседняя ветка", foreign, foreign);
        assignScope(viewer, "UNITS");
        scopes.assignUserOrgUnits(viewer, List.of(region));
        signIn(viewer, Set.of("tasks.items.view"), false);

        assertIds(export(format), format, List.of(primaryTask, secondaryTask));

        assignScope(viewer, "SUBTREE");
        assertIds(export(format), format, List.of(primaryTask, secondaryTask, childTask));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "csv", "CSV", "unknown", "xlsx", "XLSX", "excel", "EXCEL"})
    void emptyScopeProducesHeaderOnlyAndAllRetainsOrderingAndTerminalTasks(String format) throws Exception {
        Long viewer = user("viewer", null);
        Long outsider = user("outsider", null);
        Long first = task("Первая", outsider, outsider);
        Long second = task("Завершённая", outsider, outsider);
        jdbc.sql("update ms_tasks set status_id = (select id from ms_task_statuses where pcode = 'done'), resolved_time = now() where id = :id")
                .param("id", second).update();
        signIn(viewer, Set.of("*.*"), false);
        for (String rule : List.of("SELF", "UNITS", "SUBTREE")) {
            assignScope(viewer, rule);
            assertIds(export(format), format, List.of());
        }
        assignScope(viewer, "ALL");
        assertIds(export(format), format, List.of(first, second));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "csv", "CSV", "unknown", "xlsx", "XLSX", "excel", "EXCEL"})
    void authenticationPermissionAndPasswordChangeRemainRequired(String format) throws Exception {
        mvc.perform(get("/api/v1/reports/tasks/export").param("format", format))
                .andExpect(status().isUnauthorized());
        Long viewer = user("viewer", null);
        signIn(viewer, Set.of(), false);
        mvc.perform(get("/api/v1/reports/tasks/export").param("format", format))
                .andExpect(status().isForbidden());
        signIn(viewer, Set.of("tasks.items.view"), true);
        mvc.perform(get("/api/v1/reports/tasks/export").param("format", format))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingActorFailsBeforeWritingAnyBytes(boolean xml) {
        var output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> {
            if (xml) reports.exportTasksExcelXml(output, null);
            else reports.exportTasksCsv(output, null);
        }).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        assertThat(output.size()).isZero();
    }

    @ParameterizedTest
    @MethodSource("csvTextCases")
    void csvNeutralizesUnsafePrefixesInEveryEditableColumnAndPreservesCsvStructure(String input, boolean unsafe) throws Exception {
        prepareTextCells(input);

        var response = export("csv");
        var exported = rows(response, "csv");
        assertThat(exported).hasSize(2);
        var row = exported.get(1);
        assertThat(row).hasSize(8);
        String expected = unsafe ? "'" + input : input;
        if (unsafe) {
            assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                    .contains("\"" + expected.replace("\"", "\"\"") + "\"");
        }
        for (int column : List.of(1, 2, 4, 7)) assertThat(row.get(column)).isEqualTo(expected);
        assertThat(row.get(3)).isEqualTo("Средний");
        assertThat(row.get(5)).isEqualTo("—");
        assertThat(row.get(6)).isEqualTo("05.09.2026 08:30");
    }

    static Stream<Arguments> csvTextCases() {
        Stream<Arguments> unsafe = Stream.of(
                "=1+1", "+1+1", "-1+1", "@SUM(1)", "＝1+1", "＋1+1", "－1+1", "＠SUM(1)",
                " =1+1", "\t=1+1", "\r=1+1", "\n=1+1", " \tтекст", "\rтекст", "\nтекст",
                "\u0001=1+1", "\u00A0=1+1", "\u2003+1+1", "\u200B@SUM(1)", "\uFEFF-1+1",
                "=1+1\";=2+2", " =1+1\n;\"строка\"")
                .map(value -> Arguments.of(value, true));
        Stream<Arguments> ordinary = Stream.of(
                "Обычная задача", "", "  пробелы", "'уже текст", "Задача; \"кавычки\"",
                "Первая\nВторая", "Первая\r\nВторая", "Обычная;=1+1", "Текст\";=1+1", "Стоимость - 100", "—")
                .map(value -> Arguments.of(value, false));
        return Stream.concat(unsafe, ordinary);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "excel"})
    void xmlKeepsOriginalFormulaLikeTextAsEscapedStringCells(String format) throws Exception {
        String input = "=1+1; <Русский & 'текст' \"цитата\">";
        prepareTextCells(input);

        var exported = rows(export(format), format);
        assertThat(exported).hasSize(2);
        assertThat(exported.get(1)).hasSize(8);
        for (int column : List.of(1, 2, 4, 7)) assertThat(exported.get(1).get(column)).isEqualTo(input);
    }

    private static void prepareTextCells(String text) {
        Long viewer = user("viewer", null);
        assignScope(viewer, "SELF");
        Long taskId = task(text, viewer, viewer);
        Long project = jdbc.sql("insert into ms_task_projects (name) values (:name) returning id")
                .param("name", text).query(Long.class).single();
        jdbc.sql("update ms_tasks set project_id = :project where id = :id")
                .param("project", project).param("id", taskId).update();
        jdbc.sql("update ms_task_statuses set name = :name where id = (select status_id from ms_tasks where id = :id)")
                .param("name", text).param("id", taskId).update();
        jdbc.sql("update md_users set name = :name where id = :id")
                .param("name", text).param("id", viewer).update();
        signIn(viewer, Set.of("tasks.items.view"), false);
    }

    private static MockHttpServletResponse export(String format) throws Exception {
        var request = get("/api/v1/reports/tasks/export");
        if (!format.isEmpty()) request.param("format", format);
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
    }

    private static void assertIds(MockHttpServletResponse response, String format, List<Long> expected) throws Exception {
        List<List<String>> rows = rows(response, format);
        assertThat(rows.getFirst()).containsExactly("ID", "Заголовок", "Проект", "Приоритет", "Статус", "Срок", "Дата создания", "Автор");
        assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(8));
        assertThat(rows.stream().skip(1).map(row -> Long.valueOf(row.getFirst())).toList())
                .containsExactlyElementsOf(expected.stream().sorted(Comparator.reverseOrder()).toList());
    }

    private static List<List<String>> rows(MockHttpServletResponse response, String format) throws Exception {
        if (format.equalsIgnoreCase("xlsx") || format.equalsIgnoreCase("excel")) {
            assertThat(response.getContentType()).isEqualTo("application/vnd.ms-excel; charset=UTF-8");
            assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"tasks-export.xls\"");
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.getContentAsByteArray()));
            var xmlRows = doc.getElementsByTagNameNS(SS, "Row");
            List<List<String>> result = new ArrayList<>();
            for (int r = 0; r < xmlRows.getLength(); r++) {
                var cells = ((Element) xmlRows.item(r)).getElementsByTagNameNS(SS, "Data");
                List<String> values = new ArrayList<>();
                for (int c = 0; c < cells.getLength(); c++) {
                    var cell = (Element) cells.item(c);
                    assertThat(cell.getAttributeNS(SS, "Type")).isEqualTo(r > 0 && c == 0 ? "Number" : "String");
                    assertThat(((Element) cell.getParentNode()).hasAttributeNS(SS, "Formula")).isFalse();
                    values.add(cell.getTextContent());
                }
                result.add(values);
            }
            return result;
        }
        assertThat(response.getContentType()).isEqualTo("text/csv; charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"tasks-export.csv\"");
        String csv = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF");
        return parseCsv(csv.substring(1));
    }

    private static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        var cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char ch = csv.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (ch == ';' && !quoted) {
                row.add(cell.toString()); cell.setLength(0);
            } else if ((ch == '\r' || ch == '\n') && !quoted) {
                if (ch == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); rows.add(row); row = new ArrayList<>(); cell.setLength(0);
            } else cell.append(ch);
        }
        assertThat(quoted).isFalse();
        assertThat(row).isEmpty();
        assertThat(cell).isEmpty();
        return rows;
    }

    private static Long user(String login, Long orgUnitId) {
        return jdbc.sql("""
                insert into md_users (name, login, email, password_hash, state, language, timezone,
                                      attributes, is_2fa_enabled, force_password_change, org_unit_id)
                values (:login, :login, :login || '@example.invalid', 'x', 'A', 'ru', 'UTC', '{}', false, false, :unit)
                returning id
                """).param("login", login).param("unit", orgUnitId).query(Long.class).single();
    }

    private static void assignScope(Long userId, String rule) {
        var role = roles.create("Export " + rule, null, "A", 100);
        scopeRepository.setRoleRule(role.id(), rule);
        roles.assignRolesToUser(userId, List.of(role.id()));
        scopes.recalculateFor(userId);
    }

    private static Long task(String title, Long creator, Long reporter) {
        return jdbc.sql("""
                insert into ms_tasks (title, description_markdown, status_id, priority, reporter_id,
                                      attributes, created_by, modified_by, created_at)
                values (:title, '', (select id from ms_task_statuses order by id limit 1), 'medium', :reporter,
                        '{}', :creator, :creator, '2026-09-05T08:30:00Z') returning id
                """).param("title", title).param("creator", creator).param("reporter", reporter).query(Long.class).single();
    }

    private static void signIn(Long userId, Set<String> permissions, boolean forcePasswordChange) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                userId, "viewer", "viewer@example.invalid", 20L, false, permissions, 1L, forcePasswordChange));
    }
}
