package com.smartup24.cms.instance.config.openapi;

import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.instance.common.web.Revisions;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Every entity on the runtime in the API description (ADR-0032, 6.13; plan 10/10, item 5.4): instead of one template
 * {@code /api/v1/entities/{code}}, each entity gets its own paths ({@code /api/v1/entities/ms.notes},
 * {@code …/{id}}, {@code …/{id}/archived}, {@code …/{id}/actions/<action>}) with operation ids
 * ({@code listMsNotes}, {@code createMsNotes}…), its tag and its schemas ({@code MsNotesRecord}, {@code MsNotesCreate},
 * {@code MsNotesPatch}, {@code MsNotesPage}) built from its fields, so the web types know each entity. It runs first:
 * the customizers after it add If-Match's 409 and 428, Idempotency-Key, Location and the problem details as for any
 * handler.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EntityOpenApiCustomizer implements OpenApiCustomizer {

    static final String BASE = "/api/v1/entities/";

    private static final Set<String> STANDARD = Set.of("create", "update", "archive", "delete");

    private final List<EntityDefinition> entities;

    public EntityOpenApiCustomizer(List<EntityDefinition> entities) {
        this.entities = entities.stream()
                .filter(entity -> entity.model() != null)
                .sorted((a, b) -> a.code().compareTo(b.code()))
                .toList();
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (entities.isEmpty()) return;
        if (openApi.getComponents() == null) openApi.setComponents(new Components());
        if (openApi.getPaths() == null) openApi.setPaths(new Paths());
        EntitySchemas.shared().forEach(openApi.getComponents()::addSchemas);
        for (EntityDefinition entity : entities) {
            describe(openApi, entity);
        }
    }

    private static void describe(OpenAPI openApi, EntityDefinition entity) {
        String code = entity.code();
        String stem = EntitySchemas.stem(code);
        Components components = openApi.getComponents();
        components.addSchemas(stem + "Record", EntitySchemas.record(entity));
        components.addSchemas(stem + "Page", EntitySchemas.page(stem + "Record"));
        PathItem collection = new PathItem().get(list(entity, stem));
        if (entity.action("create").isPresent()) {
            components.addSchemas(stem + "Create", EntitySchemas.body(entity, true));
            collection.post(operation(entity, "create" + stem, "Create a record of " + code)
                    .requestBody(body(stem + "Create"))
                    .responses(answer("201", "The created record", stem + "Record")));
        }
        openApi.getPaths().addPathItem(BASE + code, collection);
        PathItem record = new PathItem()
                .get(operation(entity, "get" + stem, "Get a record of " + code)
                        .addParametersItem(id())
                        .responses(answer("200", "The record", stem + "Record")));
        if (entity.action("update").isPresent()) {
            components.addSchemas(stem + "Patch", EntitySchemas.body(entity, false));
            record.patch(operation(entity, "patch" + stem, "Change a record of " + code)
                    .addParametersItem(id())
                    .addParametersItem(ifMatch())
                    .requestBody(body(stem + "Patch"))
                    .responses(answer("200", "The changed record", stem + "Record")));
        }
        if (entity.action(EntityDefinition.DELETE).isPresent()) {
            record.delete(operation(entity, "delete" + stem, "Delete a record of " + code)
                    .addParametersItem(id())
                    .addParametersItem(ifMatch())
                    .responses(new ApiResponses().addApiResponse("204", new ApiResponse().description("Deleted"))));
        }
        openApi.getPaths().addPathItem(BASE + code + "/{id}", record);
        if (entity.capabilities().contains(EntityCapability.ARCHIVE)) {
            openApi.getPaths()
                    .addPathItem(
                            BASE + code + "/{id}/archived",
                            new PathItem()
                                    .put(operation(entity, "archive" + stem, "Archive or restore a record of " + code)
                                            .addParametersItem(id())
                                            .addParametersItem(ifMatch())
                                            .requestBody(body(EntitySchemas.ARCHIVED))
                                            .responses(answer("200", "The record", stem + "Record"))));
        }
        for (EntityAction action : entity.actions()) {
            if (STANDARD.contains(action.code())) continue;
            openApi.getPaths()
                    .addPathItem(
                            BASE + code + "/{id}/actions/" + action.code(),
                            new PathItem()
                                    .post(operation(
                                                    entity,
                                                    action.code() + stem,
                                                    "Run " + action.code() + " on a record")
                                            .addParametersItem(id())
                                            .addParametersItem(ifMatch())
                                            .responses(answer("200", "The record", stem + "Record"))));
        }
    }

    private static Operation list(EntityDefinition entity, String stem) {
        Operation list = operation(entity, "list" + stem, "List records of " + entity.code())
                .responses(new ApiResponses()
                        .addApiResponse(
                                "200",
                                new ApiResponse()
                                        .description("A page of the list (ADR-0016)")
                                        .content(json(EntitySchemas.ref(stem + "Page")))));
        for (String name : List.of("q", "filter", "sort", "cursor")) {
            list.addParametersItem(
                    new Parameter().in("query").name(name).required(false).schema(new StringSchema()));
        }
        list.addParametersItem(
                new Parameter().in("query").name("limit").required(false).schema(new IntegerSchema().format("int32")));
        return list;
    }

    private static Operation operation(EntityDefinition entity, String id, String summary) {
        return new Operation()
                .operationId(id)
                .addTagsItem(entity.code())
                .summary(summary)
                .description(summary + " through the general entity runtime (ADR-0032); the entity's rights and its"
                        + " data scope are checked, a record outside the scope answers 404.");
    }

    private static ApiResponses answer(String status, String description, String schema) {
        ApiResponse response = new ApiResponse()
                .description(description)
                .content(json(EntitySchemas.ref(schema)))
                .addHeaderObject(
                        ApiHeadersDocs.ETAG,
                        new Header()
                                .description("The revision of the record, the value a following change sends in"
                                        + " If-Match")
                                .schema(new StringSchema()));
        return new ApiResponses().addApiResponse(status, response);
    }

    private static RequestBody body(String schema) {
        return new RequestBody().required(true).content(json(EntitySchemas.ref(schema)));
    }

    private static Content json(Schema<?> schema) {
        return new Content().addMediaType("application/json", new MediaType().schema(schema));
    }

    private static Parameter id() {
        return new Parameter().in("path").name("id").required(true).schema(new IntegerSchema().format("int64"));
    }

    private static Parameter ifMatch() {
        return new Parameter()
                .in("header")
                .name(Revisions.IF_MATCH)
                .required(false)
                .description("The revision the change is made from, as the ETag of the record read")
                .schema(new StringSchema());
    }
}
