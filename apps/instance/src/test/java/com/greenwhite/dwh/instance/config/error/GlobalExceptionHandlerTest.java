package com.greenwhite.dwh.instance.config.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Д-9 (AUDIT-05): клиентская ошибка не должна выдаваться за серверную.
 * Ровно этот дефект наблюдался вживую: POST /api/v1/files отдавал 500 internal_error
 * вместо 405 — и попутно писал в журнал «Unhandled exception», маскируя настоящие сбои.
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ReadOnlyTestController())
            .setControllerAdvice(new GlobalExceptionHandler())
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
    @DisplayName("Поддерживаемый метод по тому же маршруту продолжает работать")
    void supportedMethodStillWorks() throws Exception {
        mvc.perform(get("/api/v1/read-only")).andExpect(status().isOk());
    }

    @RestController
    @RequestMapping("/api/v1/read-only")
    static class ReadOnlyTestController {
        @GetMapping
        String read() {
            return "ok";
        }

        @GetMapping("/duplicate")
        String duplicate() {
            throw new org.springframework.dao.DuplicateKeyException("unique constraint violated");
        }

        @GetMapping("/integrity")
        String integrity() {
            throw new org.springframework.dao.DataIntegrityViolationException("not-null constraint violated");
        }
    }
}
