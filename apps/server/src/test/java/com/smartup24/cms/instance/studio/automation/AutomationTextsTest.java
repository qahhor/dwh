package com.smartup24.cms.instance.studio.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.runtime.EntityRecordView;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Texts;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** ADR-0046: the texts of an automation's messages, the record's values as a person reads them. */
class AutomationTextsTest {
    private static final String DECLARATION = """
            {"formatVersion": 6, "kind": "simple-form",
             "module": {"code": "studio", "title": {"ru": "Формы", "uz": "Shakllar", "en": "Forms"}},
             "entity": {"name": "orders", "title": {"ru": "Заказы", "uz": "Buyurtmalar", "en": "Orders"}},
             "fields": [
               {"id": "kind", "type": "select", "labels": {"ru": "Вид", "uz": "Turi", "en": "Kind"},
                "required": false, "listed": true,
                "options": [{"code": "big", "labels": {"ru": "Крупный", "uz": "Katta", "en": "Big"}}]},
               {"id": "amount", "type": "number", "labels": {"ru": "Сумма", "uz": "Summa", "en": "Amount"},
                "required": true, "listed": true},
               {"id": "secret", "type": "text", "labels": {"ru": "Скрыто", "uz": "Yashirin", "en": "Hidden"},
                "required": false, "listed": false, "maxLength": 10, "hidden": true}
             ],
             "workflow": {
               "states": [
                 {"code": "draft", "labels": {"ru": "Черновик", "uz": "Qoralama", "en": "Draft"}, "color": "gray"},
                 {"code": "done", "labels": {"ru": "Готово", "uz": "Tayyor", "en": "Done"}, "color": "green"}
               ],
               "transitions": [{"code": "finish", "labels": {"ru": "Закрыть", "uz": "Yopish", "en": "Close"},
                 "from": ["draft"], "to": "done", "comment": "required"}]
             }}
            """;

    private final MdI18nService i18n = mock(MdI18nService.class);
    private final AutomationTexts texts = new AutomationTexts(i18n);

    private AutomationRun run() {
        Declaration declaration = JsonMapper.shared().readValue(DECLARATION, Declaration.class);
        AutomationForm form = new AutomationForm("studio.orders", declaration, false);
        AutomationRun run = new AutomationRun(
                1,
                "Проба",
                2,
                "dry",
                form,
                5,
                1,
                true,
                ZoneId.of("Asia/Tashkent"),
                Instant.parse("2026-10-19T05:00:00Z"),
                Map.of("amount", "10"),
                Set.of("amount"));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", 5);
        record.put("revision", 2);
        record.put("kind", "big");
        record.put("docStatus", "done");
        record.put("amount", new BigDecimal("7.50"));
        record.put("count", 3);
        record.put("flag", true);
        record.put("day", LocalDate.of(2026, 10, 19));
        record.put("dayText", "2026-10-20");
        record.put("moment", Instant.parse("2026-10-19T05:00:00Z"));
        record.put("momentText", "2026-10-19T06:30:00Z");
        record.put("numberText", "12.500");
        record.put("client", 77);
        run.read(new EntityRecordView(record, List.of(), Map.of("client", "ООО Ромашка")));
        return run;
    }

    @Test
    @DisplayName("ADR-0046: placeholders take labels, numbers without zeros, local dates and moments, words for flags")
    void placeholdersReadAsAPersonReadsThem() {
        when(i18n.effectiveDictionary(anyString())).thenReturn(Map.of("common.yes", "Да", "common.no", "Нет"));
        AutomationRun run = run();
        String text = texts.render(
                new Texts(
                        "{form}: {kind} {docStatus} {amount} {count} {flag} {day} {dayText} {moment} {momentText}"
                                + " {numberText} {client} [{missing}]",
                        null,
                        "{form}"),
                "ru",
                run);
        assertThat(text)
                .isEqualTo("Заказы: Крупный Готово 7.5 3 Да 19.10.2026 20.10.2026 19.10.2026 10:00 19.10.2026 11:30"
                        + " 12.5 ООО Ромашка []");
        assertThat(texts.render(new Texts("{form}", " ", "{form}"), "uz", run)).isEqualTo("Buyurtmalar");
        assertThat(texts.render(new Texts("{form}", null, "{form} {kind}"), "en", run))
                .isEqualTo("Orders Big");
    }

    @Test
    @DisplayName("ADR-0046: a formula reads old and changed values; the form gives its writable fields and types")
    void theRunReadsOldAndChangedValues() {
        AutomationRun run = run();
        assertThat(run.values().apply("old.amount")).isEqualTo("10");
        assertThat(run.values().apply("changed.amount")).isEqualTo(true);
        assertThat(run.values().apply("changed.kind")).isEqualTo(false);
        assertThat(run.values().apply("amount")).isEqualTo(new BigDecimal("7.50"));
        assertThat(run.revision()).isEqualTo(2);
        AutomationForm form = run.form();
        assertThat(form.entered("amount")).isNotNull();
        assertThat(form.entered("secret")).isNull();
        assertThat(form.types(true)).containsKeys("old.amount", "changed.docStatus", "modifiedAt", "recordNumber");
        assertThat(form.types(false)).doesNotContainKey("old.amount");
        assertThat(form.userFields()).isEmpty();
        assertThat(form.transitions()).containsKey("finish");
        assertThat(form.choice("kind", "small", "ru")).isNull();
        assertThat(form.choice("docStatus", "draft", "en")).isEqualTo("Draft");
        assertThat(AutomationForm.needsValue(form.shown().get("amount"))).isTrue();
        assertThat(AutomationTexts.markdown("a_b*c")).isEqualTo("a\\_b\\*c");
        assertThat(AutomationTexts.markdown(null)).isEmpty();
    }

    @Test
    @DisplayName("ADR-0046: a value goes to the runtime as a key, a flag or text; retries wait 30 s, then 2 min")
    void valuesAndRetries() {
        assertThat(AutomationActions.body("user", new BigDecimal("12"))).isEqualTo(12L);
        assertThat(AutomationActions.body("user", new BigDecimal("1.5"))).isEqualTo("1.5");
        assertThat(AutomationActions.body("ref", 7L)).isEqualTo(7L);
        assertThat(AutomationActions.body("bool", true)).isEqualTo(true);
        assertThat(AutomationActions.body("date", LocalDate.of(2026, 1, 2))).isEqualTo("2026-01-02");
        assertThat(AutomationActions.body("text", null)).isNull();
        assertThat(AutomationRunner.backoff(1)).hasSeconds(30);
        assertThat(AutomationRunner.backoff(2)).hasMinutes(2);
        assertThat(AutomationExecutor.retryable(new IllegalStateException("db")))
                .isTrue();
        assertThat(AutomationEvents.plain(java.sql.Timestamp.from(Instant.parse("2026-10-19T05:00:00Z"))))
                .isEqualTo("2026-10-19T05:00:00Z");
        assertThat(AutomationEvents.plain(new BigDecimal("1.50"))).isEqualTo("1.50");
        assertThat(AutomationNotices.key("run-1", 0, 2, "email"))
                .isEqualTo(AutomationNotices.key("run-1", 0, 2, "email"));
    }
}
