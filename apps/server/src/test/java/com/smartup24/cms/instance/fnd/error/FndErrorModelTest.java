package com.smartup24.cms.instance.fnd.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.config.error.GlobalExceptionHandler;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.fnd.api.ConstraintErrorCode;
import com.smartup24.cms.instance.fnd.api.ConstraintViolationException;
import com.smartup24.cms.instance.fnd.api.DwhUnavailableException;
import com.smartup24.cms.instance.fnd.api.FndCoefficientMissingException;
import com.smartup24.cms.instance.fnd.api.StaleVersionException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * План 10/10, п. 3.1: исключения основы — часть единой модели ошибок. Каждый {@link ConstraintErrorCode} несёт код
 * ответа и ключ {@code error.fnd.<код>}, текст которого есть в ru, en и uz; обработчик отвечает problem+json, а не 500.
 */
class FndErrorModelTest {

    private static final Path CATALOGS = Path.of("src/main/resources/i18n");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)}");

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FndThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
            .build();

    @Test
    @DisplayName("3.1: каждый код основы — ключ error.fnd.<код>, текст есть в ru, en, uz")
    void everyCodeHasItsTextInEveryCatalog() {
        List<String> keys = new ArrayList<>();
        for (ConstraintErrorCode code : ConstraintErrorCode.values()) {
            assertThat(code.messageKey()).isEqualTo("error.fnd." + code.code());
            assertThat(ApiException.MESSAGE_KEY.matcher(code.messageKey()).matches())
                    .as(code.messageKey())
                    .isTrue();
            keys.add(code.messageKey());
        }
        keys.add("error.fnd.coefficient_missing");

        Map<String, Map<String, String>> catalogs =
                Map.of("ru", catalog("ru"), "en", catalog("en"), "uz", catalog("uz"));
        for (String key : keys) {
            Set<String> russian = placeholders(catalogs.get("ru").get(key));
            for (var catalog : catalogs.entrySet()) {
                String text = catalog.getValue().get(key);
                assertThat(text).as(catalog.getKey() + ": " + key).isNotBlank();
                assertThat(placeholders(text)).as(catalog.getKey() + ": " + key).isEqualTo(russian);
            }
        }
    }

    @Test
    @DisplayName("3.1: код основы переводится в код ответа по смыслу")
    void codesMapToResponseCodesByMeaning() {
        assertThat(ConstraintErrorCode.FND_UNITS_UK_CODE.errorCode()).isEqualTo(ErrorCode.CODE_ALREADY_EXISTS);
        assertThat(ConstraintErrorCode.FND_UNIT_COEFFICIENT_VERSIONS_EX_VALID.errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(ConstraintErrorCode.FND_UNITS_FK_BASE_UNIT.errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(ConstraintErrorCode.FND_LOADS_CK_PERIOD.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ConstraintErrorCode.STALE_VERSION.errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(ConstraintErrorCode.FND_VERSION_UNKNOWN.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ConstraintErrorCode.FND_LOAD_STATUS_TRANSITION.errorCode())
                .isEqualTo(ErrorCode.STATUS_TRANSITION_FORBIDDEN);
        assertThat(ConstraintErrorCode.DWH_UNAVAILABLE.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        for (ConstraintErrorCode code : ConstraintErrorCode.values()) {
            if (code.constraintName().map(name -> name.contains("_ck_")).orElse(false)) {
                assertThat(code.errorCode()).as(code.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            }
            if (code.constraintName().map(name -> name.contains("_fk_")).orElse(false)) {
                assertThat(code.errorCode()).as(code.code()).isEqualTo(ErrorCode.CONFLICT);
            }
        }
    }

    @Test
    @DisplayName("3.1: код и причина исключения основы сохраняются рядом с ключом")
    void exceptionKeepsCodeAndCause() {
        SQLException sql = new SQLException("duplicate key value violates unique constraint");
        ConstraintViolationException e = new ConstraintViolationException(ConstraintErrorCode.FND_UNITS_UK_CODE, sql);

        assertThat(e).isInstanceOf(ApiException.class);
        assertThat(e.code()).isEqualTo(ConstraintErrorCode.FND_UNITS_UK_CODE);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CODE_ALREADY_EXISTS);
        assertThat(e.getMessageKey()).isEqualTo("error.fnd.fnd_units_uk_code");
        assertThat(e.getCause()).isSameAs(sql);
        assertThat(new StaleVersionException().code()).isEqualTo(ConstraintErrorCode.STALE_VERSION);
    }

    @Test
    @DisplayName("3.1: устаревшая версия — 409 conflict с текстом по ключу, а не 500")
    void staleVersionIs409() throws Exception {
        mvc.perform(get("/fnd/stale"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("conflict"))
                .andExpect(jsonPath("$.messageKey").value("error.fnd.stale_version"))
                .andExpect(jsonPath("$.detail")
                        .value("Запись уже изменена другим пользователем. Обновите данные и повторите"));
    }

    @Test
    @DisplayName("3.1: нарушение ограничения — свой код без SQL-текста в ответе")
    void constraintViolationHidesSql() throws Exception {
        mvc.perform(get("/fnd/constraint"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.messageKey").value("error.fnd.fnd_loads_ck_period"))
                .andExpect(jsonPath("$.detail").value("Начало периода загрузки позже его конца"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL"))));
    }

    @Test
    @DisplayName("3.1: pg-dwh недоступна — 503 service_unavailable, а не 500")
    void dwhUnavailableIs503() throws Exception {
        mvc.perform(get("/fnd/dwh"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("service_unavailable"))
                .andExpect(jsonPath("$.messageKey").value("error.fnd.dwh_unavailable"));
    }

    @Test
    @DisplayName("3.1: нет коэффициента — 409 с единицами и датой в параметрах текста")
    void coefficientMissingIs409WithParams() throws Exception {
        mvc.perform(get("/fnd/coefficient"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("error.fnd.coefficient_missing"))
                .andExpect(jsonPath("$.params.from").value("kg"))
                .andExpect(jsonPath("$.params.to").value("t"))
                .andExpect(jsonPath("$.params.date").value("2026-01-01"))
                .andExpect(jsonPath("$.detail").value("Нет коэффициента пересчёта kg → t на 2026-01-01"));
    }

    private static Set<String> placeholders(String text) {
        Set<String> names = new TreeSet<>();
        if (text != null) {
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    private static Map<String, String> catalog(String language) {
        return new ObjectMapper().readValue(CATALOGS.resolve(language + ".json").toFile(), new TypeReference<>() {});
    }

    @RestController
    static class FndThrowingController {

        @GetMapping("/fnd/stale")
        String stale() {
            throw new StaleVersionException();
        }

        @GetMapping("/fnd/constraint")
        String constraint() {
            throw new ConstraintViolationException(
                    ConstraintErrorCode.FND_LOADS_CK_PERIOD, new SQLException("SQL: check constraint violated"));
        }

        @GetMapping("/fnd/dwh")
        String dwh() {
            throw new DwhUnavailableException(new SQLException("Connection refused"));
        }

        @GetMapping("/fnd/coefficient")
        String coefficient() {
            throw new FndCoefficientMissingException("kg", "t", LocalDate.of(2026, 1, 1));
        }
    }
}
