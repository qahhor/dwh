package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.service.StudioEntityCompiler;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.EntityField;
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
 * Declarations stored today keep compiling (ADR-0038). Activated rows are immutable and never migrated, so every
 * sample here — one per field type and option, in the stored JSON format of simple-form v1 — must still read back
 * unchanged, keep its checksum (an exact repeat answers by it) and compile into the same runtime shape. A compiler, DTO
 * or validator change that breaks one of them needs a migration path or a new format version, not an edit of this file.
 */
class StudioStoredFormatCompatibilityTest {
    private static final Path SAMPLES = Path.of("src/test/resources/studio/golden/stored-declarations-v1.json");
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
