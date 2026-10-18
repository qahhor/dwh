package com.smartup24.cms.instance.studio;

import static com.smartup24.cms.instance.studio.StudioFormFixtures.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.TestUsers;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/**
 * The reference report "by regions" of stage 1 (ADR-0041, owner decision 4) over the server alone: groups of a live
 * form's reference to a list ordered by a measure or by their labels, cut to a top, named by the list's labels in the
 * reader's language — on screen and in the XLSX — and groups of a reference to another form named by its title; the
 * saved report found in the catalog by its name, ownership and kind.
 */
@TestPropertySource(properties = "smc.studio.activation-enabled=true")
class StudioRegionReportHttpTest extends EmbeddedPostgresTest {
    private static final String BY_REGION = "[{\"field\":\"region\"}]";
    private static final String COUNT_AND_SUM = "[{\"op\":\"count\"},{\"op\":\"sum\",\"field\":\"amount\"}]";

    @Autowired
    private WebApplicationContext context;

    private final StudioFormFixtures forms = new StudioFormFixtures();
    private String tag;
    private String code;
    private String visits;
    private TestSession reader;

    @BeforeEach
    void setup() throws Exception {
        TestUsers users = TestUsers.of(context);
        tag = UUID.randomUUID().toString().substring(0, 8);
        long unit = users.unit("regions-" + tag);
        TestSession publisher = TestSession.signIn(
                context,
                users.withRights(
                                Map.of(
                                        "studio.forms", Set.of("publish"),
                                        "studio.lists", Set.of("view", "manage"),
                                        "md.settings", Set.of("update")),
                                unit)
                        .login());
        String regions = "regions_" + tag;
        forms.list(publisher, regions, List.of("north", "south", "east"));
        code = forms.activate(
                publisher,
                forms.format4(
                        forms.plain(
                                "rclients_" + tag,
                                "Клиенты",
                                "all",
                                List.of(
                                        field("title", "text", true, true),
                                        field(
                                                "region",
                                                "ref",
                                                true,
                                                Map.of("target", Map.of("kind", "list", "code", regions))),
                                        field("amount", "number", false, Map.of("scale", 2)))),
                        "title",
                        null));
        visits = forms.activate(
                publisher,
                forms.format4(
                        forms.plain(
                                "rvisits_" + tag,
                                "Визиты",
                                "all",
                                List.of(field(
                                        "client",
                                        "ref",
                                        true,
                                        Map.of("target", Map.of("kind", "form", "code", code))))),
                        null,
                        null));
        reader = TestSession.signIn(
                context,
                users.withRights(
                                Map.of(
                                        code,
                                        Set.of("view", "create"),
                                        visits,
                                        Set.of("view", "create"),
                                        "md.profile",
                                        Set.of("view", "update")),
                                unit)
                        .login());
        long first = 0;
        for (var row : List.of(
                List.of("Альфа", "north", 10),
                List.of("Бета", "north", 20),
                List.of("Гамма", "north", 5),
                List.of("Дельта", "south", 7),
                List.of("Эпсилон", "east", 1),
                List.of("Зета", "east", 2))) {
            long id =
                    forms.create(reader, code, Map.of("title", row.get(0), "region", row.get(1), "amount", row.get(2)));
            if (first == 0) first = id;
        }
        forms.create(reader, visits, Map.of("client", first));
    }

    @Test
    void theRegionsAreOrderedByAMeasureCutToATopAndNamedByTheirLabels() throws Exception {
        Map<String, Object> top =
                report(Map.of("sort", "{\"by\":\"measure\",\"measure\":0,\"direction\":\"desc\"}", "limit", "2"), "ru");
        assertThat(groups(top)).containsExactly("north", "east");
        assertThat(labels(top)).containsExactly("Тип north", "Тип east");
        assertThat(top.get("truncated")).isEqualTo(true);
        assertThat(values(top, 1)).containsExactly(35.0, 3.0);

        Map<String, Object> words = report(Map.of("sort", "{\"by\":\"label\",\"direction\":\"desc\"}"), "en");
        assertThat(labels(words)).containsExactly("Kind south", "Kind north", "Kind east");
        assertThat(words.get("truncated")).isEqualTo(false);

        MockHttpServletResponse byClient = reader.send(get("/api/v1/entities/" + visits + "/report")
                .param("groupBy", "[{\"field\":\"client\"}]")
                .param("measures", "[{\"op\":\"count\"}]"));
        assertThat(byClient.getStatus()).as(byClient.getContentAsString()).isEqualTo(200);
        assertThat(labels(TestSession.object(byClient))).containsExactly("Альфа");
    }

    @Test
    void aWrongOrderOrTopIsRefusedAtItsPart() throws Exception {
        assertThat(refused(Map.of("sort", "{\"by\":\"size\"}"))).contains("sort.by");
        assertThat(refused(Map.of("sort", "{\"by\":\"measure\",\"measure\":5}")))
                .contains("sort.measure");
        assertThat(refused(Map.of("limit", "0"))).contains("\"limit\"");
        MockHttpServletResponse ungrouped = reader.send(get("/api/v1/entities/" + code + "/report")
                .param("measures", COUNT_AND_SUM)
                .param("limit", "3"));
        assertThat(ungrouped.getStatus()).isEqualTo(422);
        assertThat(ungrouped.getContentAsString()).contains("error.common.field_report_order_ungrouped");
    }

    @Test
    void theExportNamesTheRegionsAndTheCatalogFindsTheSavedReport() throws Exception {
        MockHttpServletResponse file = reader.send(get("/api/v1/entities/" + code + "/report/export")
                .param("groupBy", BY_REGION)
                .param("measures", COUNT_AND_SUM)
                .param("sort", "{\"by\":\"measure\",\"measure\":0,\"direction\":\"desc\"}")
                .param("lang", "en"));
        assertThat(file.getStatus()).isEqualTo(200);
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            List<String> first = new ArrayList<>();
            for (int index = 1; index < rows.size(); index++)
                first.add(rows.get(index).getCellText(0));
            assertThat(first).containsExactly("Kind north", "Kind east", "Kind south");
        }

        Map<String, Object> state = Map.of(
                "groupBy", List.of(Map.of("field", "region")),
                "measures", List.of(Map.of("op", "count")),
                "filter", List.of(),
                "chart", "bar",
                "sort", Map.of("by", "measure", "measure", 0, "direction", "desc"),
                "limit", 5);
        MockHttpServletResponse saved = reader.send(
                post("/api/v1/list-views/" + code),
                Map.of("name", "По регионам " + tag, "kind", "report", "state", state, "isDefault", false),
                null);
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> canonical =
                (Map<String, Object>) TestSession.object(saved).get("state");
        assertThat(canonical).containsEntry("limit", 5).containsKey("sort");
        long id = ((Number) TestSession.object(saved).get("id")).longValue();
        Map<String, Object> run = TestSession.object(reader.send(get("/api/v1/entities/" + code + "/reports/" + id)));
        assertThat(labels(run)).containsExactly("Тип north", "Тип east", "Тип south");

        assertThat(catalog(Map.of("q", "регионам " + tag.toUpperCase(java.util.Locale.ROOT), "owner", "mine")))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item).containsEntry("owned", true).containsEntry("shared", false);
                    assertThat(item).containsEntry("entity", code);
                });
        assertThat(catalog(Map.of("q", tag, "owner", "shared"))).isEmpty();
        assertThat(catalog(Map.of("q", tag, "kind", "widget"))).isEmpty();
        assertThat(catalog(Map.of("listCode", code))).hasSize(1);
        assertThat(reader.send(get("/api/v1/report-views").param("owner", "all"))
                        .getStatus())
                .isEqualTo(422);
    }

    private Map<String, Object> report(Map<String, String> order, String language) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/entities/" + code + "/report")
                .param("groupBy", BY_REGION)
                .param("measures", COUNT_AND_SUM)
                .header("Accept-Language", language);
        order.forEach(request::param);
        MockHttpServletResponse answer = reader.send(request);
        assertThat(answer.getStatus()).as(answer.getContentAsString()).isEqualTo(200);
        return TestSession.object(answer);
    }

    private String refused(Map<String, String> order) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/entities/" + code + "/report")
                .param("groupBy", BY_REGION)
                .param("measures", COUNT_AND_SUM);
        order.forEach(request::param);
        MockHttpServletResponse answer = reader.send(request);
        assertThat(answer.getStatus()).isEqualTo(422);
        return answer.getContentAsString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> catalog(Map<String, String> filter) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/report-views");
        filter.forEach(request::param);
        MockHttpServletResponse answer = reader.send(request);
        assertThat(answer.getStatus()).as(answer.getContentAsString()).isEqualTo(200);
        return (List<Map<String, Object>>) TestSession.object(answer).get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> groups(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("rows"))
                .stream()
                        .map(row -> ((List<Object>) row.get("groups")).getFirst())
                        .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> labels(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("rows"))
                .stream()
                        .map(row -> ((List<Object>) row.get("labels")).getFirst())
                        .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Double> values(Map<String, Object> result, int measure) {
        return ((List<Map<String, Object>>) result.get("rows"))
                .stream()
                        .map(row -> ((Number) ((List<Object>) row.get("values")).get(measure)).doubleValue())
                        .toList();
    }
}
