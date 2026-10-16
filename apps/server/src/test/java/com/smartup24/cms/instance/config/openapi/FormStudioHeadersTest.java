package com.smartup24.cms.instance.config.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.common.entity.FormStudioController;
import com.smartup24.cms.instance.common.entity.FormStudioService;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

class FormStudioHeadersTest {
    @Test
    void nonpersistentHandlerNeverAdvertisesAnIdempotencyKeyWhateverItsPath() throws Exception {
        var docs = new ApiHeadersDocs();
        var operation = new Operation().responses(new ApiResponses().addApiResponse("200", new ApiResponse()));
        var handler = new HandlerMethod(
                new FormStudioController(mock(FormStudioService.class)),
                FormStudioController.class.getMethod("preview", HttpServletRequest.class));
        docs.handlerFacts().customize(operation, handler);
        var api =
                new OpenAPI().paths(new Paths().addPathItem("/api/v1/another-preview", new PathItem().post(operation)));
        docs.commonHeaders().customise(api);
        assertThat(operation.getParameters()).isNull();
        assertThat(operation.getExtensions()).isNull();
    }
}
