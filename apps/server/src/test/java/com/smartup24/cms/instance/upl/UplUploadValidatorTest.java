package com.smartup24.cms.instance.upl;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.upl.upload.UplUploadValidator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Checking the fields of a file upload request: all errors are collected at once. */
class UplUploadValidatorTest {

    private static final String FROM = "2026-03-01";
    private static final String TO = "2026-03-31";
    private static final String SOURCE = "7";
    private static final long SIZE = 128L;

    @Test
    @DisplayName("Пустой запрос: все четыре ошибки приходят одним списком")
    void emptyRequestReportsEveryField() {
        List<FieldErrorItem> errors = UplUploadValidator.validate(null, null, null, false, null, 0);

        assertThat(errors)
                .extracting(FieldErrorItem::code)
                .containsExactly(
                        UplUploadValidator.UPL_PKG_SOURCE_REQUIRED,
                        UplUploadValidator.UPL_PKG_PERIOD_REQUIRED,
                        UplUploadValidator.UPL_PKG_PERIOD_REQUIRED,
                        UplUploadValidator.UPL_PKG_FILE_REQUIRED);
        assertThat(errors)
                .extracting(FieldErrorItem::field)
                .containsExactly("sourceId", "periodFrom", "periodTo", "file");
        assertThat(errors).allSatisfy(error -> assertThat(error.message()).isNotBlank());
    }

    @Test
    @DisplayName("Дата не в формате yyyy-MM-dd — ошибка своего поля")
    void unreadableDateIsReportedOnItsOwnField() {
        List<FieldErrorItem> errors = UplUploadValidator.validate(SOURCE, FROM, "31.03.2026", true, "TEST.xlsx", SIZE);

        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("periodTo");
            assertThat(error.code()).isEqualTo(UplUploadValidator.UPL_PKG_PERIOD_REQUIRED);
        });
    }

    @Test
    @DisplayName("Начало периода позже конца")
    void periodStartAfterEnd() {
        List<FieldErrorItem> errors = UplUploadValidator.validate(SOURCE, TO, FROM, true, "TEST.xlsx", SIZE);

        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("periodFrom");
            assertThat(error.code()).isEqualTo(UplUploadValidator.UPL_PKG_PERIOD_ORDER);
        });
    }

    @Test
    @DisplayName("Пустой файл")
    void emptyFile() {
        List<FieldErrorItem> errors = UplUploadValidator.validate(SOURCE, FROM, TO, true, "TEST.xlsx", 0);

        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("file");
            assertThat(error.code()).isEqualTo(UplUploadValidator.UPL_PKG_FILE_EMPTY);
        });
    }

    @Test
    @DisplayName("Расширение проверяется без учёта регистра")
    void extensionIsCheckedIgnoringCase() {
        List<FieldErrorItem> wrongExtension = UplUploadValidator.validate(SOURCE, FROM, TO, true, "a.xls", SIZE);
        assertThat(wrongExtension)
                .singleElement()
                .satisfies(error -> assertThat(error.code()).isEqualTo(UplUploadValidator.UPL_PKG_FILE_NOT_XLSX));

        assertThat(UplUploadValidator.validate(SOURCE, FROM, TO, true, "A.XLSX", SIZE))
                .isEmpty();
    }
}
