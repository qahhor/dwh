package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.service.StudioChangeRules;
import com.smartup24.cms.instance.studio.service.StudioEntityCompiler;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.field.FieldReadonly;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Declarations stored today keep compiling (ADR-0038, ADR-0041). Activated rows are immutable and never migrated, so every
 * sample here — one per field type and option, in the stored JSON format of simple-form v1 — must still read back
 * unchanged, keep its checksum (an exact repeat answers by it) and compile into the same runtime shape. A compiler, DTO
 * or validator change that breaks one of them needs a migration path or a new format version, not an edit of this file.
 */
class StudioStoredFormatCompatibilityTest {
    private static final Path SAMPLES = Path.of("src/test/resources/studio/golden/stored-declarations-v1.json");
    private static final Path SAMPLES_V2 = Path.of("src/test/resources/studio/golden/stored-declarations-v2.json");
    private static final Path SAMPLES_V3 = Path.of("src/test/resources/studio/golden/stored-declarations-v3.json");
    private static final Path SAMPLES_V4 = Path.of("src/test/resources/studio/golden/stored-declarations-v4.json");
    private static final Path SAMPLES_V5 = Path.of("src/test/resources/studio/golden/stored-declarations-v5.json");
    private static final Map<String, FieldType> TYPES = Map.of(
            "text", FieldType.TEXT,
            "textarea", FieldType.TEXTAREA,
            "number", FieldType.NUMBER,
            "date", FieldType.DATE,
            "datetime", FieldType.DATETIME,
            "time", FieldType.TIME,
            "bool", FieldType.BOOLEAN,
            "email", FieldType.EMAIL,
            "phone", FieldType.PHONE,
            "url", FieldType.URL);

    private final ObjectMapper json = JsonMapper.shared();
    private final StudioFormRepository repository = new StudioFormRepository(mock(JdbcClient.class), json);

    @Test
    void everyStoredSampleReadsBackUnchangedKeepsItsChecksumAndCompiles() throws Exception {
        JsonNode samples = json.readTree(Files.readString(SAMPLES));
        Set<FieldType> covered = EnumSet.noneOf(FieldType.class);
        Set<String> options = new HashSet<>();
        long id = 1;
        for (JsonNode sample : samples) {
            String stored = json.writeValueAsString(sample.path("declaration"));
            Declaration declaration = repository.declaration(stored);
            assertThat(json.valueToTree(declaration).equals(sample.path("declaration")))
                    .as("stored format of %s", declaration.entity().name())
                    .isTrue();
            var prepared = new MdSimpleFormDeclarations(json).prepare(stored.getBytes(StandardCharsets.UTF_8));
            assertThat(prepared.declaration()).isEqualTo(declaration);
            assertThat(prepared.checksum()).isEqualTo(sample.path("checksum").asString());

            var compiled = new StudioEntityCompiler().compile(id++, declaration);
            EntityDefinition entity = compiled.entity();
            String code = "studio." + declaration.entity().name();
            assertThat(entity.code()).isEqualTo(code);
            assertThat(entity.form()).isEqualTo(code);
            assertThat(entity.auditTable())
                    .isEqualTo("studio_" + declaration.entity().name());
            assertThat(compiled.messages().get("uz"))
                    .containsEntry(
                            "studio.live." + declaration.entity().name() + ".title",
                            declaration.entity().title().uz());
            for (Field field : declaration.fields()) {
                covered.add(check(entity, field));
                options.add(field.type() + (field.required() ? ":required" : ":optional"));
                options.add(field.type() + (field.listed() ? ":listed" : ":form"));
            }
        }
        assertThat(covered).as("every field type has a stored sample").containsAll(TYPES.values());
        for (String type : TYPES.keySet()) {
            assertThat(options).contains(type + ":required", type + ":optional", type + ":listed", type + ":form");
        }
    }

    /**
     * Format 2 (ADR-0041): hidden fields, fixed defaults and the menu item. A hidden field is served nowhere but keeps
     * its key; a default is the form default and the value read while the key is absent; the stored defaults stay
     * acceptable to the compiled field.
     */
    @Test
    void everyStoredFormatTwoSampleReadsBackKeepsItsChecksumAndCompilesItsHiddenFieldsDefaultsAndMenu()
            throws Exception {
        JsonNode samples = json.readTree(Files.readString(SAMPLES_V2));
        Set<FieldType> defaulted = EnumSet.noneOf(FieldType.class);
        boolean hidden = false;
        long id = 1000;
        for (JsonNode sample : samples) {
            String stored = json.writeValueAsString(sample.path("declaration"));
            Declaration declaration = repository.declaration(stored);
            assertThat(declaration.formatVersion()).isEqualTo(2);
            assertThat(json.valueToTree(declaration).equals(sample.path("declaration")))
                    .as("stored format of %s", declaration.entity().name())
                    .isTrue();
            var prepared = new MdSimpleFormDeclarations(json).prepare(stored.getBytes(StandardCharsets.UTF_8));
            assertThat(prepared.declaration()).isEqualTo(declaration);
            assertThat(prepared.checksum()).isEqualTo(sample.path("checksum").asString());

            EntityDefinition entity =
                    new StudioEntityCompiler().compile(id++, declaration).entity();
            StudioChangeRules.requireDefaults(declaration, entity);
            var menu = declaration.entity().menu() == null
                    ? StudioEntityCompiler.DEFAULT_MENU
                    : declaration.entity().menu();
            assertThat(entity.menu()).isNotNull();
            assertThat(entity.menu().icon()).isEqualTo(menu.icon());
            assertThat(entity.menu().section()).isEqualTo(menu.section());
            assertThat(entity.menu().order()).isEqualTo(menu.order());
            for (Field field : declaration.fields()) {
                if (Boolean.TRUE.equals(field.hidden())) {
                    hidden = true;
                    assertThat(entity.fieldsByKey()).doesNotContainKey(field.id());
                    continue;
                }
                FieldType type = check(entity, field);
                FormField form = entity.fieldsByKey().get(field.id());
                if (field.defaultValue() == null) {
                    assertThat(form.flags().defaultValue()).isNull();
                    continue;
                }
                defaulted.add(type);
                assertThat(form.flags().defaultValue()).isEqualTo(FieldDefault.fixed(field.defaultValue()));
                EntityField stored2 = entity.model().fields().stream()
                        .filter(candidate -> candidate.key().equals(field.id()))
                        .findFirst()
                        .orElseThrow();
                assertThat(stored2.sql("t")).startsWith("(case when t.payload->'" + field.id() + "' is null");
            }
        }
        assertThat(hidden).as("a hidden field has a stored sample").isTrue();
        assertThat(defaulted).as("every field type has a stored default").containsAll(TYPES.values());
        // Version 1 stays readable next to version 2: the samples of format 1 are checked by the test above.
    }

    /**
     * Format 3 (ADR-0041): a select keeps the code of a choice its own labels name, a reference to a list is an
     * enumeration of the list, a reference to a form and a user field keep the key of the row they name; the data
     * scope restricts the records, and a form scoped by org unit serves the record's unit.
     */
    @Test
    void everyStoredFormatThreeSampleReadsBackKeepsItsChecksumAndCompilesItsChoicesTargetsAndScope() throws Exception {
        JsonNode samples = json.readTree(Files.readString(SAMPLES_V3));
        Set<FieldType> kinds = EnumSet.noneOf(FieldType.class);
        Set<String> scopes = new HashSet<>();
        long id = 2000;
        for (JsonNode sample : samples) {
            String stored = json.writeValueAsString(sample.path("declaration"));
            Declaration declaration = repository.declaration(stored);
            assertThat(declaration.formatVersion()).isEqualTo(3);
            assertThat(json.valueToTree(declaration).equals(sample.path("declaration")))
                    .as("stored format of %s", declaration.entity().name())
                    .isTrue();
            var prepared = new MdSimpleFormDeclarations(json).prepare(stored.getBytes(StandardCharsets.UTF_8));
            assertThat(prepared.declaration()).isEqualTo(declaration);
            assertThat(prepared.checksum()).isEqualTo(sample.path("checksum").asString());

            var compiled = new StudioEntityCompiler().compile(id++, declaration);
            EntityDefinition entity = compiled.entity();
            StudioChangeRules.requireDefaults(declaration, entity);
            String scope = declaration.entity().scope();
            scopes.add(scope);
            assertThat(entity.model().scope().describe())
                    .isEqualTo(
                            switch (scope) {
                                case "org_unit" -> "orgUnit(org_unit_id, created_by)";
                                case "owner" -> "owner(created_by)";
                                default -> "all";
                            });
            assertThat(entity.fieldsByKey().containsKey(StudioEntityCompiler.ORG_UNIT))
                    .isEqualTo("org_unit".equals(scope));
            for (Field field : declaration.fields()) {
                if (Boolean.TRUE.equals(field.hidden())) {
                    assertThat(entity.fieldsByKey()).doesNotContainKey(field.id());
                    continue;
                }
                kinds.add(kind(entity, field, compiled.messages().get("en")));
            }
        }
        assertThat(kinds).containsAll(Set.of(FieldType.SELECT, FieldType.ENUM, FieldType.REF));
        assertThat(scopes).containsExactlyInAnyOrder("org_unit", "owner", "all");
    }

    /**
     * Format 4 (ADR-0041): a form named by its title field, by the record's number or by the system name and code; its
     * sections in order — the system fields first, the org unit last on the first one — its tabs with the history last,
     * and the labels of both in the form's own dictionary.
     */
    @Test
    void everyStoredFormatFourSampleReadsBackKeepsItsChecksumAndCompilesItsTitleSectionsAndTabs() throws Exception {
        JsonNode samples = json.readTree(Files.readString(SAMPLES_V4));
        Set<String> titles = new HashSet<>();
        boolean tabbed = false;
        long id = 3000;
        for (JsonNode sample : samples) {
            String stored = json.writeValueAsString(sample.path("declaration"));
            Declaration declaration = repository.declaration(stored);
            assertThat(declaration.formatVersion()).isEqualTo(4);
            assertThat(json.valueToTree(declaration).equals(sample.path("declaration")))
                    .as("stored format of %s", declaration.entity().name())
                    .isTrue();
            var prepared = new MdSimpleFormDeclarations(json).prepare(stored.getBytes(StandardCharsets.UTF_8));
            assertThat(prepared.declaration()).isEqualTo(declaration);
            assertThat(prepared.checksum())
                    .as("checksum of %s", declaration.entity().name())
                    .isEqualTo(sample.path("checksum").asString());

            var compiled = new StudioEntityCompiler().compile(id++, declaration);
            EntityDefinition entity = compiled.entity();
            StudioChangeRules.requireDefaults(declaration, entity);
            String title = StudioEntityCompiler.titleKey(declaration);
            titles.add(title);
            assertThat(compiled.titleField()).isEqualTo(title);
            assertThat(entity.model().fields().getFirst().key()).isEqualTo(title);
            boolean named = "name".equals(title);
            assertThat(entity.fieldsByKey().containsKey("code")).isEqualTo(named);
            assertThat(entity.fieldsByKey().containsKey("recordNumber")).isEqualTo("recordNumber".equals(title));
            String prefix = "studio.live." + declaration.entity().name();
            var layout = declaration.layout();
            if (layout == null) {
                assertThat(entity.layout())
                        .singleElement()
                        .satisfies(section -> assertThat(section.key()).isEqualTo("main"));
                continue;
            }
            assertThat(entity.layout().stream().map(section -> section.key()).toList())
                    .containsExactlyElementsOf(layout.sections().stream()
                            .map(section -> section.key())
                            .toList());
            var first = entity.layout().getFirst().fields();
            if (named) assertThat(first.subList(0, 2)).containsExactly("name", "code");
            if ("org_unit".equals(declaration.entity().scope()))
                assertThat(first.getLast()).isEqualTo(StudioEntityCompiler.ORG_UNIT);
            for (var section : layout.sections()) {
                assertThat(compiled.messages().get("en"))
                        .containsEntry(
                                prefix + ".section." + section.key(),
                                section.labels().en());
            }
            if (layout.tabs() == null) {
                assertThat(entity.model().tabs()).isEmpty();
                continue;
            }
            tabbed = true;
            assertThat(entity.model().tabs().stream().map(tab -> tab.key()).toList())
                    .containsExactlyElementsOf(java.util.stream.Stream.concat(
                                    layout.tabs().stream().map(tab -> tab.key()), java.util.stream.Stream.of("history"))
                            .toList());
            for (var tab : layout.tabs()) {
                assertThat(compiled.messages().get("uz"))
                        .containsEntry(
                                prefix + ".tab." + tab.key(), tab.labels().uz());
            }
        }
        assertThat(titles).containsExactlyInAnyOrder("title", "recordNumber", "name");
        assertThat(tabbed).as("a stored sample has tabs").isTrue();
    }

    /**
     * Format 5 (ADR-0042): the formulas of the fields and the rules of the form. A computed field compiles read-only and
     * is evaluated after the computed fields it reads; a default formula, a condition and a rule compile into the form
     * logic; a rule is a cross-field rule of the entity with its message in the form's dictionary; the formulas of a
     * hidden field are kept but not served.
     */
    @Test
    void everyStoredFormatFiveSampleReadsBackKeepsItsChecksumAndCompilesItsFormulasAndRules() throws Exception {
        JsonNode samples = json.readTree(Files.readString(SAMPLES_V5));
        Set<String> kinds = new HashSet<>();
        long id = 4000;
        for (JsonNode sample : samples) {
            String stored = json.writeValueAsString(sample.path("declaration"));
            Declaration declaration = repository.declaration(stored);
            assertThat(declaration.formatVersion()).isEqualTo(5);
            assertThat(json.valueToTree(declaration).equals(sample.path("declaration")))
                    .as("stored format of %s", declaration.entity().name())
                    .isTrue();
            var prepared = new MdSimpleFormDeclarations(json).prepare(stored.getBytes(StandardCharsets.UTF_8));
            assertThat(prepared.declaration()).isEqualTo(declaration);
            assertThat(prepared.checksum())
                    .as("checksum of %s", declaration.entity().name())
                    .isEqualTo(sample.path("checksum").asString());

            var compiled = new StudioEntityCompiler().compile(id++, declaration);
            EntityDefinition entity = compiled.entity();
            StudioChangeRules.requireDefaults(declaration, entity);
            var logic = compiled.logic();
            String prefix = "studio.live." + declaration.entity().name();
            for (Field field : declaration.fields()) {
                if (Boolean.TRUE.equals(field.hidden())) {
                    assertThat(entity.fieldsByKey()).doesNotContainKey(field.id());
                    assertThat(logic.computedKeys()).doesNotContain(field.id());
                    kinds.add("hidden");
                    continue;
                }
                FormField form = entity.fieldsByKey().get(field.id());
                if (field.formula() != null) {
                    kinds.add("formula");
                    assertThat(form.flags().readonly()).isEqualTo(FieldReadonly.ALWAYS);
                    assertThat(logic.computedFormula(field.id())).isNotNull();
                }
                if (field.defaultFormula() != null) kinds.add("default");
                if (field.visibleIf() != null) kinds.add("visible");
                if (field.requiredIf() != null) kinds.add("required");
                if (field.readonlyIf() != null) kinds.add("readonly");
                assertThat(logic.defaults().containsKey(field.id())).isEqualTo(field.defaultFormula() != null);
                assertThat(logic.visible().containsKey(field.id())).isEqualTo(field.visibleIf() != null);
                assertThat(logic.required().containsKey(field.id())).isEqualTo(field.requiredIf() != null);
                assertThat(logic.readonly().containsKey(field.id())).isEqualTo(field.readonlyIf() != null);
            }
            List<String> order =
                    logic.computed().stream().map(item -> item.field()).toList();
            for (var item : logic.computed()) {
                for (String read : item.formula().fields().keySet()) {
                    if (order.contains(read)) assertThat(order.indexOf(read)).isLessThan(order.indexOf(item.field()));
                }
            }
            if (declaration.rules() == null) continue;
            for (var rule : declaration.rules()) {
                kinds.add("rule");
                assertThat(entity.model().rules()).containsKey("rule." + rule.key());
                assertThat(compiled.messages().get("uz"))
                        .containsEntry(
                                prefix + ".rule." + rule.key(), rule.message().uz());
            }
        }
        assertThat(kinds)
                .containsExactlyInAnyOrder("formula", "default", "visible", "required", "readonly", "rule", "hidden");
    }

    private static FieldType kind(EntityDefinition entity, Field field, Map<String, String> english) {
        FormField form = entity.fieldsByKey().get(field.id());
        String where = entity.code() + "." + field.id();
        assertThat(form).as(where).isNotNull();
        EntityField stored = entity.model().fields().stream()
                .filter(candidate -> candidate.key().equals(field.id()))
                .findFirst()
                .orElseThrow();
        assertThat(stored.source()).as(where).isEqualTo(new FieldSource.JsonProperty("payload", field.id()));
        switch (field.type()) {
            case "select" -> {
                assertThat(form.type()).as(where).isEqualTo(FieldType.SELECT);
                assertThat(stored.options().options())
                        .containsExactlyElementsOf(field.options().stream()
                                .map(option -> option.code())
                                .toList());
                for (var option : field.options()) {
                    assertThat(english)
                            .containsEntry(
                                    stored.options().optionLabelPrefix() + option.code(),
                                    option.labels().en());
                }
            }
            case "user" -> {
                assertThat(form.type()).as(where).isEqualTo(FieldType.REF);
                assertThat(stored.options().target()).isEqualTo(StudioEntityCompiler.USERS);
            }
            case "ref" -> {
                if ("list".equals(field.target().kind())) {
                    assertThat(form.type()).as(where).isEqualTo(FieldType.ENUM);
                    assertThat(stored.options().enumeration())
                            .isEqualTo("studio.list." + field.target().code());
                } else {
                    assertThat(form.type()).as(where).isEqualTo(FieldType.REF);
                    assertThat(stored.options().target())
                            .isEqualTo(field.target().code());
                    assertThat(stored.sql("t")).endsWith("::bigint");
                }
            }
            default -> {
                return check(entity, field);
            }
        }
        return form.type();
    }

    private static FieldType check(EntityDefinition entity, Field field) {
        String where = entity.code() + "." + field.id();
        FormField form = entity.fieldsByKey().get(field.id());
        assertThat(form).as(where).isNotNull();
        assertThat(form.type()).as(where).isEqualTo(TYPES.get(field.type()));
        assertThat(form.required()).as(where).isEqualTo(field.required());
        assertThat(form.maxLength()).as(where).isEqualTo(field.maxLength());
        assertThat(form.params().scale()).as(where).isEqualTo(field.scale());
        EntityField stored = entity.model().fields().stream()
                .filter(candidate -> candidate.key().equals(field.id()))
                .findFirst()
                .orElseThrow();
        assertThat(stored.source()).as(where).isEqualTo(new FieldSource.JsonProperty("payload", field.id()));
        assertThat(stored.list() != null).as(where).isEqualTo(field.listed());
        return form.type();
    }
}
