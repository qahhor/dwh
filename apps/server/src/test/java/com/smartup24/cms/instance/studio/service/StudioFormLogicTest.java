package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.EntityLogic;
import com.smartup24.cms.instance.common.web.RequestLanguage;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Entity;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Module;
import com.smartup24.cms.instance.md.api.MdSimpleFormRuleDtos.Rule;
import com.smartup24.cms.instance.md.api.MdSimpleFormRuleDtos.RuleMessage;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.hook.EntityValues;
import com.smartup24.cms.platform.api.entity.hook.RuleErrors;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The logic of a live form of format 5 compiled for the runtime (ADR-0042): the computed fields in their evaluation
 * order, the conditions and defaults by field, the rules as cross-field rules with their messages in the language of
 * the request, and a formula of a rule that cannot give a value as a problem of the rule's field.
 */
class StudioFormLogicTest {
    private static final Labels LABELS = new Labels("Поле", "Maydon", "Field");

    @Test
    void earlierFormatsHaveNoLogic() {
        assertThat(StudioFormLogic.of(form(4, List.of(number("qty", null)), null), "p"))
                .isEqualTo(EntityLogic.NONE);
    }

    @Test
    void theComputedFieldsComeAfterWhatTheyReadAndTheConditionsByField() {
        Field net = number("net", "total - 1");
        Field total = number("total", "qty * 2");
        Field reason = new Field(
                "reason",
                "text",
                LABELS,
                false,
                true,
                100,
                null,
                null,
                null,
                null,
                null,
                null,
                "\"none\"",
                "qty > 1",
                "qty > 2",
                "qty > 3",
                null);
        EntityLogic logic =
                StudioFormLogic.of(form(5, List.of(net, total, number("qty", null), reason), null), "studio.live.x");
        assertThat(logic.computed()).extracting(EntityLogic.Computed::field).containsExactly("total", "net");
        assertThat(logic.defaults()).containsOnlyKeys("reason");
        assertThat(logic.visible()).containsOnlyKeys("reason");
        assertThat(logic.required()).containsOnlyKeys("reason");
        assertThat(logic.readonly()).containsOnlyKeys("reason");
        assertThat(logic.rules()).isEmpty();
        assertThat(logic.computedFormula("qty")).isNull();
    }

    @Test
    void computedFieldsThatReadEachOtherDoNotCompile() {
        Declaration cyclic = form(5, List.of(number("aa", "bb + 1"), number("bb", "aa + 1")), null);
        assertThatThrownBy(() -> StudioFormLogic.of(cyclic, "p")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRuleFailsOnlyWhenFalseWithItsMessageInTheLanguageOfTheRequest() {
        Rule max =
                new Rule("max", "qty <= 30", new RuleMessage("Не больше 30", "30 dan oshmaydi", "At most 30"), "qty");
        Rule whole = new Rule("whole", "10 / qty > 0", new RuleMessage("Целое", "Butun", "Whole"), null);
        Declaration declaration = form(5, List.of(number("qty", null)), List.of(max, whole));
        EntityLogic logic = StudioFormLogic.of(declaration, "studio.live.x");
        assertThat(logic.rules())
                .extracting(EntityLogic.Rule::messageKey)
                .containsExactly("studio.live.x.rule.max", "studio.live.x.rule.whole");
        EntityDefinition entity =
                new StudioEntityCompiler().compile(1, declaration).entity();
        assertThat(entity.model().rules()).containsOnlyKeys("rule.max", "rule.whole");

        RuleErrors failed = check(logic.rules().getFirst(), entity, Map.of("qty", "31"), "uz");
        assertThat(failed.items()).singleElement().satisfies(problem -> {
            assertThat(problem.field()).isEqualTo("qty");
            assertThat(problem.code()).isEqualTo("rule_failed");
            assertThat(problem.params())
                    .containsEntry("message", "30 dan oshmaydi")
                    .containsEntry("rule", "max");
        });
        assertThat(check(logic.rules().getFirst(), entity, Map.of(), "ru").isEmpty())
                .isTrue();
        assertThat(check(logic.rules().getFirst(), entity, Map.of("qty", "3"), "ru")
                        .isEmpty())
                .isTrue();
        RuleErrors zero = check(logic.rules().get(1), entity, Map.of("qty", "0"), "en");
        assertThat(zero.items()).singleElement().satisfies(problem -> {
            assertThat(problem.field()).isEqualTo(RuleErrors.RECORD);
            assertThat(problem.code()).isEqualTo("formula_division_by_zero");
            assertThat(problem.messageKey()).isEqualTo("error.formula.division_by_zero");
        });
        EntityLogic.Rule unnamed =
                new EntityLogic.Rule("bare", null, logic.rules().getFirst().formula(), "k", Map.of());
        assertThat(check(unnamed, entity, Map.of("qty", "40"), "ru")
                        .items()
                        .getFirst()
                        .params())
                .containsEntry("message", "bare");
    }

    private static RuleErrors check(
            EntityLogic.Rule rule, EntityDefinition entity, Map<String, ?> values, String lang) {
        RuleErrors errors = new RuleErrors();
        RequestLanguage.run(
                lang, () -> new StudioFormLogic.Check(rule).check(EntityValues.readOnly(entity, values), null, errors));
        return errors;
    }

    private static Field number(String id, String formula) {
        return new Field(
                id, "number", LABELS, false, true, null, 0, null, null, null, null, formula, null, null, null, null,
                null);
    }

    private static Declaration form(int format, List<Field> fields, List<Rule> rules) {
        return new Declaration(
                format,
                "simple-form",
                new Module("studio", LABELS),
                new Entity("orders", new Labels("Заказы", "Buyurtmalar", "Orders")),
                fields,
                null,
                rules);
    }
}
