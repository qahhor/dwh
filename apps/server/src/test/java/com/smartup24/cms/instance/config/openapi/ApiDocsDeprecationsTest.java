package com.smartup24.cms.instance.config.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.web.ApiDeprecations;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 3.4 (ADR-0023): the API description marks what {@link ApiDeprecations} lists. No form is deprecated
 * now, so the marking is checked on a fixture table and the current table must mark nothing.
 */
class ApiDocsDeprecationsTest {

    private static final ApiDeprecations FIXTURE = new ApiDeprecations(
            List.of(ApiDeprecations.alias(
                    "POST", "/api/v1/widgets/{code}/toggle", "PUT", "/api/v1/widgets/{code}/enabled")),
            Map.of("project_id", "projectId"));

    @Test
    @DisplayName("3.4: a deprecated operation names its sunset and successor, a legacy parameter sits by its successor")
    void fixtureFormsAreMarked() {
        OpenAPI api = description();

        ApiDocsConfig.deprecatedFormsAndLocations(FIXTURE).customise(api);

        Operation toggle = api.getPaths().get("/api/v1/widgets/{code}/toggle").getPost();
        assertThat(toggle.getDeprecated()).isTrue();
        assertThat(toggle.getExtensions())
                .containsEntry("x-sunset", ApiDeprecations.SUNSET.toString())
                .containsEntry("x-successor", "PUT /api/v1/widgets/{code}/enabled");
        List<Parameter> parameters =
                api.getPaths().get("/api/v1/tasks").getGet().getParameters();
        assertThat(parameters).extracting(Parameter::getName).containsExactly("projectId", "project_id");
        assertThat(parameters.get(1).getDeprecated()).isTrue();
        assertThat(api.getPaths()
                        .get("/api/v1/tasks")
                        .getPost()
                        .getResponses()
                        .get("201")
                        .getHeaders())
                .containsKey("Location");
    }

    @Test
    @DisplayName("ADR-0023: the current table marks nothing deprecated")
    void currentTableMarksNothing() {
        OpenAPI api = description();

        ApiDocsConfig.deprecatedFormsAndLocations(ApiDeprecations.CURRENT).customise(api);

        assertThat(api.getPaths().get("/api/v1/widgets/{code}/toggle").getPost().getDeprecated())
                .isNull();
        assertThat(api.getPaths().get("/api/v1/tasks").getGet().getParameters())
                .extracting(Parameter::getName)
                .containsExactly("projectId");
    }

    private static OpenAPI description() {
        Operation toggle = new Operation().responses(new ApiResponses().addApiResponse("200", new ApiResponse()));
        Operation list = new Operation()
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse()))
                .addParametersItem(new Parameter().in("query").name("projectId").schema(new StringSchema()));
        Operation create = new Operation().responses(new ApiResponses().addApiResponse("201", new ApiResponse()));
        return new OpenAPI()
                .paths(new Paths()
                        .addPathItem("/api/v1/widgets/{code}/toggle", new PathItem().post(toggle))
                        .addPathItem("/api/v1/tasks", new PathItem().get(list).post(create)));
    }
}
