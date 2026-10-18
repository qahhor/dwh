package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.service.StudioChangeRules.Change;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The changes a new version of a document may make (ADR-0043): its parts may grow and their labels, conditions,
 * comments and formulas change; a table of lines keeps its place, its rows and its columns, the number its pattern and
 * the statuses their first one and every status records may be in.
 */
class StudioDocumentChangeRulesTest {
    private static final Path ORDER = Path.of("src/test/resources/studio/golden/stored-declarations-v6.json");

    private final ObjectMapper json = JsonMapper.shared();
    private final StudioFormRepository repository = new StudioFormRepository(mock(JdbcClient.class), json);

    @Test
    void aDocumentGrowsAndRelabelsFreely() throws Exception {
        assertThat(changes(root -> {
                    ObjectNode table = (ObjectNode) lines(root).get(0);
                    table.put("maxRows", 200);
                    ArrayNode columns = (ArrayNode) table.get("fields");
                    ObjectNode added = (ObjectNode) columns.get(4).deepCopy();
                    added.put("id", "extra");
                    columns.add(added);
                    ((ObjectNode) columns.get(3)).put("formula", "round(qty * price, 2)");
                    ObjectNode second = table.deepCopy();
                    second.put("key", "services");
                    lines(root).add(second);
                    ObjectNode state = (ObjectNode) states(root).get(1).deepCopy();
                    state.put("code", "archived");
                    states(root).add(state);
                    ((ObjectNode) transitions(root).get(0)).remove("condition");
                    ((ObjectNode) transitions(root).get(1)).put("comment", "required");
                    ((ObjectNode) root.get("entity").get("numbering")).remove("assignOn");
                }))
                .isEmpty();
    }

    @Test
    void aTableKeepsItsPlaceRowsAndColumns() throws Exception {
        assertThat(changes(root -> root.remove("lines"))).containsExactly(new Change("lines.items", "lines_moved"));
        assertThat(changes(root -> ((ObjectNode) lines(root).get(0)).put("maxRows", 10)))
                .containsExactly(new Change("lines.items", "max_rows"));
        assertThat(changes(root -> {
                    ArrayNode columns = columns(root);
                    columns.remove(4);
                    ((ObjectNode) columns.get(1))
                            .put("type", "text")
                            .put("maxLength", 10)
                            .remove("scale");
                    ((ObjectNode) columns.get(2)).put("scale", 1);
                    ((ArrayNode) columns.get(0).get("options")).remove(1);
                    ObjectNode added = (ObjectNode) columns.get(3).deepCopy();
                    added.put("id", "mandatory").put("required", true).remove("formula");
                    columns.add(added);
                }))
                .containsExactlyInAnyOrder(
                        new Change("lines.items.note", "removed"),
                        new Change("lines.items.qty", "type"),
                        new Change("lines.items.price", "scale"),
                        new Change("lines.items.product", "option_removed"),
                        new Change("lines.items.mandatory", "required"));
    }

    @Test
    void theNumberAndTheStatusesKeepWhatRecordsHold() throws Exception {
        assertThat(changes(root -> ((ObjectNode) root.get("entity").get("numbering")).put("pattern", "N-{YYYY}-{NNN}")))
                .containsExactly(new Change("entity.numbering", "numbering"));
        assertThat(changes(root -> root.remove("workflow")))
                .containsExactly(new Change("workflow", "workflow_removed"));
        assertThat(changes(root -> {
                    ObjectNode first = (ObjectNode) states(root).get(0);
                    states(root).remove(0);
                    states(root).insert(1, first);
                }))
                .containsExactly(new Change("workflow.states.draft", "initial"));
        ApiException refused = StudioChangeRules.refusal(
                StudioChangeRules.incompatible(declaration(root -> {}), declaration(root -> root.remove("workflow"))));
        assertThat(refused.getFieldErrors()).extracting(FieldErrorItem::field).containsExactly("workflow");
        assertThatThrownBy(() -> {
                    throw StudioChangeRules.refusal(StudioChangeRules.incompatible(
                            declaration(root -> {}), declaration(root -> ((ArrayNode) columns(root)).remove(4))));
                })
                .isInstanceOfSatisfying(
                        ApiException.class,
                        problem -> assertThat(problem.getFieldErrors())
                                .extracting(FieldErrorItem::field)
                                .containsExactly("lines.items.note"));
    }

    @Test
    void aColumnKeepsItsTargetLengthOptionsAndEnteredValues() throws Exception {
        assertThat(changes(root -> ((ObjectNode) lines(root).get(0)).put("key", "goods")))
                .containsExactly(new Change("lines.items", "lines_moved"));
        assertThat(changes(root -> {
                    ArrayNode columns = columns(root);
                    ((ObjectNode) columns.get(0)).remove("options");
                    ((ObjectNode) columns.get(1)).put("formula", "price");
                    ((ObjectNode) columns.get(4)).put("maxLength", 100);
                }))
                .containsExactlyInAnyOrder(
                        new Change("lines.items.product", "option_removed"),
                        new Change("lines.items.qty", "computed"),
                        new Change("lines.items.note", "max_length"));
        assertThat(changes(root -> ((ObjectNode) columns(root).get(4)).remove("maxLength")))
                .isEmpty();
        Consumer<ObjectNode> goods = root -> columns(root)
                .addObject()
                .put("id", "item")
                .put("type", "ref")
                .put("required", false)
                .setAll((ObjectNode) json.readTree("{\"labels\":{\"ru\":\"Товар\",\"uz\":\"Tovar\",\"en\":\"Item\"},"
                        + "\"target\":{\"kind\":\"list\",\"code\":\"goods\"}}"));
        Declaration before = declaration(goods);
        Declaration after = declaration(
                goods.andThen(root -> ((ObjectNode) columns(root).get(5).get("target")).put("code", "services")));
        assertThat(StudioChangeRules.incompatible(before, after))
                .containsExactly(new Change("lines.items.item", "target"));
    }

    @Test
    void theNumberKeepsItsPeriodAndAStatusItsRecords() throws Exception {
        assertThat(changes(root -> ((ObjectNode) root.get("entity")).remove("numbering")))
                .containsExactly(new Change("entity.numbering", "numbering"));
        assertThat(changes(root -> ((ObjectNode) root.get("entity").get("numbering")).put("reset", "month")))
                .containsExactly(new Change("entity.numbering", "numbering"));
        assertThat(changes(root -> states(root).remove(3)))
                .containsExactly(new Change("workflow.states.rejected", "state_removed"));
    }

    private java.util.List<Change> changes(Consumer<ObjectNode> change) throws Exception {
        return StudioChangeRules.incompatible(declaration(root -> {}), declaration(change));
    }

    private Declaration declaration(Consumer<ObjectNode> change) throws Exception {
        ObjectNode root = (ObjectNode)
                json.readTree(Files.readString(ORDER)).get(0).get("declaration").deepCopy();
        change.accept(root);
        return repository.declaration(json.writeValueAsString(root));
    }

    private static ArrayNode lines(ObjectNode root) {
        return (ArrayNode) root.get("lines");
    }

    private static ArrayNode columns(ObjectNode root) {
        return (ArrayNode) lines(root).get(0).get("fields");
    }

    private static ArrayNode states(ObjectNode root) {
        return (ArrayNode) root.get("workflow").get("states");
    }

    private static ArrayNode transitions(ObjectNode root) {
        return (ArrayNode) root.get("workflow").get("transitions");
    }
}
