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
}
