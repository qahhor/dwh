package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.common.entity.EntityFormulaReads;
import com.smartup24.cms.instance.common.entity.EntityLogic;
import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.formula.FormulaFailure;
import com.smartup24.cms.instance.common.formula.FormulaScope;
import com.smartup24.cms.instance.common.formula.FormulaType;
import com.smartup24.cms.instance.common.web.RequestLanguage;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Lines;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormRuleDtos.Rule;
import com.smartup24.cms.instance.md.service.MdSimpleFormDocumentLogic;
import com.smartup24.cms.instance.md.service.MdSimpleFormLogic;
import com.smartup24.cms.platform.api.entity.hook.EntityRule;
import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import com.smartup24.cms.platform.api.entity.hook.RuleErrors;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The form logic of a live form of format 5 (ADR-0042), compiled from its stored formulas: the computed fields in the
 * order they are evaluated — each after the computed fields it reads, declaration order otherwise — the defaults, the
 * conditions and the rules. The activation checked the same formulas, so a stored form compiles; one that no longer
 * does is left out of the catalog like any broken form. The rules run as cross-field rules of the entity (ADR-0032,
 * 6.6) on every write path of the general runtime.
 */
final class StudioFormLogic {
    private static final Clock CLOCK = Clock.systemUTC();

    private StudioFormLogic() {}

    /**
     * The logic of the form; none for formats 1 to 4. The formulas of a document of format 6 also read its number, its
     * status and its rows, and its computed columns are evaluated per row (ADR-0043).
     */
    static EntityLogic of(Declaration source, String prefix) {
        if (source.formatVersion() < 5) return EntityLogic.NONE;
        Map<String, FormulaType> types = source.formatVersion() < 6
                ? MdSimpleFormLogic.types(source.fields())
                : MdSimpleFormDocumentLogic.header(
                        source.fields(),
                        source.lines(),
                        source.workflow(),
                        source.entity().numbering());
        Map<String, Formula> computed = new LinkedHashMap<>();
        Map<String, Formula> defaults = new LinkedHashMap<>();
        Map<String, Formula> visible = new LinkedHashMap<>();
        Map<String, Formula> required = new LinkedHashMap<>();
        Map<String, Formula> readonly = new LinkedHashMap<>();
        for (Field field : StudioEntityCompiler.shown(source)) {
            put(computed, field.id(), field.formula(), types);
            put(defaults, field.id(), field.defaultFormula(), types);
            put(visible, field.id(), field.visibleIf(), types);
            put(required, field.id(), field.requiredIf(), types);
            put(readonly, field.id(), field.readonlyIf(), types);
        }
        List<EntityLogic.Rule> rules = new ArrayList<>();
        if (source.rules() != null) {
            for (Rule rule : source.rules()) {
                rules.add(new EntityLogic.Rule(
                        rule.key(),
                        rule.field(),
                        Formula.compile(rule.formula(), types),
                        messageKey(prefix, rule.key()),
                        Map.of(
                                "ru",
                                rule.message().ru(),
                                "uz",
                                rule.message().uz(),
                                "en",
                                rule.message().en())));
            }
        }
        return new EntityLogic(ordered(computed), defaults, visible, required, readonly, rules, lines(source));
    }

    /** The computed columns of each table of lines, by its key, each after the columns it reads. */
    private static Map<String, List<EntityLogic.Computed>> lines(Declaration source) {
        Map<String, List<EntityLogic.Computed>> lines = new LinkedHashMap<>();
        if (source.lines() == null) return lines;
        for (Lines table : source.lines()) {
            Map<String, FormulaType> types = MdSimpleFormDocumentLogic.line(table, source.fields());
            Map<String, Formula> computed = new LinkedHashMap<>();
            table.fields().forEach(column -> put(computed, column.id(), column.formula(), types));
            if (!computed.isEmpty()) lines.put(table.key(), ordered(computed));
        }
        return lines;
    }

    /** The dictionary key of a rule's message. */
    static String messageKey(String prefix, String rule) {
        return prefix + ".rule." + rule;
    }

    private static void put(
            Map<String, Formula> into, String key, @Nullable String source, Map<String, FormulaType> types) {
        if (source != null) into.put(key, Formula.compile(source, types));
    }

    /** The computed fields, each after the computed fields it reads; the activation refused cycles. */
    private static List<EntityLogic.Computed> ordered(Map<String, Formula> computed) {
        List<EntityLogic.Computed> result = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        while (placed.size() < computed.size()) {
            boolean progress = false;
            for (Map.Entry<String, Formula> entry : computed.entrySet()) {
                if (placed.contains(entry.getKey())) continue;
                boolean ready = entry.getValue().fields().keySet().stream()
                        .allMatch(read -> !computed.containsKey(read) || placed.contains(read));
                if (!ready) continue;
                result.add(new EntityLogic.Computed(entry.getKey(), entry.getValue()));
                placed.add(entry.getKey());
                progress = true;
            }
            if (!progress) throw new IllegalStateException("Computed fields of a live form read each other");
        }
        return List.copyOf(result);
    }

    /**
     * A rule of the form as a cross-field rule of the entity: it fails when its formula is false over the record as the
     * save will write it — an empty value passes — with its message in the language of the request; a formula that
     * cannot give a value is a problem of the rule's field.
     */
    record Check(EntityLogic.Rule rule) implements EntityRule {
        @Override
        public void check(EntityValues values, @Nullable EntityValues before, RuleErrors errors) {
            String at = rule.field() == null ? RuleErrors.RECORD : rule.field();
            try {
                if (!rule.formula().fails(EntityFormulaReads.values(values), FormulaScope.current(CLOCK))) return;
                String message = rule.messages().get(RequestLanguage.current());
                errors.field(
                        at,
                        "rule_failed",
                        "error.studio.rule_failed",
                        Map.of("rule", rule.key(), "message", message == null ? rule.key() : message));
            } catch (FormulaFailure failure) {
                errors.field(at, "formula_" + failure.reason(), failure.messageKey(), Map.of("rule", rule.key()));
            }
        }
    }
}
