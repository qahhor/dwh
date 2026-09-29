package com.smartup24.cms.instance.config.openapi;

import com.smartup24.cms.core.error.ProblemDetailRecord;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API description is generated from the controllers (plan 10/10, item 3.3; springdoc): this class adds what code
 * cannot say — the title, how a caller authenticates, and that every error is RFC 9457 problem details
 * ({@code application/problem+json}, ADR-0021). The committed copy {@code docs/api/openapi.json} is what clients and
 * the breaking-change check read.
 */
@Configuration(proxyBeanMethods = false)
public class ApiDocsConfig {

    static final String BEARER = "BearerAuth";
    static final String SESSION = "SessionCookie";
    static final String PROBLEM = "ProblemDetail";
    static final String PROBLEM_JSON = "application/problem+json";

    @Bean
    OpenAPI smartupCmsApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SmartupCMS Core API")
                        .version("2.0.0")
                        .description("Single-tenant core platform API: idempotent changes (Idempotency-Key), RFC 9457"
                                + " problem details with catalog keys, keyset pagination, scoped rights, search and"
                                + " audit."))
                .servers(List.of(new Server().url("/").description("Current deployment host")))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .description("Personal API token (dwh_...)"))
                        .addSecuritySchemes(
                                SESSION,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.COOKIE)
                                        .name("DWH_SESSION")
                                        .description("HTTP-only session cookie of the web application")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .addSecurityItem(new SecurityRequirement().addList(SESSION));
    }

    /**
     * Every operation may end with problem details: the default response names them, so a client generated from the
     * description knows the error shape without each controller repeating it.
     */
    @Bean
    OpenApiCustomizer problemDetailsEverywhere() {
        return openApi -> {
            Map<String, Schema> schemas = ModelConverters.getInstance().readAll(ProblemDetailRecord.class);
            Components components = openApi.getComponents();
            schemas.forEach((name, schema) -> components.addSchemas(name, schema));
            Schema<?> problem =
                    new Schema<>().$ref("#/components/schemas/" + ProblemDetailRecord.class.getSimpleName());
            ApiResponse error = new ApiResponse()
                    .description("Problem details (RFC 9457): code, catalog key, parameters and the rendered text")
                    .content(new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(problem)));
            SortedSet<String> tags = new TreeSet<>();
            if (openApi.getPaths() != null) {
                openApi.getPaths()
                        .values()
                        .forEach(path -> path.readOperations().forEach(operation -> {
                            if (operation.getResponses() != null
                                    && !operation.getResponses().containsKey("default")) {
                                operation.getResponses().addApiResponse("default", error);
                            }
                            if (operation.getTags() != null) {
                                tags.addAll(operation.getTags());
                            }
                        }));
            }
            // Every tag an operation uses (one per controller) is declared once, in a stable order.
            openApi.setTags(tags.stream().map(name -> new Tag().name(name)).toList());
        };
    }
}
