package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.common.formula.FormulaValues;
import com.smartup24.cms.instance.md.service.MdI18nService;
import com.smartup24.cms.instance.md.service.MdUserTexts;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Texts;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The texts of an automation's messages and comments (ADR-0046): the text of the reader's language — Russian when it
 * has none — with each {@code {field}} replaced by the record's value as a person reads it: a choice or a status by its
 * label, a reference by the name of its row, a number without trailing zeros, a date as {@code dd.MM.yyyy}, a moment in
 * the automation's time zone; {@code {form}} is the form's name.
 */
@Component
public class AutomationTexts {
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

    private final MdI18nService i18n;

    public AutomationTexts(MdI18nService i18n) {
        this.i18n = i18n;
    }

    /** The text in {@code lang} with the values of the run's record. */
    String render(Texts texts, String lang, AutomationRun run) {
        String template = switch (lang) {
            case "uz" -> texts.uz();
            case "en" -> texts.en();
            default -> texts.ru();
        };
        if (template == null || template.isBlank()) template = texts.ru();
        return fill(template, lang, run);
    }

    /** A template of one language with the values of the run's record. */
    String fill(String template, String lang, AutomationRun run) {
        Map<String, String> params = new HashMap<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            params.computeIfAbsent(name, key -> value(key, lang, run));
        }
        return MdUserTexts.fill(template, params);
    }

    private String value(String name, String lang, AutomationRun run) {
        if ("form".equals(name)) return run.form().title(lang);
        Object value = run.record().get(name);
        if (value == null) return "";
        if (run.labels().get(name) instanceof String label && !label.isBlank()) return label;
        String choice = run.form().choice(name, String.valueOf(value), lang);
        if (choice != null) return choice;
        return switch (value) {
            case Boolean flag -> yesNo(flag, lang);
            case BigDecimal number -> FormulaValues.text(number);
            case Number number -> number(number.toString());
            case LocalDate date -> DATE.format(date);
            case Instant moment -> MOMENT.format(moment.atZone(run.scope().zone()));
            case OffsetDateTime offset ->
                MOMENT.format(offset.atZoneSameInstant(run.scope().zone()));
            case java.sql.Timestamp timestamp ->
                MOMENT.format(timestamp.toInstant().atZone(run.scope().zone()));
            default -> text(String.valueOf(value), run);
        };
    }

    /** A stored text: a date or a moment as a person reads it, a number without trailing zeros, others as they are. */
    private static String text(String raw, AutomationRun run) {
        try {
            if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) return DATE.format(LocalDate.parse(raw));
            if (raw.matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
                return MOMENT.format(
                        OffsetDateTime.parse(raw).atZoneSameInstant(run.scope().zone()));
            }
        } catch (DateTimeParseException notADate) {
            return raw;
        }
        return raw.matches("-?\\d+(\\.\\d+)?") ? number(raw) : raw;
    }

    private static String number(String raw) {
        return FormulaValues.text(new BigDecimal(raw).stripTrailingZeros());
    }

    private String yesNo(boolean flag, String lang) {
        String key = flag ? "common.yes" : "common.no";
        return i18n.effectiveDictionary(lang).getOrDefault(key, Boolean.toString(flag));
    }

    /** A text inside Telegram's Markdown: its marks are escaped, so a text with them is still delivered. */
    static String markdown(@Nullable String text) {
        return text == null ? "" : text.replaceAll("([_*\\[`])", "\\\\$1");
    }
}
