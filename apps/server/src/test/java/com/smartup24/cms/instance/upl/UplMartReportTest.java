package com.smartup24.cms.instance.upl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.smartup24.cms.instance.mf.repository.MfFileRepository.FileRecord;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.units.api.Units;
import com.smartup24.cms.instance.upl.UplXlsxFixtures.SheetSpec;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Column;
import com.smartup24.cms.instance.upl.format.UplFormatModel.DataType;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Periodicity;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Sheet;
import com.smartup24.cms.instance.upl.format.UplFormatModel.SourceData;
import com.smartup24.cms.instance.upl.format.UplFormatModel.SourceType;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Strictness;
import com.smartup24.cms.instance.upl.format.UplSourceService;
import com.smartup24.cms.instance.upl.format.UplSourceService.DraftData;
import com.smartup24.cms.instance.upl.mart.UplMartSources;
import com.smartup24.cms.instance.upl.parse.UplParseJob;
import com.smartup24.cms.instance.upl.upload.UplApplyService;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.NewPackage;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import com.smartup24.cms.instance.upl.upload.UplPackageService;
import com.smartup24.cms.instance.warehouse.RawTables;
import com.smartup24.cms.instance.warehouse.WarehousePref;
import com.smartup24.cms.instance.warehouse.api.MartRow;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoad;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoads;
import com.smartup24.cms.instance.warehouse.api.WarehouseUnavailableException;
import com.smartup24.cms.instance.warehouse.mart.JdbcWarehouseMarts;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Constructor Stage 3, step 3.3 (ADR-0048): published {@code upl} file sources become read-only warehouse mart entities
 * {@code mart.<slug>} backed by {@code mart.rows} in {@code pg-dwh}.
 */
class UplMartReportTest extends EmbeddedPostgresTest {

    private static final String SHEET = "TEST лист";
    private static final String KEY_MASK = "^[0-9]{9}$";
    private static final LocalDate PERIOD_FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate PERIOD_TO = LocalDate.of(2026, 3, 31);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UplSourceService sources;

    @Autowired
    private UplPackageService packages;

    @Autowired
    private UplApplyService applies;

    @Autowired
    private UplParseJob parseJob;

    @Autowired
    private JobRunner jobs;

    @Autowired
    private MfFileService files;

    @Autowired
    private Units units;

    @Autowired
    private MdAuditActors actors;

    @Autowired
    private WarehouseLoads loads;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    @Qualifier(WarehousePref.QUALIFIER)
    private JdbcClient warehouseJdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ObjectMapper json;

    private long systemUserId;
    private TestSession viewer;
    private TestSession admin;
    private TestSession stranger;
    private String baseUnit;
    private String derivedUnit;

    @BeforeEach
    void setUp() throws Exception {
        systemUserId = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        baseUnit = "u_base_" + tag;
        derivedUnit = "u_kilo_" + tag;
        tx.executeWithoutResult(status -> {
            actors.apply(actors.system());
            jdbc.sql("delete from upl_package_errors").update();
            jdbc.sql("delete from upl_packages").update();
            jdbc.sql("delete from fnd_job_queue").update();
            jdbc.sql("delete from fnd_job_runs").update();
            units.registerUnit(baseUnit, Map.of("ru", "Баз", "uz", "Baz", "en", "Base"), baseUnit, actors.system());
            units.registerUnit(
                    derivedUnit, Map.of("ru", "Тыс", "uz", "Ming", "en", "Thousand"), baseUnit, actors.system());
            units.publishCoefficient(
                    derivedUnit, baseUnit, new BigDecimal("1000"), LocalDate.of(2026, 1, 1), actors.system());
        });
        RawTables.clear(warehouseJdbc);

        TestUsers testUsers = TestUsers.of(context);
        long orgUnit = testUsers.unit("mart-" + tag);
        viewer = TestSession.signIn(
                context,
                testUsers
                        .withRights(Map.of("upl.sources", Set.of("view"), "md.profile", Set.of("view")), orgUnit)
                        .login());
        admin = TestSession.signIn(
                context,
                testUsers
                        .withRights(
                                Map.of(
                                        "upl.sources",
                                        Set.of("view"),
                                        "md.report_datasets",
                                        Set.of("view", "manage"),
                                        "md.profile",
                                        Set.of("view")),
                                orgUnit)
                        .login());
        stranger = TestSession.signIn(
                context,
                testUsers
                        .withRights(Map.of("md.profile", Set.of("view")), orgUnit)
                        .login());
    }

    @Test
    @DisplayName(
            "3.3: применённый пакет строит витрину mart.<код>, конвертирует единицы, отсекает брак и заменённые загрузки")
    void appliedPackageMaterializesMartAndReportsConvertedActiveLoads() throws Exception {
        String sourceCode = "test.mart-" + UUID.randomUUID().toString().substring(0, 6);
        long sourceId = publishSourceWithUnits(sourceCode);
        String martCode = UplMartSources.martCode(sourceCode);

        // Metadata and i18n are served to a viewer with upl.sources.view and hidden from a stranger.
        MockHttpServletResponse metaResp = viewer.send(get("/api/v1/form-meta/" + martCode));
        assertThat(metaResp.getStatus()).as(metaResp.getContentAsString()).isEqualTo(200);
        assertThat(metaResp.getContentAsString()).contains("\"saved_views\"", "\"mart.");
        assertThat(stranger.send(get("/api/v1/form-meta/" + martCode)).getStatus())
                .isEqualTo(404);

        MockHttpServletResponse queryMetaResp = viewer.send(get("/api/v1/query-meta/" + martCode));
        assertThat(queryMetaResp.getStatus())
                .as(queryMetaResp.getContentAsString())
                .isEqualTo(200);
        assertThat(queryMetaResp.getContentAsString())
                .contains("\"periodFrom\"", "\"sheetName\"", "\"objectKey\"", "\"regionCode\"", "\"amount\"")
                .contains("\"dimension\"");

        MockHttpServletResponse dictResp = viewer.send(get("/api/v1/i18n/ru"));
        assertThat(dictResp.getStatus()).isEqualTo(200);
        assertThat(dictResp.getContentAsString()).contains("Сумма (" + baseUnit + ")");

        // Row CRUD endpoints on mart.* are never served.
        assertThat(viewer.send(get("/api/v1/entities/" + martCode)).getStatus()).isEqualTo(404);

        // First load: 2 accepted rows (key "12345678" padded to "012345678", amount 2.5 -> 2500 and 1.5 -> 1500)
        // plus 1 rejected row ("TEST-BAD", amount 999 -> must be excluded from mart).
        byte[] firstFile = workbook(List.of(
                Arrays.asList(1, "12345678", "REG-A", "Орг 1", "2,5", "15.03.2026"),
                Arrays.asList(2, "012345678", "REG-A", "Орг 2", 1.5, "2026-03-20"),
                Arrays.asList(3, "TEST-BAD", "REG-B", "Брак", 999, "15.03.2026")));
        PackageRow firstApplied = applyWorkbook(sourceId, firstFile);
        assertThat(firstApplied.rowsAccepted()).isEqualTo(2);
        assertThat(firstApplied.rowsRejected()).isEqualTo(1);

        Map<String, Object> firstReport = runReport(
                viewer,
                martCode,
                Map.of(
                        "groupBy", "[{\"field\":\"objectKey\"},{\"field\":\"regionCode\"}]",
                        "measures", "[{\"op\":\"sum\",\"field\":\"amount\"},{\"op\":\"count\"}]"));
        List<Map<String, Object>> firstRows = rows(firstReport);
        assertThat(firstRows).hasSize(1);
        assertThat(firstRows.getFirst().get("groups")).isEqualTo(List.of("012345678", "REG-A"));
        assertThat(values(firstRows.getFirst())).containsExactly(4000.0, 2.0);

        // On-demand materialization: if mart.rows is cleared, querying /report re-materializes from raw.rows.
        warehouseJdbc
                .sql("delete from mart.rows where load_id = :id")
                .param("id", firstApplied.loadId())
                .update();
        Map<String, Object> rematerialized = runReport(
                viewer,
                martCode,
                Map.of(
                        "groupBy", "[{\"field\":\"sheetName\"}]",
                        "measures", "[{\"op\":\"sum\",\"field\":\"amount\"}]"));
        assertThat(values(rows(rematerialized).getFirst())).containsExactly(4000.0);

        // Second load for the same period supersedes the first load; only the second load's rows remain in the report.
        byte[] secondFile = workbook(List.of(Arrays.asList(1, "900000001", "REG-B", "Орг 3", 3.0, 46096)));
        PackageRow secondApplied = applyWorkbook(sourceId, secondFile);
        assertThat(loads.find(firstApplied.loadId()).orElseThrow().status()).isEqualTo(WarehouseLoad.SUPERSEDED);
        assertThat(loads.find(secondApplied.loadId()).orElseThrow().status()).isEqualTo(WarehouseLoad.APPLIED);

        Map<String, Object> secondReport = runReport(
                viewer,
                martCode,
                Map.of(
                        "groupBy", "[{\"field\":\"regionCode\"}]",
                        "measures", "[{\"op\":\"sum\",\"field\":\"amount\"},{\"op\":\"count\"}]"));
        List<Map<String, Object>> secondRows = rows(secondReport);
        assertThat(secondRows).hasSize(1);
        assertThat(secondRows.getFirst().get("groups")).isEqualTo(List.of("REG-B"));
        assertThat(values(secondRows.getFirst())).containsExactly(3000.0, 1.0);

        // Admin dataset over the mart entity works with fixed filters and computed measures.
        String dsCode = "mart_ds_" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> dsBody = Map.of(
                "code", dsCode,
                "entity", martCode,
                "name", Map.of("ru", "Витрина ТЕСТ", "uz", "Vitrina TEST", "en", "Mart TEST"),
                "fields", List.of("regionCode", "amount", "periodFrom"),
                "filter", List.of(Map.of("field", "regionCode", "op", "eq", "value", "REG-B")),
                "measures", List.of());
        MockHttpServletResponse createdDs = admin.send(post("/api/v1/report-datasets"), dsBody, null);
        assertThat(createdDs.getStatus()).as(createdDs.getContentAsString()).isEqualTo(201);

        Map<String, Object> dsReport = runReport(
                viewer,
                martCode,
                Map.of(
                        "dataset", "ds." + dsCode,
                        "groupBy", "[{\"field\":\"regionCode\"}]",
                        "measures", "[{\"op\":\"sum\",\"field\":\"amount\"}]"));
        assertThat(values(rows(dsReport).getFirst())).containsExactly(3000.0);
    }

    @Test
    @DisplayName("3.3: нормализация кодов витрин и перевод ошибок pg-dwh в WarehouseUnavailableException")
    void martCodeNormalizationAndWarehouseUnavailableTranslation() throws Exception {
        assertThat(UplMartSources.martCode("fin.budget-2026")).isEqualTo("mart.fin.budget_2026");
        assertThat(UplMartSources.martCode("123.bad")).isEqualTo("mart.s_123.bad");
        assertThat(UplMartSources.toFieldKey("period_from")).isEqualTo("colPeriodFrom");
        assertThat(UplMartSources.toFieldKey("org_name")).isEqualTo("orgName");

        DataSource brokenDs = Mockito.mock(DataSource.class);
        Mockito.when(brokenDs.getConnection()).thenThrow(new SQLException("pg-dwh down"));
        JdbcWarehouseMarts brokenMarts = new JdbcWarehouseMarts(brokenDs, json);

        assertThatThrownBy(() -> brokenMarts.materializedLoadIds("src", List.of(1L)))
                .isInstanceOf(WarehouseUnavailableException.class);
        assertThatThrownBy(() -> brokenMarts.publishLoad(
                        "src",
                        1L,
                        PERIOD_FROM,
                        PERIOD_TO,
                        List.of(new MartRow(1L, SHEET, Map.of("a", 1))),
                        List.of(1L)))
                .isInstanceOf(WarehouseUnavailableException.class);
    }

    private long publishSourceWithUnits(String sourceCode) {
        SourceData data = new SourceData(
                sourceCode,
                "TEST Витрина",
                "TEST Org",
                null,
                Periodicity.MONTH,
                5,
                SourceType.FILE,
                Strictness.WARNING);
        long sourceId = sources.createSource(data, systemUserId).source().id();
        int version = sources.createDraft(sourceId, null, systemUserId).version();
        int lockVersion = sources.getVersion(sourceId, version).lockVersion();
        List<Column> cols = List.of(
                new Column(null, 0, 1, "№", "row_no", DataType.INTEGER, true, null, null, null, null, null, null),
                new Column(
                        null, 0, 2, "Ключ", "object_key", DataType.OBJECT_KEY, true, null, null, KEY_MASK, 9, 1, null),
                new Column(
                        null,
                        0,
                        3,
                        "Регион",
                        "region_code",
                        DataType.REF_CODE,
                        true,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "md.regions"),
                new Column(
                        null, 0, 4, "Название", "org_name", DataType.TEXT, false, null, null, null, null, null, null),
                new Column(
                        null,
                        0,
                        5,
                        "Сумма",
                        "amount",
                        DataType.NUMBER,
                        true,
                        derivedUnit,
                        baseUnit,
                        null,
                        null,
                        null,
                        null),
                new Column(null, 0, 6, "Дата", "doc_date", DataType.DATE, false, null, null, null, null, null, null));
        DraftData draft = new DraftData(null, null, null, null, List.of(new Sheet(null, 0, SHEET, 2, "Итого", cols)));
        sources.replaceDraft(sourceId, version, lockVersion, draft, systemUserId);
        sources.publish(sourceId, version, LocalDate.of(2026, 1, 1), systemUserId);
        return sourceId;
    }

    private static byte[] workbook(List<List<Object>> rows) {
        List<String> header = List.of("№", "Ключ", "Регион", "Название", "Сумма", "Дата");
        return UplXlsxFixtures.workbook(new SheetSpec(SHEET, 2, header, rows));
    }

    private PackageRow applyWorkbook(long sourceId, byte[] content) {
        FileRecord stored = files.uploadFile(
                "TEST.xlsx",
                UplPackageTestData.XLSX_MIME,
                new ByteArrayInputStream(content),
                content.length,
                systemUserId);
        PackageRow created = packages.register(new NewPackage(
                sourceId,
                1,
                PERIOD_FROM,
                PERIOD_TO,
                stored.id(),
                stored.originalName(),
                stored.sha256(),
                stored.sizeBytes(),
                systemUserId));
        parseJob.run(Map.of("packageId", created.publicId().toString()));
        applies.request(created.publicId().toString(), systemUserId);
        assertThat(jobs.runQueued()).isEqualTo(1);
        return packages.get(created.publicId().toString());
    }

    private static Map<String, Object> runReport(TestSession session, String martCode, Map<String, String> params)
            throws Exception {
        var req = get("/api/v1/entities/" + martCode + "/report");
        params.forEach(req::param);
        MockHttpServletResponse resp = session.send(req);
        assertThat(resp.getStatus()).as(resp.getContentAsString()).isEqualTo(200);
        return TestSession.object(resp);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<Double> values(Map<String, Object> row) {
        return ((List<Number>) row.get("values"))
                .stream().map(Number::doubleValue).toList();
    }
}
