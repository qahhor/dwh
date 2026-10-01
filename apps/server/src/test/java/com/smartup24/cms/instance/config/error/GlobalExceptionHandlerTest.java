package com.smartup24.cms.instance.config.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * A client error must not pass for a server error. Exactly this defect was seen live: POST /api/v1/files
 * answered 500 internal_error instead of 405 and also wrote "Unhandled exception" to the log, hiding real
 * failures.
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
    @DisplayName("3.1: a sentence instead of a key never reaches the client: the code's own text goes out")
    void sentenceIsReplacedByTheCodeText() throws Exception {
        mvc.perform(get("/api/v1/read-only/legacy"))
                .andExpect(jsonPath("$.messageKey").value("error.conflict"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not("Старый текст ошибки")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Старый"))));
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

    @Test
    @DisplayName("3.1: a body in a type the route does not read answers 415 problem+json with Accept")
    void unsupportedContentTypeIs415() throws Exception {
        mvc.perform(post("/api/v1/read-only/items").contentType("text/plain").content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("unsupported_media_type"))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.messageKey").value("error.request_media_type_unsupported"))
                .andExpect(jsonPath("$.params.contentType").value("text/plain"))
                .andExpect(header().string("Accept", org.hamcrest.Matchers.containsString("application/json")));
    }

    @Test
    @DisplayName("3.1: a route that cannot answer in an accepted type answers 406 problem+json")
    void notAcceptableIs406() throws Exception {
        mvc.perform(get("/api/v1/read-only/json-only").accept("text/csv"))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("not_acceptable"))
                .andExpect(jsonPath("$.messageKey").value("error.not_acceptable"));
    }

    @Test
    @DisplayName("3.1: an upload without its file part answers 400 problem+json, not 500")
    void missingPartIs400() throws Exception {
        mvc.perform(multipart("/api/v1/read-only/upload").param("note", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("bad_request"))
                .andExpect(jsonPath("$.messageKey").value("error.request_part_missing"))
                .andExpect(jsonPath("$.params.name").value("file"));
    }

    @Test
    @DisplayName("3.1: an upload that is not multipart answers 400 problem+json, not 500")
    void notMultipartIs400() throws Exception {
        mvc.perform(post("/api/v1/read-only/upload")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.messageKey").value("error.request_multipart_invalid"));
    }

    @Test
    @DisplayName("3.1: a missing required header answers 400 problem+json with its name")
    void missingHeaderIs400() throws Exception {
        mvc.perform(get("/api/v1/read-only/header"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.messageKey").value("error.request_header_missing"))
                .andExpect(jsonPath("$.params.name").value("X-Thing"));
    }

    @Test
    @DisplayName("3.1: a missing required cookie answers 400 problem+json with its name")
    void missingCookieIs400() throws Exception {
        mvc.perform(get("/api/v1/read-only/cookie"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.messageKey").value("error.request_cookie_missing"))
                .andExpect(jsonPath("$.params.name").value("thing"));
    }

    @Test
    @DisplayName("3.1: a keyed field error carries its key and params, its message rendered in the request language")
    void keyedFieldErrorIsRenderedInTheRequestLanguage() throws Exception {
        MockMvc multilingual = MockMvcBuilders.standaloneSetup(new ReadOnlyTestController())
                .setControllerAdvice(new GlobalExceptionHandler(new PackagedProblemMessages()))
                .build();

        multilingual
                .perform(get("/api/v1/read-only/field-error").header("Accept-Language", "en"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("state"))
                .andExpect(jsonPath("$.errors[0].code").value("invalid"))
                .andExpect(jsonPath("$.errors[0].messageKey").value("error.field.one_of"))
                .andExpect(jsonPath("$.errors[0].params.values").value("A, P"))
                .andExpect(jsonPath("$.errors[0].message").value("Allowed values: A, P"))
                .andExpect(jsonPath("$.errors[1].messageKey").doesNotExist())
                .andExpect(jsonPath("$.errors[1].message").value("written by bean validation"));
        multilingual
                .perform(get("/api/v1/read-only/field-error").header("Accept-Language", "ru"))
                .andExpect(jsonPath("$.errors[0].message").value("Допустимые значения: A, P"));
    }

    record Item(@NotBlank String name) {}

    @RestController
    @RequestMapping("/api/v1/read-only")
    static class ReadOnlyTestController {
        @PostMapping("/items")
        String items(@RequestBody List<@NotNull @Valid Item> items) {
            return String.valueOf(items.size());
        }

        @GetMapping(value = "/json-only", produces = "application/json")
        String jsonOnly() {
            return "{}";
        }

        @PostMapping("/upload")
        String upload(@RequestParam("file") MultipartFile file) {
            return file.getOriginalFilename();
        }

        @GetMapping("/header")
        String header(@RequestHeader("X-Thing") String thing) {
            return thing;
        }

        @GetMapping("/cookie")
        String cookie(@CookieValue("thing") String thing) {
            return thing;
        }

        @GetMapping("/field-error")
        String fieldError() {
            throw ApiException.validation(
                    "error.validation_failed",
                    List.of(
                            FieldErrorItem.keyed("state", "invalid", "error.field.one_of", Map.of("values", "A, P")),
                            new FieldErrorItem("name", "NotBlank", "written by bean validation")));
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
