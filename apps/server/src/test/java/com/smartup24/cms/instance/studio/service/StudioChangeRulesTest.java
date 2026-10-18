package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Entity;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Menu;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Module;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Option;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Target;
import com.smartup24.cms.instance.md.api.MdSimpleFormFileDtos.Files;
import com.smartup24.cms.instance.studio.service.StudioChangeRules.Change;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The compatible and incompatible changes of a live form, one row per rule (ADR-0041, decision 2). */
class StudioChangeRulesTest {
    private static final Labels LABELS = new Labels("Поле", "Maydon", "Field");

    private static final Field TEXT = new Field("title", "text", LABELS, false, true, 100, null);
    private static final Field NUMBER = new Field("amount", "number", LABELS, false, true, null, 2);
    private static final Field DEFAULTED = new Field("kind", "text", LABELS, true, true, 20, null, null, "general");

    static Stream<Arguments> compatible() {
        return Stream.of(
                row("labels", fields -> set(fields, 0, with(TEXT, new Labels("Название", "Nom", "Title"), null))),
                row("order", fields -> List.of(fields.get(1), fields.get(0), fields.get(2))),
                row(
                        "optional",
                        fields -> set(
                                fields, 2, new Field("kind", "text", LABELS, false, true, 20, null, null, "general"))),
                row(
                        "longer text",
                        fields -> set(fields, 0, new Field("title", "text", LABELS, false, true, 4000, null))),
                row(
                        "more decimals",
                        fields -> set(fields, 1, new Field("amount", "number", LABELS, false, true, null, 6))),
                row(
                        "hidden",
                        fields ->
                                set(fields, 0, new Field("title", "text", LABELS, false, true, 100, null, true, null))),
                row(
                        "added optional",
                        fields -> add(fields, new Field("region", "text", LABELS, false, true, 50, null))),
                row(
                        "added required with default",
                        fields -> add(fields, new Field("priority", "number", LABELS, true, true, null, 0, null, "3"))),
                row(
                        "listed changes",
                        fields -> set(fields, 0, new Field("title", "text", LABELS, false, false, 100, null))));
    }

    static Stream<Arguments> incompatible() {
        return Stream.of(
                row(
                        "type",
                        fields -> set(fields, 1, new Field("amount", "text", LABELS, false, true, 20, null)),
                        new Change("amount", "type")),
                row("removed", fields -> List.of(fields.get(0), fields.get(2)), new Change("amount", "removed")),
                row(
                        "renamed",
                        fields -> set(fields, 0, new Field("name", "text", LABELS, false, true, 100, null)),
                        new Change("title", "removed")),
                row(
                        "required without default",
                        fields -> set(fields, 0, new Field("title", "text", LABELS, true, true, 100, null)),
                        new Change("title", "required")),
                row(
                        "added required without default",
                        fields -> add(fields, new Field("region", "text", LABELS, true, true, 50, null)),
                        new Change("region", "required")),
                row(
                        "shorter text",
                        fields -> set(fields, 0, new Field("title", "text", LABELS, false, true, 99, null)),
                        new Change("title", "max_length")),
                row(
                        "fewer decimals",
                        fields -> set(fields, 1, new Field("amount", "number", LABELS, false, true, null, 1)),
                        new Change("amount", "scale")),
                row(
                        "default changed",
                        fields ->
                                set(fields, 2, new Field("kind", "text", LABELS, true, true, 20, null, null, "other")),
                        new Change("kind", "default")),
                row(
                        "default added",
                        fields -> set(fields, 0, new Field("title", "text", LABELS, false, true, 100, null, null, "x")),
                        new Change("title", "default")),
                row(
                        "default dropped",
                        fields -> set(fields, 2, new Field("kind", "text", LABELS, false, true, 20, null)),
                        new Change("kind", "default")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("compatible")
    void compatibleChangesPass(String name, UnaryOperator<List<Field>> change, Change[] none) {
        assertThat(StudioChangeRules.incompatible(form(base()), form(change.apply(base()))))
                .as(name)
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompatible")
    void incompatibleChangesAreNamedByFieldAndChange(String name, UnaryOperator<List<Field>> change, Change[] want) {
        assertThat(StudioChangeRules.incompatible(form(base()), form(change.apply(base()))))
                .as(name)
                .containsExactly(want);
    }

    @Test
    void theTitleAndTheMenuChangeFreelyAndEveryRefusedChangeIsListed() {
        Declaration before = form(base());
        Declaration after = new Declaration(
                2,
                "simple-form",
                before.module(),
                new Entity("visits", new Labels("Визиты", "Tashriflar", "Visits"), new Menu("event", "workspace", 5)),
                List.of(new Field("title", "number", LABELS, false, true, null, 0), DEFAULTED));
        List<Change> changes = StudioChangeRules.incompatible(before, after);
        assertThat(changes).containsExactly(new Change("title", "type"), new Change("amount", "removed"));
        assertThatThrownBy(() -> {
                    throw StudioChangeRules.refusal(changes);
                })
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.getMessageKey()).isEqualTo(StudioChangeRules.INCOMPATIBLE);
                    assertThat(refused.getParams())
                            .containsEntry("field", "title")
                            .containsEntry("change", "type");
                    assertThat(refused.getFieldErrors())
                            .extracting(error -> error.field() + ":" + error.code())
                            .containsExactly("fields.title:change_type", "fields.amount:change_removed");
                });
        assertThat(StudioChangeRules.incompatible(
                        before,
                        new Declaration(1, "simple-form", before.module(), new Entity("visits", LABELS), base())))
                .isEmpty();
    }

    /**
     * Format 3 (ADR-0041): a select gains choices and renames them, but never drops a code records may hold; a
     * reference keeps its target; the data scope stays the one chosen at creation.
     */
    @Test
    void choicesMayGrowTargetsAndTheScopeStay() {
        Field select = select("result", option("done", "Выполнен"), option("later", "Позже"));
        Field list = reference("kind", "list", "kinds");
        Field client = reference("client", "form", "studio.clients");
        Field user = new Field("owner", "user", LABELS, false, true, null, null, null, null, null, null);
        Declaration before = scoped("org_unit", List.of(select, list, client, user));

        Declaration grown = scoped(
                "org_unit",
                List.of(
                        select("result", option("done", "Сделан"), option("later", "Позже"), option("no", "Нет")),
                        list,
                        client,
                        user));
        assertThat(StudioChangeRules.incompatible(before, grown)).isEmpty();

        Declaration changed = scoped(
                "owner",
                List.of(
                        select("result", option("done", "Выполнен")),
                        reference("kind", "list", "types"),
                        reference("client", "form", "studio.partners"),
                        user));
        List<Change> changes = StudioChangeRules.incompatible(before, changed);
        assertThat(changes)
                .containsExactly(
                        new Change("entity.scope", "scope"),
                        new Change("result", "option_removed"),
                        new Change("kind", "target"),
                        new Change("client", "target"));
        assertThatThrownBy(() -> {
                    throw StudioChangeRules.refusal(changes);
                })
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.getMessageKey()).isEqualTo(StudioChangeRules.SCOPE);
                    assertThat(refused.getFieldErrors())
                            .extracting(error -> error.field() + ":" + error.code())
                            .containsExactly(
                                    "entity.scope:change_scope",
                                    "fields.result:change_option_removed",
                                    "fields.kind:change_target",
                                    "fields.client:change_target");
                });
        assertThat(StudioChangeRules.incompatible(scoped(null, List.of(user)), scoped("all", List.of(user))))
                .isEmpty();
        assertThat(StudioChangeRules.incompatible(
                        scoped("all", List.of(select)), scoped("all", List.of(reference("result", "list", "kinds")))))
                .containsExactly(new Change("result", "type"));
    }

    private static Declaration scoped(String scope, List<Field> fields) {
        return new Declaration(
                3,
                "simple-form",
                new Module("studio", LABELS),
                new Entity("visits", new Labels("Визиты", "Tashriflar", "Visits"), null, scope),
                fields);
    }

    private static Field select(String id, Option... options) {
        return new Field(id, "select", LABELS, false, true, null, null, null, null, List.of(options), null);
    }

    private static Option option(String code, String ru) {
        return new Option(code, new Labels(ru, code, code));
    }

    private static Field reference(String id, String kind, String code) {
        return new Field(id, "ref", LABELS, false, true, null, null, null, null, null, new Target(kind, code));
    }

    @Test
    void aDefaultTheFieldWouldRefuseIsRefusedBeforeItIsStored() {
        var compiler = new StudioEntityCompiler();
        Declaration bad = form(List.of(
                new Field("title", "text", LABELS, false, true, 3, null, null, "too long"),
                new Field("amount", "number", LABELS, false, true, null, 0, null, "1.25"),
                new Field("due", "date", LABELS, false, true, null, null, null, "31.01.2026"),
                new Field("hiddenbad", "date", LABELS, false, true, null, null, true, "never checked")));
        assertThatThrownBy(() -> StudioChangeRules.requireDefaults(
                        bad, compiler.compile(1, bad).entity()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getFieldErrors())
                                .extracting(error -> error.field())
                                .containsExactly(
                                        "fields[0].defaultValue", "fields[1].defaultValue", "fields[2].defaultValue"));
        Declaration good = form(List.of(
                new Field("title", "text", LABELS, true, true, 10, null, null, "ok"),
                new Field("flag", "bool", LABELS, true, true, null, null, null, "false")));
        StudioChangeRules.requireDefaults(good, compiler.compile(1, good).entity());
    }

    private static List<Field> base() {
        return List.of(TEXT, NUMBER, DEFAULTED);
    }

    @Test
    void formulasConditionsAndRulesChangeFreelyButAnEnteredFieldNeverBecomesComputed() {
        Declaration before = form(List.of(TEXT, logic(NUMBER, "1 + 1", null)));
        assertThat(StudioChangeRules.incompatible(before, form(List.of(TEXT, logic(NUMBER, "2 * 3", null)))))
                .as("a computed field's formula changes")
                .isEmpty();
        assertThat(StudioChangeRules.incompatible(before, form(List.of(logic(TEXT, null, "amount > 1"), NUMBER))))
                .as("a computed field becomes entered and a condition is added")
                .isEmpty();
        assertThat(StudioChangeRules.incompatible(
                        form(List.of(TEXT, NUMBER)), form(List.of(TEXT, logic(NUMBER, "1 + 1", null)))))
                .containsExactly(new Change("amount", "computed"));
        assertThat(StudioChangeRules.incompatible(
                        form(List.of(TEXT)), form(List.of(TEXT, logic(NUMBER, "1 + 1", null)))))
                .as("a new computed field starts empty in the saved records")
                .isEmpty();
    }

    private static Field logic(Field field, String formula, String visibleIf) {
        return new Field(
                field.id(),
                field.type(),
                field.labels(),
                field.required(),
                field.listed(),
                field.maxLength(),
                field.scale(),
                field.hidden(),
                field.defaultValue(),
                field.options(),
                field.target(),
                formula,
                null,
                visibleIf,
                null,
                null,
                null);
    }

    private static Declaration form(List<Field> fields) {
        return new Declaration(
                2,
                "simple-form",
                new Module("studio", LABELS),
                new Entity("visits", new Labels("Визиты", "Tashriflar", "Visits")),
                fields);
    }

    private static Arguments row(String name, UnaryOperator<List<Field>> change, Change... changes) {
        return Arguments.of(name, change, changes);
    }

    private static List<Field> set(List<Field> fields, int index, Field field) {
        List<Field> copy = new ArrayList<>(fields);
        copy.set(index, field);
        return copy;
    }

    private static List<Field> add(List<Field> fields, Field field) {
        List<Field> copy = new ArrayList<>(fields);
        copy.add(field);
        return copy;
    }

    private static Field with(Field field, Labels labels, String fallback) {
        return new Field(
                field.id(),
                field.type(),
                labels,
                field.required(),
                field.listed(),
                field.maxLength(),
                field.scale(),
                field.hidden(),
                fallback);
    }

    @Test
    void aFileFieldIsAddedFreelyButKeepsItsKindOfValueAndItsLimit() {
        Field single = file("act", null);
        Field several = file("docs", new Files(true, 5, null, null));
        List<Field> before = List.of(TEXT, single, several);

        List<Field> loosened = List.of(
                TEXT,
                file("act", new Files(false, null, List.of("application/pdf"), 10)),
                file("docs", new Files(true, 8, List.of("image/png"), 1)),
                file("photo", new Files(true, 3, null, null)));
        assertThat(StudioChangeRules.incompatible(form(before), form(loosened))).isEmpty();

        List<Field> changed = List.of(
                new Field("title", "file", LABELS, false, true, null, null),
                file("act", new Files(true, 2, null, null)),
                file("docs", new Files(true, 4, null, null)));
        assertThat(StudioChangeRules.incompatible(form(before), form(changed)))
                .containsExactly(
                        new Change("title", "type"),
                        new Change("act", "files_multiple"),
                        new Change("docs", "max_files"));
        assertThat(StudioChangeRules.incompatible(
                        form(before), form(List.of(TEXT, single, file("docs", new Files(false, null, null, null))))))
                .containsExactly(new Change("docs", "files_multiple"));
    }

    private static Field file(String id, @org.jspecify.annotations.Nullable Files files) {
        return new Field(
                id, "file", LABELS, false, true, null, null, null, null, null, null, null, null, null, null, null,
                files);
    }
}
