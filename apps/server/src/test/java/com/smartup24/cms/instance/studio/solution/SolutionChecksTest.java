package com.smartup24.cms.instance.studio.solution;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** ADR-0047: the structure of a package — required parts, codes, counts and the references between its parts. */
class SolutionChecksTest {
    private final SolutionFixtures fixtures = new SolutionFixtures();

    @Test
    void theHeadIsComplete() {
        assertThat(fields(root -> root.put("code", "Order"))).containsExactly("code");
        assertThat(fields(root -> root.put("version", "1.0"))).containsExactly("version");
        assertThat(fields(root -> ((ObjectNode) root.get("title")).put("ru", " ")))
                .containsExactly("title");
        assertThat(fields(root -> ((ObjectNode) root.get("description")).put("en", "x".repeat(501))))
                .containsExactly("description");
        assertThat(fields(root -> ((ObjectNode) root.get("description")).put("uz", "line\u0007")))
                .containsExactly("description");
        assertThat(fields(root -> ((ObjectNode) root.get("description")).put("ru", " padded")))
                .containsExactly("description");
        assertThat(fields(root -> root.put("producedBy", " "))).containsExactly("producedBy");
        assertThat(fields(root -> root.put("producedBy", "x".repeat(65)))).containsExactly("producedBy");
        assertThat(fields(root -> ((ObjectNode) root.get("requires")).put("simpleFormFormat", 0)))
                .contains("requires");
        assertThat(fields(root -> ((ObjectNode) root.get("requires")).put("simpleFormFormat", 5)))
                .containsExactly("forms[1].declaration.formatVersion");
        assertThat(fields(root -> root.remove("requires"))).contains("requires");
        assertThat(fields(root -> root.remove("producedBy"))).containsExactly("producedBy");
    }

    @Test
    void placeholdersAreUniqueWellFormedAndOfAKnownKind() {
        assertThat(fields(root -> placeholder(root, 0).put("key", "Seller"))).contains("placeholders[0].key");
        assertThat(fields(root -> placeholder(root, 1).put("key", "seller"))).contains("placeholders[1].key");
        assertThat(fields(root -> placeholder(root, 0).put("kind", "group"))).contains("placeholders[0].kind");
        assertThat(fields(root -> placeholder(root, 0).remove("labels"))).contains("placeholders[0].labels");
        assertThat(fields(root -> ((ArrayNode) root.get("placeholders")).addNull()))
                .contains("placeholders[3]");
        assertThat(fields(root -> root.remove("placeholders"))).contains("placeholders");
    }

    @Test
    void listsAndTheirItemsAreUniqueAndBounded() {
        assertThat(fields(root -> list(root, 1).put("code", "regions"))).containsExactly("lists[1].code");
        assertThat(fields(root -> list(root, 0).put("code", "Regions"))).containsExactly("lists[0].code");
        assertThat(fields(root -> list(root, 0).remove("name"))).containsExactly("lists[0].name");
        assertThat(fields(root -> item(root, 0, 1).put("code", "tashkent"))).containsExactly("lists[0].items[1].code");
        assertThat(fields(root -> item(root, 0, 0).put("sortOrder", 0))).containsExactly("lists[0].items[0].sortOrder");
        assertThat(fields(root -> item(root, 0, 0).put("sortOrder", 10000)))
                .containsExactly("lists[0].items[0].sortOrder");
        assertThat(fields(root -> item(root, 0, 0).remove("name"))).containsExactly("lists[0].items[0].name");
        assertThat(fields(root -> ((ArrayNode) list(root, 0).get("items")).addNull()))
                .containsExactly("lists[0].items[5]");
        assertThat(fields(root -> list(root, 0).remove("items"))).containsExactly("lists[0].items");
        assertThat(fields(root -> ((ArrayNode) root.get("lists")).addNull())).containsExactly("lists[2]");
        assertThat(fields(root -> root.remove("lists"))).contains("lists");
    }

    @Test
    void formsAreUnique() {
        assertThat(fields(root -> ((ArrayNode) root.get("forms"))
                        .add(root.get("forms").get(0).deepCopy())))
                .containsExactly("forms[2]");
    }

    @Test
    void datasetsAreComplete() {
        ObjectNode dataset = fixtures.json.createObjectNode();
        dataset.put("code", "sales");
        dataset.set("name", fixtures.order().get("title"));
        dataset.put("entity", "studio.orders");
        dataset.putArray("fields").add("region");
        dataset.putArray("filter");
        dataset.putArray("measures");
        assertThat(fields(root -> ((ArrayNode) root.get("datasets")).add(dataset.deepCopy())))
                .isEmpty();
        assertThat(fields(root -> {
                    ArrayNode datasets = (ArrayNode) root.get("datasets");
                    datasets.add(dataset.deepCopy());
                    datasets.add(dataset.deepCopy());
                }))
                .containsExactly("datasets[1].code");
        for (String part : List.of("entity", "fields", "filter", "measures", "name")) {
            ObjectNode missing = dataset.deepCopy();
            missing.remove(part);
            assertThat(fields(root -> ((ArrayNode) root.get("datasets")).add(missing)))
                    .containsExactly("datasets[0]." + part);
        }
        assertThat(fields(root -> ((ArrayNode) root.get("datasets")).addNull())).containsExactly("datasets[0]");
    }

    @Test
    void reportsAreCompleteAndSharedWithRolePlaceholders() {
        assertThat(fields(root -> report(root).put("key", "Orders")))
                .containsExactly("reports[0].key", "mailings[0].report");
        assertThat(fields(root -> report(root).put("name", "x".repeat(81)))).containsExactly("reports[0].name");
        assertThat(fields(root -> report(root).remove("list"))).containsExactly("reports[0].list");
        assertThat(fields(root -> report(root).put("state", "x"))).containsExactly("reports[0].state");
        assertThat(fields(root -> ((ArrayNode) report(root).get("share")).set(0, "mailing-recipients")))
                .containsExactly("reports[0].share[0]");
        assertThat(fields(root -> report(root).remove("share"))).containsExactly("reports[0].share");
        assertThat(fields(root -> {
                    ArrayNode reports = (ArrayNode) root.get("reports");
                    reports.add(reports.get(0).deepCopy());
                }))
                .containsExactly("reports[1].key");
        assertThat(fields(root -> ((ArrayNode) root.get("reports")).addNull())).containsExactly("reports[1]");
    }

    @Test
    void mailingsNameTheirReportAndAPlaceholderOfUsers() {
        assertThat(fields(root -> mailing(root).put("report", "missing"))).containsExactly("mailings[0].report");
        assertThat(fields(root -> mailing(root).put("recipients", "approver")))
                .containsExactly("mailings[0].recipients");
        assertThat(fields(root -> mailing(root).putArray("channels"))).containsExactly("mailings[0].channels");
        assertThat(fields(root -> mailing(root).remove("schedule"))).containsExactly("mailings[0].schedule");
        assertThat(fields(root -> mailing(root).remove("condition"))).containsExactly("mailings[0].condition");
        assertThat(fields(root -> mailing(root).remove("parameters"))).containsExactly("mailings[0].parameters");
        assertThat(fields(root -> mailing(root).put("name", " "))).containsExactly("mailings[0].name");
        assertThat(fields(root -> mailing(root).put("key", "-x"))).containsExactly("mailings[0].key");
        assertThat(fields(root -> {
                    ArrayNode mailings = (ArrayNode) root.get("mailings");
                    mailings.add(mailings.get(0).deepCopy());
                }))
                .containsExactly("mailings[1].key");
        assertThat(fields(root -> ((ArrayNode) root.get("mailings")).addNull())).containsExactly("mailings[1]");
    }

    @Test
    void automationsHaveActionsAndTheirUsersArePlaceholders() {
        assertThat(fields(root -> automation(root).putArray("actions"))).containsExactly("automations[0].actions");
        assertThat(fields(root -> ((ArrayNode) automation(root).get("actions")).addNull()))
                .containsExactly("automations[0].actions[2]");
        assertThat(fields(root ->
                        ((ObjectNode) automation(root).get("actions").get(1).get("recipients")).put("users", "seller")))
                .containsExactly("automations[0].actions[1].recipients.users");
        assertThat(fields(root -> ((ObjectNode)
                                automation(root).get("actions").get(1).get("recipients"))
                        .put("users", "mailing-recipients")))
                .isEmpty();
        assertThat(fields(root -> automation(root).remove("form"))).containsExactly("automations[0].form");
        assertThat(fields(root -> automation(root).remove("trigger"))).containsExactly("automations[0].trigger");
        assertThat(fields(root -> automation(root).remove("timeZone"))).containsExactly("automations[0].timeZone");
        assertThat(fields(root -> {
                    ArrayNode automations = (ArrayNode) root.get("automations");
                    automations.add(automations.get(0).deepCopy());
                }))
                .containsExactly("automations[1].key");
        assertThat(fields(root -> ((ArrayNode) root.get("automations")).addNull()))
                .containsExactly("automations[1]");
    }

    @Test
    void grantsGiveTheRightsOfTheFormsOfThePackageToRolePlaceholders() {
        assertThat(fields(root -> grant(root).put("role", "mailing-recipients")))
                .containsExactly("grants[0].role");
        assertThat(fields(root -> grant(root).putArray("permissions"))).containsExactly("grants[0].permissions");
        assertThat(fields(root -> ((ObjectNode) grant(root).get("permissions").get(0)).put("form", "md.users")))
                .containsExactly("grants[0].permissions[0]");
        assertThat(fields(root -> ((ObjectNode) grant(root).get("permissions").get(0)).put("action", "View")))
                .containsExactly("grants[0].permissions[0]");
        assertThat(fields(root -> ((ArrayNode) grant(root).get("permissions")).addNull()))
                .containsExactly("grants[0].permissions[6]");
        assertThat(fields(root -> ((ArrayNode) root.get("grants")).addNull())).containsExactly("grants[2]");
    }

    @Test
    void everyMissingOrMalformedPartIsNamed() {
        assertThat(fields(root -> root.remove("code"))).containsExactly("code");
        assertThat(fields(root -> ((ObjectNode) root.get("title")).put("uz", " ")))
                .containsExactly("title");
        assertThat(fields(root -> ((ObjectNode) root.get("title")).put("en", " ")))
                .containsExactly("title");
        assertThat(fields(root -> ((ObjectNode) root.get("description")).remove("en")))
                .containsExactly("description");
        assertThat(fields(root -> ((ObjectNode) root.get("description")).put("ru", " ")))
                .containsExactly("description");
        assertThat(fields(root -> ((ObjectNode) placeholder(root, 0).get("labels")).remove("ru")))
                .containsExactly("placeholders[0].labels");
        assertThat(fields(root -> item(root, 0, 0).put("code", "Tashkent"))).containsExactly("lists[0].items[0].code");
        assertThat(fields(root -> root.remove("datasets"))).containsExactly("datasets");
        assertThat(fields(root -> root.remove("reports"))).contains("reports");
        assertThat(fields(root -> root.remove("mailings"))).containsExactly("mailings");
        assertThat(fields(root -> root.remove("automations"))).containsExactly("automations");
        assertThat(fields(root -> root.remove("grants"))).containsExactly("grants");
        assertThat(fields(root -> report(root).remove("state"))).containsExactly("reports[0].state");
        assertThat(fields(root -> mailing(root).remove("channels"))).containsExactly("mailings[0].channels");
        assertThat(fields(root -> mailing(root).remove("recipients"))).containsExactly("mailings[0].recipients");
        assertThat(fields(root -> mailing(root).remove("name"))).containsExactly("mailings[0].name");
        assertThat(fields(root -> automation(root).put("key", "A"))).containsExactly("automations[0].key");
        assertThat(fields(root -> automation(root).remove("actions"))).containsExactly("automations[0].actions");
        assertThat(fields(root -> {
                    ArrayNode actions = (ArrayNode) automation(root).get("actions");
                    for (int i = 0; i < 10; i++) actions.add(actions.get(0).deepCopy());
                }))
                .containsExactly("automations[0].actions");
        assertThat(fields(root -> ((ObjectNode) automation(root).get("actions").get(0)).remove("type")))
                .containsExactly("automations[0].actions[0]");
        assertThat(fields(root -> grant(root).remove("permissions"))).containsExactly("grants[0].permissions");
        assertThat(fields(root -> ((ObjectNode) grant(root).get("permissions").get(0)).remove("action")))
                .containsExactly("grants[0].permissions[0]");
        assertThat(fields(root -> {
                    ArrayNode placeholders = (ArrayNode) root.get("placeholders");
                    for (int i = 0; i < 50; i++)
                        placeholders
                                .addObject()
                                .put("key", "extra-" + i)
                                .put("kind", "role")
                                .put("required", false)
                                .set("labels", root.get("title").deepCopy());
                }))
                .contains("placeholders");
        ObjectNode dataset = fixtures.json.createObjectNode();
        dataset.put("code", "Sales");
        dataset.set("name", fixtures.order().get("title"));
        dataset.put("entity", "studio.orders");
        dataset.putArray("fields");
        dataset.putArray("filter");
        dataset.putArray("measures");
        assertThat(fields(root -> ((ArrayNode) root.get("datasets")).add(dataset)))
                .containsExactly("datasets[0].code");
    }

    @Test
    void anExportOfTooManyFormsIsRefused() {
        var pack = fixtures.read(fixtures.sealed(fixtures.order()));
        var forms = new java.util.ArrayList<>(pack.forms());
        while (forms.size() <= 20) forms.add(pack.forms().getFirst());
        var many = new com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage(
                pack.formatVersion(),
                pack.kind(),
                pack.code(),
                pack.version(),
                pack.title(),
                pack.description(),
                pack.requires(),
                pack.producedBy(),
                pack.placeholders(),
                pack.lists(),
                forms,
                pack.datasets(),
                pack.reports(),
                pack.mailings(),
                pack.automations(),
                pack.grants(),
                null);
        try {
            SolutionChecks.require(many);
            throw new AssertionError("Too many forms were accepted");
        } catch (ApiException refused) {
            assertThat(refused.getFieldErrors())
                    .extracting(FieldErrorItem::field)
                    .contains("forms");
        }
    }

    private List<String> fields(Consumer<ObjectNode> change) {
        try {
            fixtures.read(fixtures.changed(change));
            return List.of();
        } catch (ApiException refused) {
            assertThat(refused.getMessageKey()).isEqualTo("error.studio.solution_invalid");
            return refused.getFieldErrors().stream().map(FieldErrorItem::field).toList();
        }
    }

    private static ObjectNode placeholder(JsonNode root, int index) {
        return (ObjectNode) root.get("placeholders").get(index);
    }

    private static ObjectNode list(JsonNode root, int index) {
        return (ObjectNode) root.get("lists").get(index);
    }

    private static ObjectNode item(JsonNode root, int list, int index) {
        return (ObjectNode) root.get("lists").get(list).get("items").get(index);
    }

    private static ObjectNode report(JsonNode root) {
        return (ObjectNode) root.get("reports").get(0);
    }

    private static ObjectNode mailing(JsonNode root) {
        return (ObjectNode) root.get("mailings").get(0);
    }

    private static ObjectNode automation(JsonNode root) {
        return (ObjectNode) root.get("automations").get(0);
    }

    private static ObjectNode grant(JsonNode root) {
        return (ObjectNode) root.get("grants").get(0);
    }
}
