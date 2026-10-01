package com.smartup24.cms.instance.config.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.ProblemDetailRecord;
import com.smartup24.cms.instance.common.error.ApiException;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** ADR-0021: what fails outside Spring MVC reaches the client as problem details, like an error of a handler. */
class ProblemErrorControllerTest {

    private final ProblemErrorController controller = new ProblemErrorController(PackagedProblemMessages.russian());

    @Test
    @DisplayName("a status sent by the container or a filter answers problem+json with the status's code")
    void sentStatusIsProblemJson() {
        MockHttpServletRequest request = errorRequest(404, null);

        ResponseEntity<ProblemDetailRecord> response = controller.error(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().code()).isEqualTo("not_found");
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().messageKey()).isEqualTo("error.not_found");
        assertThat(response.getBody().instance()).isEqualTo("/api/v1/original");
    }

    @Test
    @DisplayName("an exception thrown in a filter answers 500 internal_error without its cause")
    void filterExceptionHidesItsCause() {
        MockHttpServletRequest request =
                errorRequest(500, new ServletException(new IllegalStateException("secret internal state")));

        ResponseEntity<ProblemDetailRecord> response = controller.error(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("internal_error");
        assertThat(response.getBody().detail()).doesNotContain("secret");
    }

    @Test
    @DisplayName("an ApiException thrown in a filter answers with its own code, key and parameters")
    void filterApiExceptionKeepsItsCode() {
        MockHttpServletRequest request = errorRequest(
                500,
                new ServletException(
                        ApiException.conflict(ErrorCode.CONFLICT, "error.md.module_not_found", Map.of("code", "x"))));

        ResponseEntity<ProblemDetailRecord> response = controller.error(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().messageKey()).isEqualTo("error.md.module_not_found");
        assertThat(response.getBody().params()).containsEntry("code", "x");
    }

    @Test
    @DisplayName("a client status without a code of its own answers bad_request with that status")
    void otherClientStatusKeepsTheStatus() {
        ResponseEntity<ProblemDetailRecord> response = controller.error(errorRequest(400, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("bad_request");
        assertThat(ProblemErrorController.codeOf(HttpStatus.URI_TOO_LONG)).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(ProblemErrorController.codeOf(HttpStatus.BAD_GATEWAY)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ProblemErrorController.codeOf(HttpStatus.valueOf(413))).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(ProblemErrorController.codeOf(HttpStatus.valueOf(422))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private static MockHttpServletRequest errorRequest(int status, Throwable failure) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/original");
        if (failure != null) {
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
        }
        return request;
    }
}
