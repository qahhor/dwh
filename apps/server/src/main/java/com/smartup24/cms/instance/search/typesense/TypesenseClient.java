package com.smartup24.cms.instance.search.typesense;

import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP transport shared by the Typesense concern classes ({@link TypesenseCollections}, {@link TypesenseDocuments},
 * {@link TypesenseSearch}, {@link TypesenseHealth}): one RestClient carrying the API key and bounded timeouts.
 */
@Component
public class TypesenseClient {

    static final int CONNECT_TIMEOUT_MS = 1500;
    static final int READ_TIMEOUT_MS = 3000;

    private final TypesenseProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TypesenseClient(TypesenseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS));
        requestFactory.setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS));

        this.restClient = RestClient.builder()
                .baseUrl(properties.url())
                .defaultHeader("X-TYPESENSE-API-KEY", properties.apiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    RestClient rest() {
        return restClient;
    }

    ObjectMapper mapper() {
        return objectMapper;
    }

    JsonNode metadata(String uri, Object... variables) {
        String body = restClient.get().uri(uri, variables).retrieve().body(String.class);
        var value = objectMapper.readTree(body);
        if (value == null || !value.isObject()) throw TypesenseException.invalidResponse();
        return value;
    }

    static Long nonnegativeInteger(JsonNode value, boolean decimalStringAllowed) {
        try {
            if (value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0) return value.asLong();
            if (decimalStringAllowed && value.isString() && value.asString().matches("[0-9]+(?:\\.0+)?")) {
                long result = new java.math.BigDecimal(value.asString()).longValueExact();
                return result >= 0 ? result : null;
            }
        } catch (ArithmeticException overflow) {
            /* unavailable counter */
        }
        return null;
    }
}
