package com.smartup24.cms.instance.config.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import jakarta.servlet.RequestDispatcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0021: errors raised before or outside Spring MVC — the security firewall, the servlet error page — answer
 * {@code application/problem+json} like every other error, not Spring Boot's default error body.
 */
class ProblemOutsideMvcIntegrationTest extends EmbeddedPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private WebApplicationContext wac;

    @Test
    @DisplayName("a request the security firewall rejects answers 400 problem+json")
    void firewallRejectionIsProblemJson() throws Exception {
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/tasks;x=1"))
                            .header("Accept-Language", "en")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo("bad_request");
        assertThat(body.path("messageKey").asString()).isEqualTo("error.request_rejected");
        assertThat(body.path("status").asInt()).isEqualTo(400);
        assertThat(body.has("timestamp")).isTrue();
        assertThat(body.has("error")).as("Spring Boot's default error body").isFalse();
    }

    @Test
    @DisplayName("the servlet error page is the problem details controller, not Spring Boot's default")
    void errorPageIsProblemJson() throws Exception {
        assertThat(context.getBeansOfType(ErrorController.class).values())
                .singleElement()
                .isInstanceOf(ProblemErrorController.class);

        MockMvcBuilders.webAppContextSetup(wac)
                .build()
                .perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 413)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/files/upload"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("payload_too_large"))
                .andExpect(jsonPath("$.instance").value("/api/v1/files/upload"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}
