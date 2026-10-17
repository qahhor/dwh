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
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
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
