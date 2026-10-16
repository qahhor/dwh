package com.smartup24.cms.instance.studio.llm;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiCompatibleLlmProviderTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final java.util.List<OpenAiCompatibleLlmProvider> clients = new java.util.ArrayList<>();
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private String response;
    private int status = 200;
    private long delay;

    @BeforeEach
    void start() throws IOException {
        response = envelope("{\"draft\":true}", "stop");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", "/other");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        clients.forEach(OpenAiCompatibleLlmProvider::close);
    }

    @Test
    void sendsOnlyExplicitInputsAndReturnsOneCompleteChoice() throws Exception {
        assertThat(provider(Duration.ofSeconds(2)).generate("JSON instructions", "Catalogue task"))
                .isEqualTo("{\"draft\":true}");
        var sent = json.readTree(request.get());
        assertThat(sent.path("store").asBoolean()).isFalse();
        assertThat(sent.path("messages").size()).isEqualTo(2);
        assertThat(sent.path("messages").get(1).path("content").asString()).isEqualTo("Catalogue task");
        assertThat(sent.path("model").asString()).isEqualTo("test-model");
        assertThat(sent.path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(authorization.get()).isNull();
    }

    @Test
    void rejectsRedirectErrorsMalformedRefusalIncompleteAndOversizedResponses() {
        for (int code : new int[] {302, 401, 429, 500}) {
            status = code;
            assertThatThrownBy(() -> provider(Duration.ofSeconds(2)).generate("JSON", "task"))
                    .isInstanceOf(IOException.class);
        }
        status = 200;
        for (String value : new String[] {
            "{}",
            "[]",
            "null",
            "{\"choices\":[]}",
            "{\"choices\":[{},{}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":42}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\",\"tool_calls\":[]}}]}",
            "not json",
            envelope("{}", "length"),
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\",\"refusal\":\"no\"}}]}",
            "x".repeat(65537),
            envelope("{}", "stop") + "{}"
        }) {
            response = value;
            assertThatThrownBy(() -> provider(Duration.ofSeconds(2)).generate("JSON", "task"))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void deadlineIncludesResponseAndNotOnlyConnect() {
        delay = 300;
        assertThatThrownBy(() -> provider(Duration.ofMillis(30)).generate("JSON", "task"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void productionRequiresExplicitHttpsEndpointAndModel() {
        try (var disabled = new OpenAiCompatibleLlmProvider(json, "", "", "");
                var enabled = new OpenAiCompatibleLlmProvider(json, "https://model.invalid/chat", "model", "")) {
            assertThat(disabled.isConfigured()).isFalse();
            assertThat(enabled.isConfigured()).isTrue();
            assertThatThrownBy(() -> disabled.generate("JSON", "task")).isInstanceOf(IOException.class);
        }
        for (String url : new String[] {
            "http://localhost/chat",
            "https:///chat",
            "https://a:b@model.invalid/chat",
            "https://model.invalid/chat?key=x",
            "https://model.invalid/chat#fragment"
        })
            assertThatThrownBy(() -> new OpenAiCompatibleLlmProvider(json, url, "model", ""))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedEndpointDoesNotAppearInStartupException() {
        assertThatThrownBy(() ->
                        new OpenAiCompatibleLlmProvider(json, "https://model.invalid/private setting", "model", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LLM endpoint is invalid")
                .hasNoCause();
    }

    @Test
    void sendsAnExplicitCredentialOnlyToTheConfiguredLocalFixture() throws Exception {
        String credential = java.util.UUID.randomUUID().toString();
        try (var configured = new OpenAiCompatibleLlmProvider(
                json,
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/chat",
                "test-model",
                credential,
                Duration.ofSeconds(2))) {
            response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\",\"refusal\":null}}]}";
            assertThat(configured.generate("JSON", "task")).isEqualTo("{}");
            assertThat(authorization.get()).isEqualTo("Bearer " + credential);
        }
    }

    private OpenAiCompatibleLlmProvider provider(Duration timeout) {
        var provider = new OpenAiCompatibleLlmProvider(
                json,
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/chat",
                "test-model",
                "",
                timeout);
        clients.add(provider);
        return provider;
    }

    private String envelope(String content, String finish) {
        return json.writeValueAsString(
                Map.of("choices", List.of(Map.of("finish_reason", finish, "message", Map.of("content", content)))));
    }
}
