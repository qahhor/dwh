package com.smartup24.cms.instance.config.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Plan 10/10, item 3.3: the API description comes from the code. Every handler of the application is in it, and the
 * committed copy {@code docs/api/openapi.json} — what the web types and the breaking-change check read — is the one
 * the code generates. After a deliberate API change regenerate it with
 * {@code mvn test -pl apps/server -Dtest=OpenApiContractTest -Dopenapi.update=true}.
 */
class OpenApiContractTest extends EmbeddedPostgresTest {

    static final Path COMMITTED = Path.of("../../docs/api/openapi.json");

    /** Handlers that are not part of the API: the description itself and the servlet error page. */
    private static final Set<String> NOT_API = Set.of("/api/v1/openapi.json", "/api/v1/openapi.json.yaml", "/error");

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlers;

    @Test
    @DisplayName("3.3: every handler of the application is described, with the platform's security schemes")
    void everyHandlerIsDescribed() throws Exception {
        JsonNode spec = generated();

        assertThat(spec.path("openapi").asString()).startsWith("3.");
        assertThat(spec.path("info").path("title").asString()).isEqualTo("SmartupCMS Core API");
        assertThat(spec.path("components").path("securitySchemes").has(ApiDocsConfig.BEARER))
                .isTrue();
        assertThat(spec.path("components").path("securitySchemes").has(ApiDocsConfig.SESSION))
                .isTrue();
        assertThat(spec.path("components").path("schemas").has("ProblemDetailRecord"))
                .isTrue();

        List<String> missing = new ArrayList<>();
        handlers.getHandlerMethods().forEach((info, method) -> {
            Set<String> patterns = info.getPatternValues();
            Set<RequestMethod> verbs = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                String path = pattern.replaceAll("\\{([^}:]+):[^}]*}", "{$1}");
                if (NOT_API.contains(path)) {
                    continue;
                }
                JsonNode described = spec.path("paths").path(path);
                for (RequestMethod verb : verbs.isEmpty() ? Set.of(RequestMethod.GET) : verbs) {
                    if (!described.has(verb.name().toLowerCase(Locale.ROOT))) {
                        missing.add(verb + " " + path + " (" + method.getShortLogMessage() + ")");
                    }
                }
            }
        });
        assertThat(new TreeSet<>(missing))
                .as("handlers missing in the API description")
                .isEmpty();
    }

    @Test
    @DisplayName("3.3: docs/api/openapi.json is the description the code generates")
    void committedDescriptionIsCurrent() throws Exception {
        String current = canonical(generated());
        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, current, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(COMMITTED))
                .as("run with -Dopenapi.update=true to write " + COMMITTED)
                .isTrue();
        assertThat(Files.readString(COMMITTED, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .as("the API changed: regenerate docs/api/openapi.json with -Dopenapi.update=true and review the diff")
                .isEqualTo(current);
    }

    private JsonNode generated() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        String body = mvc.perform(get("/api/v1/openapi.json"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return new ObjectMapper().readTree(body);
    }

    private static String canonical(JsonNode spec) throws IOException {
        return new ObjectMapper()
                        .rebuild()
                        .enable(SerializationFeature.INDENT_OUTPUT)
                        .build()
                        .writeValueAsString(spec)
                        .replace("\r\n", "\n")
                + "\n";
    }
}
