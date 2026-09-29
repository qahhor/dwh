package com.smartup24.cms.instance.config.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Д-9 (AUDIT-05): клиентская ошибка не должна выдаваться за серверную.
 * Ровно этот дефект наблюдался вживую: POST /api/v1/files отдавал 500 internal_error
 * вместо 405 — и попутно писал в журнал «Unhandled exception», маскируя настоящие сбои.
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReadOnlyTestController())
            .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
            .build();

    @Test
    @DisplayName("Неподдерживаемый метод отдаёт 405 method_not_allowed, а не 500")
    void unsupportedMethodReturns405() throws Exception {
        mvc.perform(post("/api/v1/read-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("method_not_allowed"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("Ответ 405 несёт заголовок Allow с разрешёнными методами (RFC 9110)")
    void unsupportedMethodAdvertisesAllowedMethods() throws Exception {
        mvc.perform(post("/api/v1/read-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")));
    }

    @Test
    @DisplayName("Нарушение уникальности отдаёт 409, а не 500")
    void duplicateKeyReturns409() throws Exception {
        mvc.perform(get("/api/v1/read-only/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("code_already_exists"));
    }

    @Test
    @DisplayName("Прочие нарушения целостности отдают 409 conflict")
    void integrityViolationReturns409() throws Exception {
        mvc.perform(get("/api/v1/read-only/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"));
    }

    @Test
    @DisplayName("Multipart body over the configured boundary returns stable 413 problem detail")
    void oversizedMultipartReturnsStable413() throws Exception {
        mvc.perform(get("/api/v1/read-only/oversized-upload"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("file_size_exceeded"))
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    @DisplayName("Поддерживаемый метод по тому же маршруту продолжает работать")
    void supportedMethodStillWorks() throws Exception {
        mvc.perform(get("/api/v1/read-only")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Отключение клиента после committed response не превращается в internal_error")
    void clientDisconnectIsConsumedWithoutResponseRewrite() throws Exception {
        MvcResult initial = mvc.perform(get("/api/v1/read-only/disconnect"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mvc.perform(asyncDispatch(initial))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andReturn();

        assertThat(completed.getResponse().isCommitted()).isTrue();
        assertThat(completed.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class);
    }

    @ParameterizedTest(name = "{0} {1} -> {2} {3}")
    @CsvSource({
        "POST, /api/v1/read-only, 405, method_not_allowed",
        "GET, /api/v1/read-only/duplicate, 409, code_already_exists",
        "GET, /api/v1/read-only/integrity, 409, conflict",
        "GET, /api/v1/read-only/oversized-upload, 413, file_size_exceeded",
        "GET, /api/v1/read-only/api-error, 404, user_not_found",
        "GET, /api/v1/read-only/legacy, 409, conflict",
        "GET, /api/v1/read-only/limit, 400, bad_request",
        "GET, /api/v1/read-only/limit?value=abc, 400, bad_request",
        "GET, /api/v1/read-only/boom, 500, internal_error",
    })
    @DisplayName("3.1: every error answers application/problem+json with its code")
    void everyErrorIsProblemJson(String method, String uri, int status, String code) throws Exception {
        var request = "POST".equals(method) ? post(uri) : get(uri);
        mvc.perform(request)
                .andExpect(status().is(status))
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status));
    }

    @Test
    @DisplayName("3.1: an error names its catalog key and parameters, and the text is rendered from them")
    void errorCarriesKeyParamsAndRenderedText() throws Exception {
        mvc.perform(get("/api/v1/read-only/limit"))
                .andExpect(jsonPath("$.messageKey").value("error.request_param_missing"))
                .andExpect(jsonPath("$.params.name").value("value"))
                .andExpect(jsonPath("$.detail").value("Отсутствует обязательный параметр запроса: value"));
        mvc.perform(get("/api/v1/read-only/api-error"))
                .andExpect(jsonPath("$.messageKey").value("error.user_not_found"))
                .andExpect(jsonPath("$.params").doesNotExist())
                .andExpect(jsonPath("$.detail").value("Пользователь не найден"));
    }

    @Test
    @DisplayName("3.1: a sentence passed by a caller not yet on keys goes out as it is, without a key")
    void legacySentencePassesThrough() throws Exception {
        mvc.perform(get("/api/v1/read-only/legacy"))
                .andExpect(jsonPath("$.messageKey").doesNotExist())
                .andExpect(jsonPath("$.detail").value("Старый текст ошибки"));
    }

    @Test
    @DisplayName("3.1: an unexpected failure says nothing about its cause")
    void unexpectedFailureHidesItsCause() throws Exception {
        mvc.perform(get("/api/v1/read-only/boom"))
                .andExpect(jsonPath("$.detail").value("Внутренняя ошибка сервера. Обратитесь к администратору."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    @DisplayName("3.2: a list body with a missing or blank element answers 422, not 500")
    void invalidListElementIsAClientError() throws Exception {
        for (String body : List.of("[null]", "[{\"name\":\" \"}]")) {
            mvc.perform(post("/api/v1/read-only/items")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("validation_failed"));
        }
        mvc.perform(post("/api/v1/read-only/items")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("[{\"name\":\"ok\"}]"))
                .andExpect(status().isOk());
    }

    record Item(@NotBlank String name) {}

    @RestController
    @RequestMapping("/api/v1/read-only")
    static class ReadOnlyTestController {
        @PostMapping("/items")
        String items(@RequestBody List<@NotNull @Valid Item> items) {
            return String.valueOf(items.size());
        }

        @GetMapping("/api-error")
        String apiError() {
            throw ApiException.notFound(ErrorCode.USER_NOT_FOUND, "error.user_not_found");
        }

        @GetMapping("/legacy")
        String legacy() {
            throw ApiException.conflict(ErrorCode.CONFLICT, "Старый текст ошибки");
        }

        @GetMapping("/limit")
        String limit(@RequestParam int value) {
            return String.valueOf(value);
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret internal state");
        }

        @GetMapping
        String read() {
            return "ok";
        }

        @GetMapping("/duplicate")
        String duplicate() {
            throw new DuplicateKeyException("unique constraint violated");
        }

        @GetMapping("/integrity")
        String integrity() {
            throw new DataIntegrityViolationException("not-null constraint violated");
        }

        @GetMapping("/oversized-upload")
        String oversizedUpload() {
            throw new MaxUploadSizeExceededException(50L * 1024L * 1024L);
        }

        @GetMapping("/disconnect")
        Callable<Void> disconnect(HttpServletResponse response) {
            return () -> {
                response.setStatus(HttpStatus.NO_CONTENT.value());
                response.flushBuffer();
                throw new AsyncRequestNotUsableException("Broken pipe");
            };
        }
    }
}
