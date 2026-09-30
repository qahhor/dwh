package com.smartup24.cms.instance.config.openapi;

import com.smartup24.cms.instance.common.annotation.ReturnsSecret;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Revisioned;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.config.idempotency.IdempotencyFilter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;

/**
 * The headers and statuses every client meets that no controller signature states (plan 10/10, items 3.3 and 3.6,
 * ADR-0024): {@code If-Match} with its 409 and 428 on a change of a revisioned record, the {@code ETag} of an answer
 * that carries a revision, and {@code Idempotency-Key} on the changes the idempotency filter keeps for a replay.
 */
@Configuration(proxyBeanMethods = false)
public class ApiHeadersDocs {

    static final String ETAG = "ETag";
    static final String IDEMPOTENCY_KEY = IdempotencyFilter.HEADER_IDEMPOTENCY_KEY;

    /** Marks set per handler for the customizer below, which reads and removes them. */
    private static final String REVISIONED_ANSWER = "x-smc-revisioned-answer";

    private static final String RETURNS_SECRET = "x-smc-returns-secret";

    private static final Set<PathItem.HttpMethod> CHANGES = Set.of(
            PathItem.HttpMethod.POST, PathItem.HttpMethod.PUT, PathItem.HttpMethod.PATCH, PathItem.HttpMethod.DELETE);

    /** What only the handler knows: whether its answer is a revisioned record, whether it returns a secret. */
    @Bean
    OperationCustomizer handlerFacts() {
        return (operation, handler) -> {
            ResolvableType answer = ResolvableType.forMethodReturnType(handler.getMethod());
            if (ResponseEntity.class.isAssignableFrom(answer.toClass())) {
                answer = answer.getGeneric(0);
            }
            if (Revisioned.class.isAssignableFrom(answer.toClass())
                    || handler.hasMethodAnnotation(AnswersRevision.class)) {
                operation.addExtension(REVISIONED_ANSWER, true);
            }
            if (handler.hasMethodAnnotation(ReturnsSecret.class)) {
                operation.addExtension(RETURNS_SECRET, true);
            }
            return operation;
        };
    }

    @Bean
    OpenApiCustomizer commonHeaders() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths()
                    .forEach((path, item) -> item.readOperationsMap()
                            .forEach((method, operation) -> describe(openApi, path, method, operation)));
        };
    }

    private static void describe(OpenAPI openApi, String path, PathItem.HttpMethod method, Operation operation) {
        Map<String, Object> marks = operation.getExtensions();
        boolean revisionedAnswer = marks != null && marks.remove(REVISIONED_ANSWER) != null;
        boolean returnsSecret = marks != null && marks.remove(RETURNS_SECRET) != null;
        if (marks != null && marks.isEmpty()) {
            operation.setExtensions(null);
        }
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            return;
        }
        boolean ifMatch = hasHeader(operation, Revisions.IF_MATCH);
        if (ifMatch) {
            responses.computeIfAbsent(
                    "409", code -> problem("The record changed since the revision named in If-Match"));
            responses.computeIfAbsent("428", code -> problem("The change names no revision: send If-Match"));
        } else if (CHANGES.contains(method) && bodyNamesRevision(openApi, operation)) {
            responses.computeIfAbsent("409", code -> problem("The record changed since the revision the body names"));
        }
        if (revisionedAnswer) {
            responses.forEach((code, response) -> {
                if (code.startsWith("2")) {
                    etag(response);
                }
            });
        }
        if (CHANGES.contains(method)
                && IdempotencyFilter.supportsPath(path)
                && !returnsSecret
                && !multipart(operation)
                && !hasHeader(operation, IDEMPOTENCY_KEY)) {
            operation.addParametersItem(new Parameter()
                    .in("header")
                    .name(IDEMPOTENCY_KEY)
                    .required(false)
                    .description("A UUID naming this change: a retry with the same key and body gets the stored"
                            + " answer (Idempotent-Replay: true) instead of running again")
                    .schema(new UUIDSchema()));
        }
    }

    private static boolean hasHeader(Operation operation, String name) {
        return operation.getParameters() != null
                && operation.getParameters().stream()
                        .anyMatch(parameter ->
                                "header".equals(parameter.getIn()) && name.equalsIgnoreCase(parameter.getName()));
    }

    private static boolean multipart(Operation operation) {
        return operation.getRequestBody() != null
                && operation.getRequestBody().getContent() != null
                && operation.getRequestBody().getContent().keySet().stream()
                        .anyMatch(type -> type.startsWith("multipart/"));
    }

    /** Whether the request body names the revision it changes ({@code expectedRevision} or {@code lockVersion}). */
    private static boolean bodyNamesRevision(OpenAPI openApi, Operation operation) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return false;
        }
        return operation.getRequestBody().getContent().values().stream()
                .map(MediaType::getSchema)
                .map(schema -> resolve(openApi, schema))
                .anyMatch(schema -> schema != null
                        && schema.getProperties() != null
                        && (schema.getProperties().containsKey("expectedRevision")
                                || schema.getProperties().containsKey("lockVersion")));
    }

    private static Schema<?> resolve(OpenAPI openApi, Schema<?> schema) {
        if (schema == null || schema.get$ref() == null || openApi.getComponents() == null) {
            return schema;
        }
        String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
        return openApi.getComponents().getSchemas() == null
                ? null
                : openApi.getComponents().getSchemas().get(name);
    }

    private static void etag(ApiResponse response) {
        if (response.getHeaders() == null || !response.getHeaders().containsKey(ETAG)) {
            response.addHeaderObject(
                    ETAG,
                    new Header()
                            .description("The revision of the record, the value a following change sends in If-Match")
                            .schema(new StringSchema()));
        }
    }

    private static ApiResponse problem(String description) {
        Schema<?> problem = new Schema<>().$ref("#/components/schemas/ProblemDetailRecord");
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(ApiDocsConfig.PROBLEM_JSON, new MediaType().schema(problem)));
    }
}
