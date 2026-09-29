package com.smartup24.cms.instance.config.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.bulk.BulkRunner;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkResult;
import com.smartup24.cms.instance.common.error.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plan 10/10, item 3.1: the reason of a failed record of a bulk action is the text of its key in the request's
 * language, as {@code detail} is, not the key itself.
 */
class BulkResultMessagesTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BulkTestController())
            .setControllerAdvice(new BulkResultMessages(new PackagedProblemMessages()))
            .build();

    @Test
    @DisplayName("3.1: a failed record carries its key and parameters and the text rendered in Russian")
    void failureIsRenderedInRussianByDefault() throws Exception {
        mvc.perform(get("/bulk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.failed").value(3))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[0].message").doesNotExist())
                .andExpect(jsonPath("$.results[1].code").value("not_found"))
                .andExpect(jsonPath("$.results[1].messageKey").value("error.common.record_not_found"))
                .andExpect(jsonPath("$.results[1].message").value("Запись не найдена"))
                .andExpect(jsonPath("$.results[2].messageKey").value("error.file.extension_forbidden"))
                .andExpect(jsonPath("$.results[2].params.extension").value(".exe"))
                .andExpect(jsonPath("$.results[2].message")
                        .value("Загрузка исполняемых файлов (.exe) запрещена правилами безопасности"))
                .andExpect(jsonPath("$.results[3].code").value("bulk_item_failed"))
                .andExpect(jsonPath("$.results[3].message").value("BULK_ITEM_FAILED"));
    }

    @Test
    @DisplayName("3.1: the text follows Accept-Language")
    void failureFollowsTheRequestLanguage() throws Exception {
        mvc.perform(get("/bulk-test").header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[1].message").value("The record was not found"));
    }

    @RestController
    static class BulkTestController {

        @GetMapping("/bulk-test")
        ResponseEntity<BulkResult> bulk() {
            return ResponseEntity.ok(BulkRunner.run("delete", List.of(1L, 2L, 3L, 4L), id -> {
                if (id == 2) {
                    throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.record_not_found");
                }
                if (id == 3) {
                    throw ApiException.badRequest(
                            ErrorCode.FILE_TYPE_FORBIDDEN,
                            "error.file.extension_forbidden",
                            Map.of("extension", ".exe"));
                }
                if (id == 4) {
                    throw new IllegalStateException("internal detail that must not leak");
                }
            }));
        }
    }
}
