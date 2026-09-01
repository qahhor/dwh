package com.greenwhite.dwh.instance.config.cp;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
@Profile("!migrate")
public class CpControlPlaneClient {

    private static final String TOKEN_HEADER = "X-Instance-Token";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final CpClientProperties properties;
    private final RestClient restClient;

    public CpControlPlaneClient(CpClientProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .requestFactory(timeoutRequestFactory())
                .build();
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public CpHeartbeatReply sendHeartbeat(CpHeartbeatPayload payload) {
        CpHeartbeatReply reply = restClient.post()
                .uri(heartbeatUri())
                .contentType(MediaType.APPLICATION_JSON)
                .header(TOKEN_HEADER, properties.token())
                .body(payload)
                .retrieve()
                .body(CpHeartbeatReply.class);
        if (reply == null) {
            throw new IllegalStateException("Control Plane returned an empty heartbeat response");
        }
        return reply;
    }

    private String heartbeatUri() {
        String baseUrl = properties.url();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/api/v1/instances/heartbeat";
    }

    private static ClientHttpRequestFactory timeoutRequestFactory() {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        return factory;
    }
}
