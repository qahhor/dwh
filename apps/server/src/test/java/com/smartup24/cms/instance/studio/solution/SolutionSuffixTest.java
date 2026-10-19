package com.smartup24.cms.instance.studio.solution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdReportDatasetDtos.Measure;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Right;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Action;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageAutomation;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageDataset;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.PackageReport;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Recipients;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** ADR-0047: a second copy of a solution renames the codes of its forms, lists and datasets and every reference. */
class SolutionSuffixTest {
    private final SolutionFixtures fixtures = new SolutionFixtures();
    private final SolutionSuffix suffixes = new SolutionSuffix(fixtures.json, fixtures.declarations);

    @Test
    void everyCodeAndReferenceTakesTheSuffix() {
        SolutionPackage pack = withDatasetAndCreatingAutomation(fixtures.read(fixtures.sealed(fixtures.order())));
        SolutionPackage copy = suffixes.apply(pack, "_b2");

        assertThat(copy.lists()).extracting(list -> list.code()).containsExactly("regions_b2", "products_b2");
        assertThat(copy.forms())
                .extracting(form -> form.declaration().entity().name())
                .containsExactly("clients_b2", "orders_b2");
        var orders = copy.forms().get(1).declaration();
        assertThat(orders.fields().getFirst().target().code()).isEqualTo("studio.clients_b2");
        assertThat(orders.fields().get(1).target().code()).isEqualTo("regions_b2");
        assertThat(orders.lines().getFirst().fields().getFirst().target().code())
                .isEqualTo("products_b2");
        assertThat(copy.forms().get(1).checksum())
                .isNotEqualTo(pack.forms().get(1).checksum());
        assertThat(copy.datasets().getFirst().code()).isEqualTo("sales_b2");
        assertThat(copy.datasets().getFirst().entity()).isEqualTo("studio.orders_b2");
        assertThat(copy.reports().getFirst().list()).isEqualTo("studio.orders_b2");
        assertThat(copy.reports().get(1).state().path("dataset").asString()).isEqualTo("ds.sales_b2");
        assertThat(copy.reports().get(2).state().path("dataset").asString()).isEqualTo("ds.elsewhere");
        PackageAutomation automation = copy.automations().get(1);
        assertThat(automation.form()).isEqualTo("studio.orders_b2");
        assertThat(automation.actions().getFirst().form()).isEqualTo("studio.clients_b2");
        assertThat(automation.actions().getFirst().recipients().rights())
                .extracting(Right::form)
                .containsExactly("studio.orders_b2", "md.users");
        assertThat(copy.grants().getFirst().permissions())
                .allSatisfy(permission -> assertThat(permission.form()).endsWith("_b2"));
        assertThat(copy.mailings()).isEqualTo(pack.mailings());
        assertThat(suffixes.apply(pack, null)).isSameAs(pack);
    }

    @Test
    void aSuffixIsWellFormedAndKeepsEveryCodeWithinItsLength() {
        SolutionPackage pack = fixtures.read(fixtures.sealed(fixtures.order()));
        assertThatThrownBy(() -> suffixes.apply(pack, "b2"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.studio.solution_suffix"));
        ObjectNode root = fixtures.order();
        root.putArray("grants");
        ((ObjectNode) root.get("forms").get(0).get("declaration").get("entity")).put("name", "clientsofthecompany");
        for (var form : root.get("forms")) {
            for (var field : form.get("declaration").get("fields")) {
                if (field.path("target").path("code").asString("").equals("studio.clients"))
                    ((ObjectNode) field.get("target")).put("code", "studio.clientsofthecompany");
            }
        }
        SolutionPackage longer = fixtures.read(fixtures.sealed(root));
        assertThatThrownBy(() -> suffixes.apply(longer, "_abcde")).isInstanceOf(ApiException.class);
        ObjectNode lists = fixtures.order();
        ((ObjectNode) lists.get("lists").get(0)).put("code", "r".repeat(38));
        for (var field : lists.get("forms").get(0).get("declaration").get("fields")) {
            if (field.path("target").path("kind").asString("").equals("list"))
                ((ObjectNode) field.get("target")).put("code", "r".repeat(38));
        }
        assertThatThrownBy(() -> suffixes.apply(fixtures.read(fixtures.sealed(lists)), "_abcde"))
                .isInstanceOf(ApiException.class);
        ObjectNode foreign = fixtures.order();
        foreign.putArray("lists");
        SolutionPackage withoutLists = fixtures.read(fixtures.sealed(foreign));
        assertThat(suffixes.apply(withoutLists, "_x")
                        .forms()
                        .get(1)
                        .declaration()
                        .fields()
                        .get(1)
                        .target()
                        .code())
                .isEqualTo("regions");
        ObjectNode longDataset = fixtures.order();
        ObjectNode dataset = longDataset.putArray("datasets").addObject();
        dataset.put("code", "d".repeat(38)).put("entity", "studio.orders");
        dataset.set("name", longDataset.get("title"));
        dataset.putArray("fields");
        dataset.putArray("filter");
        dataset.putArray("measures");
        SolutionPackage withDataset = fixtures.read(fixtures.sealed(longDataset));
        assertThatThrownBy(() -> suffixes.apply(withDataset, "_abcde")).isInstanceOf(ApiException.class);
    }

    /** The order with a dataset, reports over it and over another one, and an automation creating a client. */
    private SolutionPackage withDatasetAndCreatingAutomation(SolutionPackage pack) {
        Labels name = new Labels("Продажи", "Savdo", "Sales");
        var dataset = new PackageDataset(
                "sales",
                name,
                "studio.orders",
                "items",
                List.of("lineProduct"),
                List.of(),
                List.of(new Measure("double", name, "count * 2")));
        List<PackageReport> reports = new ArrayList<>(pack.reports());
        ObjectNode state = fixtures.json.createObjectNode().put("dataset", "ds.sales");
        reports.add(new PackageReport("by-dataset", "По набору", "studio.orders", state, List.of()));
        ObjectNode elsewhere = fixtures.json.createObjectNode().put("dataset", "ds.elsewhere");
        reports.add(new PackageReport("by-other", "Чужой набор", "studio.orders", elsewhere, List.of()));
        List<PackageAutomation> automations = new ArrayList<>(pack.automations());
        Recipients recipients = new Recipients(
                null, null, null, List.of(new Right("studio.orders", "approve"), new Right("md.users", "view")));
        Recipients usersOnly = new Recipients(null, null, "mailing-recipients", null);
        automations.add(new PackageAutomation(
                "create-client",
                "studio.orders",
                "Клиент",
                pack.automations().getFirst().trigger(),
                null,
                List.of(
                        new Action(
                                "create_record", List.of(), "studio.clients", null, null, null, recipients, null, null),
                        new Action("notify", null, null, null, null, List.of("in_app"), usersOnly, null, null)),
                "UTC"));
        return new SolutionPackage(
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
                pack.forms(),
                List.of(dataset),
                reports,
                pack.mailings(),
                automations,
                pack.grants(),
                pack.checksum());
    }
}
