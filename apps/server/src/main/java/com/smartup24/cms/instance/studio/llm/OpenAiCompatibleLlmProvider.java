package com.smartup24.cms.instance.studio.llm;

import com.smartup24.cms.spi.llm.LlmProvider;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Explicit endpoint and model; no discovery, redirects, retries or input logging (ADR-0039). */
@Component
public class OpenAiCompatibleLlmProvider implements LlmProvider, AutoCloseable {
    private final ObjectMapper json;
    private final HttpClient client;
    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;

    @Autowired
    public OpenAiCompatibleLlmProvider(
            ObjectMapper json,
            @Value("${smc.studio.assistant.endpoint:}") String endpoint,
            @Value("${smc.studio.assistant.model:}") String model,
            @Value("${smc.studio.assistant.api-key:}") String apiKey) {
        this(
                json,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                endpoint,
                model,
                apiKey,
                Duration.ofSeconds(30));
        if (!endpoint.isBlank()) {
            URI uri;
            try {
                uri = URI.create(endpoint);
            } catch (IllegalArgumentException malformed) {
                client.shutdownNow();
                throw new IllegalArgumentException("LLM endpoint is invalid");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getQuery() != null) {
                client.shutdownNow();
                throw new IllegalArgumentException("LLM endpoint must be HTTPS without credentials, query or fragment");
            }
        }
    }

    OpenAiCompatibleLlmProvider(
            ObjectMapper json, HttpClient client, String endpoint, String model, String apiKey, Duration timeout) {
        this.json = json;
        this.client = client;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = timeout;
    }

    @Override
    public String getProviderCode() {
        return "openai-compatible";
    }

    @Override
    public boolean isConfigured() {
        return !endpoint.isBlank() && !model.isBlank();
    }

    @Override
    public String generate(String instructions, String prompt) throws IOException, InterruptedException {
        if (!isConfigured()) throw new IOException("LLM provider is not configured");
        var payload = Map.of(
                "model",
                model,
                "store",
                false,
                "max_completion_tokens",
                8192,
                "response_format",
                Map.of("type", "json_object"),
                "messages",
                List.of(Map.of("role", "system", "content", instructions), Map.of("role", "user", "content", prompt)));
        var builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)));
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        var response = client.sendAsync(builder.build(), info -> new LlmResponseBody());
        try {
            return extract(response.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException("LLM request failed", failure);
        } finally {
            response.cancel(true);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        client.shutdownNow();
    }

    private String extract(HttpResponse<byte[]> response) throws IOException {
        if (response.statusCode() != 200) throw new IOException("LLM request rejected");
        try {
            String body = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(response.body()))
                    .toString();
            var root = json.reader()
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            if (root == null || !root.isObject()) throw new IOException("LLM response is malformed");
            var choices = root.path("choices");
            if (!choices.isArray() || choices.size() != 1) throw new IOException("LLM response has no single choice");
            var choice = choices.get(0);
            var message = choice.path("message");
            if (!"stop".equals(choice.path("finish_reason").asString())
                    || (!message.path("refusal").isMissingNode()
                            && !message.path("refusal").isNull())
                    || message.has("tool_calls")
                    || !message.path("content").isString())
                throw new IOException("LLM response is incomplete or unsupported");
            return message.path("content").asString();
        } catch (JacksonException malformed) {
            throw new IOException("LLM response is malformed", malformed);
        }
    }
}
