package com.smartup24.cms.instance.config.openapi;

import com.smartup24.cms.core.error.ProblemDetailRecord;
import com.smartup24.cms.instance.common.web.ApiDeprecations;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

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

    static {
        // A JSON value a DTO carries as is (bulk parameters, a list view's state) is a free-form object in the
        // description, not the getters of Jackson's tree node (nodeType, pojo, missingNode...).
        SpringDocUtils.getConfig()
                .replaceWithSchema(JsonNode.class, freeFormObject())
                .replaceWithSchema(ObjectNode.class, freeFormObject());
    }

    /** A JSON object with any members. */
    static ObjectSchema freeFormObject() {
        ObjectSchema schema = new ObjectSchema();
        schema.setAdditionalProperties(Boolean.TRUE);
        return schema;
    }

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
                                        .description("Personal API token (" + KauthPref.API_TOKEN_PREFIX + "...)"))
                        .addSecuritySchemes(
                                SESSION,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.COOKIE)
                                        .name(KauthPref.SESSION_COOKIE_NAME)
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

    /**
     * The forms of {@link ApiDeprecations#CURRENT} are marked deprecated with the day they stop answering and their
     * successor (plan item 3.4, ADR-0023); each snake_case parameter is listed, deprecated, next to its camelCase name;
     * and a {@code 201} names the created resource in {@code Location}.
     */
    @Bean
    OpenApiCustomizer deprecatedFormsAndLocations() {
        return deprecatedFormsAndLocations(ApiDeprecations.CURRENT);
    }

    /** The same over another table of deprecated forms: the tests use a fixture while {@code CURRENT} is empty. */
    static OpenApiCustomizer deprecatedFormsAndLocations(ApiDeprecations deprecations) {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths()
                    .forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                        deprecations.successor(method.name(), path).ifPresent(successor -> {
                            operation.setDeprecated(true);
                            operation.addExtension("x-sunset", ApiDeprecations.SUNSET.toString());
                            operation.addExtension("x-successor", successor.method() + " " + successor.path());
                        });
                        addLegacyParameters(operation, deprecations.queryParameters());
                        ApiResponse created = operation.getResponses() == null
                                ? null
                                : operation.getResponses().get("201");
                        if (created != null) {
                            created.addHeaderObject(
                                    "Location",
                                    new Header()
                                            .description("Path of the created resource, when it has one")
                                            .schema(new StringSchema()));
                        }
                    }));
        };
    }

    private static void addLegacyParameters(Operation operation, Map<String, String> legacyNames) {
        if (operation.getParameters() == null || legacyNames.isEmpty()) {
            return;
        }
        Map<String, String> legacyByCurrent = new TreeMap<>();
        legacyNames.forEach((legacy, current) -> legacyByCurrent.put(current, legacy));
        List<Parameter> legacy = new ArrayList<>();
        for (Parameter parameter : operation.getParameters()) {
            String name = legacyByCurrent.get(parameter.getName());
            if ("query".equals(parameter.getIn()) && name != null) {
                legacy.add(new Parameter()
                        .in("query")
                        .name(name)
                        .required(false)
                        .deprecated(true)
                        .description("Deprecated name of " + parameter.getName() + "; answers until "
                                + ApiDeprecations.SUNSET)
                        .schema(parameter.getSchema()));
            }
        }
        legacy.forEach(operation::addParametersItem);
    }
}
