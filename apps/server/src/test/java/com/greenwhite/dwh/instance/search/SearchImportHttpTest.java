package com.greenwhite.dwh.instance.search;

import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import com.greenwhite.dwh.instance.search.typesense.TypesenseProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.greenwhite.dwh.instance.search.typesense.TypesenseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchImportHttpTest {
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    @org.junit.jupiter.api.AfterEach void closeRegistry() { registry.close(); }
    @Test void slowTrickleYieldsTheBoundedExportUnitWithoutPretendingEof() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();server.setExecutor(executor);
        server.createContext("/",exchange -> {
            try {
                exchange.sendResponseHeaders(200,0);
                for (byte value:"{\"id\":\"7\"}\n".getBytes(StandardCharsets.UTF_8)) {
                    exchange.getResponseBody().write(value);exchange.getResponseBody().flush();Thread.sleep(200);
                }
            } catch (java.io.IOException | InterruptedException expectedOnClose) { /* bounded client yield closes its cursor */ }
            finally { exchange.close(); }
        });server.start();
        var client=new TypesenseClient(new TypesenseProperties("http://127.0.0.1:"+server.getAddress().getPort(),"fixture-key",true,false),new ObjectMapper());
        try (var stream=client.openDocumentMetadata("candidate_users")) {
            long started=System.nanoTime();var page=stream.readPage(100,1_048_576);
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(1800);
            assertThat(page).isEmpty();assertThat(stream.exhausted()).isFalse();
        } finally { server.stop(0);executor.shutdownNow(); }
    }
    @Test
    void streamedMetadataRehashesTheBodyInsteadOfTrustingStoredFingerprint() throws Exception {
        withServer("{\"id\":\"7\",\"title\":\"changed\",\"_projection_revision\":2,\"_projection_fingerprint\":\"old\"}\n", (client, captured) -> {
            var metadata = new java.util.ArrayList<com.greenwhite.dwh.instance.search.typesense.TypesenseDocumentStream.DocumentMetadata>();
            client.forEachDocumentMetadata("candidate_tasks", metadata::add);
            assertThat(metadata).singleElement().satisfies(value -> {
                assertThat(value.id()).isEqualTo("7");
                assertThat(value.revision()).isEqualTo(2);
                assertThat(value.fingerprint()).isEqualTo("old");
                assertThat(value.contentFingerprint()).isEqualTo("7bba28573f42a301e13950e7aa0e4fddb210eb7647161fa07ffaacc9792cdf8f");
            });
            assertThat(captured.get().path()).isEqualTo("/collections/candidate_tasks/documents/export");
        });
    }

    @Test
    void metadataCursorYieldsAtRowBoundAndReportsEofSeparately() throws Exception {
        withServer("{\"id\":\"7\",\"title\":\"first\"}\n{\"id\":\"8\",\"title\":\"second\"}\n", (client, captured) -> {
            try (var stream = client.openDocumentMetadata("candidate_tasks")) {
                assertThat(stream.readPage(1, 1_048_576)).extracting(com.greenwhite.dwh.instance.search.typesense.TypesenseDocumentStream.DocumentMetadata::id).containsExactly("7");
                assertThat(stream.exhausted()).isFalse();
                assertThat(stream.readPage(1, 1_048_576)).extracting(com.greenwhite.dwh.instance.search.typesense.TypesenseDocumentStream.DocumentMetadata::id).containsExactly("8");
                assertThat(stream.readPage(1, 1_048_576)).isEmpty();
                assertThat(stream.exhausted()).isTrue();
            }
        });
    }

    @Test
    void oversizedExportLineFailsBeforeUnboundedParsing() throws Exception {
        withServer("{\"id\":\"7\",\"title\":\"" + "x".repeat(1_048_577), (client, captured) ->
                assertThatThrownBy(() -> client.forEachDocumentMetadata("candidate_tasks", ignored -> {})).isInstanceOf(TypesenseException.class));
    }

    @Test
    void importUsesJsonLinesAndKeepsSuccessAndFailureAlignedWithInputs() throws Exception {
        withServer("{\"success\":true}\n{\"success\":false,\"code\":400,\"error\":\"private-body\"}", (client, captured) -> {
            var acknowledgements = client.importDocuments("candidate_tasks", List.of(Map.of("id", "7", "title", "first"), Map.of("id", "8", "title", "second")));
            assertThat(acknowledgements).extracting(TypesenseClient.ImportAck::id).containsExactly("7", "8");
            assertThat(acknowledgements).extracting(TypesenseClient.ImportAck::success).containsExactly(true, false);
            assertThat(acknowledgements.get(1).errorCode()).isEqualTo("IMPORT_REJECTED");
            assertThat(registry.find("dwh.search.import.rows").tag("outcome","SUCCESS").counter().count()).isOne();
            assertThat(registry.find("dwh.search.import.rows").tag("outcome","FAILURE").counter().count()).isOne();
            assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getKey()).isEqualTo("outcome");assertThat(tag.getValue()).isIn("SUCCESS","FAILURE");
            }));
            assertThat(captured.get().path()).isEqualTo("/collections/candidate_tasks/documents/import?action=upsert");
            assertThat(captured.get().body().lines().count()).isEqualTo(2);
            assertThat(new ObjectMapper().readTree(captured.get().body().lines().toList().get(1)).path("id").asString()).isEqualTo("8");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"success\":true}", "{\"success\":true}\n{\"success\":true}\n{\"success\":true}",
            "{\"success\":true}\nprivate-body{", "{\"success\":true}\n{\"success\":\"true\"}", ""})
    void incompleteExtraOrMalformedAcknowledgementsNeverAcknowledgeBatch(String response) throws Exception {
        withServer(response, (client, captured) -> assertThatThrownBy(() -> client.importDocuments("candidate_tasks",
                List.of(Map.of("id", "7"), Map.of("id", "8"))))
                .isInstanceOf(TypesenseException.class).hasMessageNotContaining("private-body"));
    }

    @Test
    void oversizedDocumentHasVisibleFailureWithoutAnHttpRequest() throws Exception {
        withServer("{\"success\":true}", (client, captured) -> {
            var result = client.importDocuments("candidate_tasks", List.of(Map.of("id", "7", "title", "x".repeat(1_048_577))));
            assertThat(result).singleElement().satisfies(ack -> {
                assertThat(ack.success()).isFalse();
                assertThat(ack.errorCode()).isEqualTo("DOCUMENT_TOO_LARGE");
            });
            assertThat(captured.get()).isNull();
        });
    }

    private record Captured(String path, String body) {}
    private interface Exercise { void run(TypesenseClient client, AtomicReference<Captured> captured) throws Exception; }
    private void withServer(String response, Exercise exercise) throws Exception {
        var captured = new AtomicReference<Captured>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(new Captured(exchange.getRequestURI().toString(), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            exercise.run(new TypesenseClient(new TypesenseProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                    "fixture-key", true, false), new ObjectMapper(),java.util.Optional.of(new com.greenwhite.dwh.instance.search.service.SearchMetrics(registry))), captured);
        } finally { server.stop(0); }
    }

    @Test
    void normalizedRuSchemaIsVerifiedWithoutChangingIdentifiers() throws Exception {
        // A missing RU comparator, or stemming identifier fields, must fail this real HTTP contract.
        String schema = """
                {"name":"ru_users","num_documents":0,"fields":[
                  {"name":"user_id","type":"int64","locale":"","sort":true},
                  {"name":"name","type":"string","locale":"ru","stem":true},
                  {"name":"login","type":"string","locale":"","stem":false},
                  {"name":"email","type":"string","locale":"","stem":false},
                  {"name":"phone","type":"string","locale":"","optional":true,"stem":false},
                  {"name":"state","type":"string","locale":"","facet":true},
                  {"name":"_projection_revision","type":"int64","index":false,"sort":false,"locale":""},
                  {"name":"_projection_fingerprint","type":"string","index":false,"locale":""}
                ]}
                """;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = schema.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new TypesenseClient(new TypesenseProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-key", true, false), new ObjectMapper());
            assertThat(client.observeCollection("ru_users", "USER", "RU").schemaMatches()).isTrue();
        } finally {
            server.stop(0);
        }
    }
}
