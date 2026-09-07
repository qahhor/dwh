package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.search.service.SearchService.SearchHit;
import com.greenwhite.dwh.instance.search.service.SearchQueryPolicy;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient.CollectionSearch;
import com.greenwhite.dwh.instance.search.typesense.TypesenseException;
import com.greenwhite.dwh.instance.search.typesense.TypesenseProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypesenseClientHttpTest {

    private HttpServer server;
    private final List<CapturedRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<Response> configuredResponse = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        configuredResponse.set(new Response(200, validFixture()));
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void allSearchUsesOnePostAndPreservesQueryInTypedMultiSearchBody() {
        TypesenseClient client = client();

        List<CollectionSearch> result = client.multiSearch(
                "Проект 100%_ready & +", "ALL", 4, collections(), SearchQueryPolicy.defaults());

        assertThat(requests).hasSize(1);
        CapturedRequest request = requests.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/multi_search");
        var searches = new ObjectMapper().readTree(request.body()).path("searches");
        assertThat(searches).hasSize(3);
        assertSearch(searches.get(0), "tasks", "Проект 100%_ready & +",
                "title,description_markdown,status_name,project_name", "10,3,2,2", "2,2,2,2", "true,true,true,true", null);
        assertSearch(searches.get(1), "projects", "Проект 100%_ready & +",
                "name,description", "10,3", "2,2", "true,true", "state:=A");
        assertSearch(searches.get(2), "users", "Проект 100%_ready & +",
                "name,login,email,phone", "10,8,6,6", "2,0,0,0", "true,true,true,true", "state:=A");
        assertThat(result).extracting(CollectionSearch::entityType).containsExactly("TASK", "PROJECT", "USER");
        assertThat(result).extracting(CollectionSearch::found).containsExactly(7L, 4L, 1L);
        assertThat(result).extracting(CollectionSearch::searchTimeMs).containsExactly(4L, 3L, 2L);
        assertThat(result.stream().flatMap(group -> group.hits().stream()).toList())
                .extracting(SearchHit::id).containsExactly("11", "12", "13", "21", "22", "31");
    }

    @Test
    void validZeroHitResultIsSuccessful() {
        configuredResponse.set(new Response(200, "{\"results\":[{\"found\":0,\"search_time_ms\":1,\"hits\":[]}]}"));

        List<CollectionSearch> result = client().multiSearch("none", "TASK", 10, collections(), SearchQueryPolicy.defaults());

        assertThat(result).singleElement().satisfies(group -> {
            assertThat(group.entityType()).isEqualTo("TASK");
            assertThat(group.found()).isZero();
            assertThat(group.hits()).isEmpty();
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidResponses")
    void malformedOrPartialResponsesFailTheWholeOperation(String ignoredName, Response response) {
        configuredResponse.set(response);

        assertThatThrownBy(() -> client().multiSearch("safe-query", "ALL", 10, collections(), SearchQueryPolicy.defaults()))
                .isInstanceOf(TypesenseException.class)
                .hasMessageNotContaining("secret-downstream-body")
                .hasMessageNotContaining("safe-query");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidResponses() {
        String validTask = "{\"found\":0,\"search_time_ms\":1,\"hits\":[]}";
        String validProject = "{\"found\":0,\"search_time_ms\":1,\"hits\":[]}";
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("collection error", new Response(200,
                        "{\"results\":[{\"code\":503,\"error\":\"secret-downstream-body\"}," + validProject + "," + validProject + "]}")),
                org.junit.jupiter.params.provider.Arguments.of("missing result", new Response(200,
                        "{\"results\":[" + validTask + "," + validProject + "]}")),
                org.junit.jupiter.params.provider.Arguments.of("invalid JSON", new Response(200, "secret-downstream-body{")),
                org.junit.jupiter.params.provider.Arguments.of("null body", new Response(204, null)),
                org.junit.jupiter.params.provider.Arguments.of("missing hits", new Response(200,
                        "{\"results\":[{\"found\":0,\"search_time_ms\":1}," + validProject + "," + validProject + "]}")),
                org.junit.jupiter.params.provider.Arguments.of("non-array hits", new Response(200,
                        "{\"results\":[{\"found\":0,\"search_time_ms\":1,\"hits\":{}}," + validProject + "," + validProject + "]}")),
                org.junit.jupiter.params.provider.Arguments.of("invalid id", new Response(200,
                        "{\"results\":[{\"found\":1,\"search_time_ms\":1,\"hits\":[{\"document\":{\"id\":\"bad\",\"task_id\":\"bad\",\"title\":\"Task\"}}]}," + validProject + "," + validProject + "]}")),
                org.junit.jupiter.params.provider.Arguments.of("missing document", new Response(200,
                        "{\"results\":[{\"found\":1,\"search_time_ms\":1,\"hits\":[{}]}," + validProject + "," + validProject + "]}"))
        );
    }

    @Test
    void highlightIsReturnedAsBoundedPlaintext() {
        String oversized = "&lt;script&gt;bad&lt;/script&gt;<b>" + "я".repeat(250) + "</b>";
        configuredResponse.set(new Response(200, """
                {"results":[{"found":1,"search_time_ms":1,"hits":[{
                  "document":{"id":"21","project_id":21,"name":"Project","description":"fallback","state":"A"},
                  "highlight":{"description":{"snippet":"%s"}}
                }]}]}
                """.formatted(oversized)));

        SearchHit hit = client().multiSearch("project", "PROJECT", 10, collections(), SearchQueryPolicy.defaults())
                .getFirst().hits().getFirst();

        assertThat(hit.description()).doesNotContain("<", ">");
        assertThat(hit.description().codePointCount(0, hit.description().length())).isEqualTo(240);
    }

    private TypesenseClient client() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new TypesenseClient(new TypesenseProperties(url, "test-key", true, false), new ObjectMapper());
    }

    private void respond(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
        Response configured = configuredResponse.get();
        byte[] response = configured.body() == null ? new byte[0] : configured.body().getBytes(StandardCharsets.UTF_8);
        if (configured.body() != null) exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(configured.status(), configured.body() == null ? -1 : response.length);
        if (response.length > 0) exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static String validFixture() {
        return """
                {"results":[
                  {"found":7,"search_time_ms":4,"hits":[
                    {"document":{"id":"11","task_id":11,"title":"Task 11","description_markdown":"First"}},
                    {"document":{"id":"12","task_id":12,"title":"Task 12","description_markdown":"Second"}},
                    {"document":{"id":"13","task_id":13,"title":"Task 13","description_markdown":"Third"}}]},
                  {"found":4,"search_time_ms":3,"hits":[
                    {"document":{"id":"21","project_id":21,"name":"Project 21","description":"Fourth","state":"A"}},
                    {"document":{"id":"22","project_id":22,"name":"Project 22","description":"Fifth","state":"A"}}]},
                  {"found":1,"search_time_ms":2,"hits":[
                    {"document":{"id":"31","user_id":31,"name":"User 31","login":"user31","email":"user31@example.invalid","state":"A"}}]}
                ]}
                """;
    }

    private static Map<String, String> collections() {
        return Map.of("TASK", "tasks", "PROJECT", "projects", "USER", "users");
    }

    private static void assertSearch(tools.jackson.databind.JsonNode search, String collection, String query,
                                     String fields, String weights, String typos, String prefixes, String filterBy) {
        assertThat(search.path("collection").asText()).isEqualTo(collection);
        assertThat(search.path("q").asText()).isEqualTo(query);
        assertThat(search.path("query_by").asText()).isEqualTo(fields);
        assertThat(search.path("query_by_weights").asText()).isEqualTo(weights);
        assertThat(search.path("num_typos").asText()).isEqualTo(typos);
        assertThat(search.path("prefix").asText()).isEqualTo(prefixes);
        assertThat(search.path("per_page").asInt()).isEqualTo(4);
        assertThat(search.path("highlight_start_tag").asText()).isEmpty();
        assertThat(search.path("highlight_end_tag").asText()).isEmpty();
        if (filterBy == null) assertThat(search.has("filter_by")).isFalse();
        else assertThat(search.path("filter_by").asText()).isEqualTo(filterBy);
    }

    private record CapturedRequest(String method, String path, String body) {}
    private record Response(int status, String body) {}
}
