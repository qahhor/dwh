package com.smartup24.cms.instance.upl;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.fnd.api.ConstraintErrorCode;
import com.smartup24.cms.instance.fnd.api.ConstraintViolationException;
import com.smartup24.cms.instance.fnd.api.StaleVersionException;
import com.smartup24.cms.instance.upl.format.UplErrors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** М-10: перевод ошибок основы и БД в ответ API — каждая ветка {@link UplErrors#toApi}. */
class UplErrorsTest {

    @Test
    @DisplayName("устаревший lockVersion — CONFLICT / STALE_VERSION")
    void staleVersion() {
        assertApi(new StaleVersionException(), ErrorCode.CONFLICT, "error.upl.stale_version");
    }

    @Test
    @DisplayName("черновик уже есть и гонка версий — CONFLICT / FND_VERSION_DRAFT_EXISTS")
    void draftExistsAndVersionConflict() {
        assertApi(
                new ConstraintViolationException(ConstraintErrorCode.FND_VERSION_DRAFT_EXISTS),
                ErrorCode.CONFLICT,
                "error.upl.fnd_version_draft_exists");
        assertApi(
                new ConstraintViolationException(ConstraintErrorCode.FND_VERSION_CONFLICT),
                ErrorCode.CONFLICT,
                "error.upl.fnd_version_draft_exists");
    }

    @Test
    @DisplayName("дата не позже прежней версии — CONFLICT / FND_VERSION_NOT_AFTER_PREVIOUS")
    void notAfterPrevious() {
        assertApi(
                new ConstraintViolationException(ConstraintErrorCode.FND_VERSION_NOT_AFTER_PREVIOUS),
                ErrorCode.CONFLICT,
                "error.upl.fnd_version_not_after_previous");
    }

    @Test
    @DisplayName("неизвестная версия — NOT_FOUND / FND_VERSION_UNKNOWN")
    void unknownVersion() {
        assertApi(
                new ConstraintViolationException(ConstraintErrorCode.FND_VERSION_UNKNOWN),
                ErrorCode.NOT_FOUND,
                "error.upl.fnd_version_unknown");
    }

    @Test
    @DisplayName("ошибка БД upl_format_not_draft в цепочке причин — CONFLICT / UPL_FORMAT_NOT_DRAFT")
    void notDraftFromDatabase() {
        assertApi(
                new DataIntegrityViolationException("x", new RuntimeException("ERROR: upl_format_not_draft")),
                ErrorCode.CONFLICT,
                "error.upl.format_not_draft");
    }

    @Test
    @DisplayName("незнакомая ошибка возвращается тем же объектом")
    void unknownErrorsPassThrough() {
        RuntimeException constraint = new ConstraintViolationException(ConstraintErrorCode.FND_UNIT_UNKNOWN);
        RuntimeException other = new IllegalStateException("x");
        assertThat(UplErrors.toApi(constraint)).isSameAs(constraint);
        assertThat(UplErrors.toApi(other)).isSameAs(other);
    }

    private static void assertApi(RuntimeException source, ErrorCode code, String message) {
        assertThat(UplErrors.toApi(source)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(code);
            assertThat(e.getMessageKey()).isEqualTo(message);
        });
    }
}
