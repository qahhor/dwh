package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Workflow;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The rows of a document compile to a collection of the shared runtime, one column per type (ADR-0043). */
class StudioDocumentCompilerTest {
    private static final Path ORDER = Path.of("src/test/resources/studio/golden/stored-declarations-v6.json");
    private static final List<String> SIMPLE =
            List.of("text", "textarea", "number", "date", "datetime", "time", "bool", "email", "phone", "url");

    private final ObjectMapper json = JsonMapper.shared();
    private final StudioFormRepository repository = new StudioFormRepository(mock(JdbcClient.class), json);

    @Test
    void everyColumnTypeBecomesAFieldOfTheRowsReadFromTheirPayload() throws Exception {
        Declaration source = declaration(root -> {
            ArrayNode columns = (ArrayNode) root.get("lines").get(0).get("fields");
            for (String type : SIMPLE) column(columns, "c" + type, type);
            column(columns, "cuser", "user");
            column(columns, "cclient", "ref").set("target", json.readTree("{\"kind\":\"form\",\"code\":\"clients\"}"));
            column(columns, "cgoods", "ref").set("target", json.readTree("{\"kind\":\"list\",\"code\":\"goods\"}"));
        });
        List<EntityCollection> collections = StudioDocumentCompiler.collections(
                source, "studio.live.orders", code -> Map.of("clients", "phone").getOrDefault(code, "name"));

        EntityCollection items = collections.getFirst();
        assertThat(items.table()).isEqualTo("studio_record_lines_1");
        Map<String, FieldType> types = new java.util.LinkedHashMap<>();
        for (EntityField field : items.fields()) types.put(field.key(), field.type());
        assertThat(types)
                .containsEntry("cnumber", FieldType.NUMBER)
                .containsEntry("cdatetime", FieldType.DATETIME)
                .containsEntry("cbool", FieldType.BOOLEAN)
                .containsEntry("cuser", FieldType.REF)
                .containsEntry("cclient", FieldType.REF)
                .containsEntry("cgoods", FieldType.ENUM);
        assertThat(types.keySet()).contains("ctext", "ctextarea", "cdate", "ctime", "cemail", "cphone", "curl");
        EntityField client = items.fields().stream()
                .filter(field -> field.key().equals("cclient"))
                .findFirst()
                .orElseThrow();
        assertThat(client.options().target()).isEqualTo("clients");
    }

    @Test
    void aFormKeptForItsRightsHasItsStatusesWithoutLocksOrConditions() throws Exception {
        Workflow workflow = declaration(root -> {
                    ObjectNode pending =
                            (ObjectNode) root.get("workflow").get("states").get(1);
                    pending.put("lockAll", true);
                })
                .workflow();
        Workflow kept = StudioDocumentCompiler.rightsOnly(workflow);
        assertThat(kept.states())
                .extracting(state -> state.code())
                .containsExactly("draft", "pending", "approved", "rejected");
        assertThat(kept.states())
                .allSatisfy(state -> assertThat(state.lockAll()).isNull());
        assertThat(kept.transitions())
                .allSatisfy(transition -> assertThat(transition.condition()).isNull());
        assertThat(kept.transitions().get(1).notAuthor()).isTrue();
        assertThat(StudioDocumentCompiler.lockable(List.of(), null)).isEmpty();
    }

    private ObjectNode column(ArrayNode columns, String id, String type) {
        ObjectNode column = columns.addObject();
        column.put("id", id).put("type", type).put("required", false);
        column.set("labels", json.readTree("{\"ru\":\"Колонка\",\"uz\":\"Ustun\",\"en\":\"Column\"}"));
        if ("text".equals(type)) column.put("maxLength", 50);
        return column;
    }

    private Declaration declaration(Consumer<ObjectNode> change) throws Exception {
        ObjectNode root = (ObjectNode)
                json.readTree(Files.readString(ORDER)).get(0).get("declaration").deepCopy();
        change.accept(root);
        return repository.declaration(json.writeValueAsString(root));
    }
}
