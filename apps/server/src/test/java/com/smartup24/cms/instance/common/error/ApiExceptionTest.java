package com.smartup24.cms.instance.common.error;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApiExceptionTest {

    @Test
    void requirePresentPassesAFoundValueWithoutBuildingTheError() {
        assertThatCode(() -> ApiException.requirePresent(Optional.of(7L), () -> {
                    throw new AssertionError("the error is built only when nothing was found");
                }))
                .doesNotThrowAnyException();
    }

    @Test
    void requirePresentRefusesAnEmptyLookupWithTheCallersError() {
        assertThatThrownBy(() -> ApiException.requirePresent(
                        Optional.empty(), () -> ApiException.notFound(ErrorCode.PROJECT_NOT_FOUND, "Проект не найден")))
                .isInstanceOf(ApiException.class)
                .hasMessage("Проект не найден")
                .extracting(error -> ((ApiException) error).getErrorCode())
                .isEqualTo(ErrorCode.PROJECT_NOT_FOUND);
    }
}
