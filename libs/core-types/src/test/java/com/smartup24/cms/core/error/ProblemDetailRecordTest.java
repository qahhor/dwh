package com.smartup24.cms.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Plan 10/10, item 3.1: problem details carry the code, the catalog key with its parameters and the rendered text. */
class ProblemDetailRecordTest {

    @Test
    @DisplayName("a problem names its code, key and parameters; empty parameters are left out")
    void carriesCodeKeyAndParams() {
        var problem = ProblemDetailRecord.of(
                ErrorCode.USER_NOT_FOUND, "error.md.user_missing", Map.of("id", 7), "Нет пользователя 7", "/api/x");

        assertThat(problem.code()).isEqualTo("user_not_found");
        assertThat(problem.status()).isEqualTo(404);
        assertThat(problem.type()).endsWith("/user_not_found");
        assertThat(problem.messageKey()).isEqualTo("error.md.user_missing");
        assertThat(problem.params()).containsEntry("id", 7);
        assertThat(problem.detail()).isEqualTo("Нет пользователя 7");

        var plain = ProblemDetailRecord.of(ErrorCode.CONFLICT, "Текст", "/api/x");
        assertThat(plain.messageKey()).isNull();
        assertThat(plain.params()).isNull();
        assertThat(ProblemDetailRecord.of(ErrorCode.CONFLICT, "error.conflict", Map.of(), "t", "/api/x")
                        .params())
                .isNull();
    }

    @Test
    @DisplayName("a validation problem is 422 with the field errors")
    void validationProblem() {
        var errors = List.of(new FieldErrorItem("name", "NotBlank", "must not be blank"));

        var problem = ProblemDetailRecord.ofValidation("error.validation_failed", Map.of(), "Ошибка", "/api/x", errors);
        assertThat(problem.status()).isEqualTo(422);
        assertThat(problem.code()).isEqualTo("validation_failed");
        assertThat(problem.errors()).isEqualTo(errors);
        assertThat(problem.messageKey()).isEqualTo("error.validation_failed");

        assertThat(ProblemDetailRecord.ofValidation("Ошибка", "/api/x", errors).messageKey())
                .isNull();
    }

    @Test
    @DisplayName("the status follows the response it goes out with")
    void statusFollowsTheResponse() {
        var problem = ProblemDetailRecord.of(ErrorCode.CODE_ALREADY_EXISTS, "Уже есть", "/api/x");

        assertThat(problem.withStatus(400)).isSameAs(problem);
        var conflict = problem.withStatus(409);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("code_already_exists");
        assertThat(conflict.detail()).isEqualTo("Уже есть");
    }
}
