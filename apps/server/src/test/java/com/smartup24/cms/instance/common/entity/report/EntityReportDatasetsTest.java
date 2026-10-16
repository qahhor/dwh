package com.smartup24.cms.instance.common.entity.report;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.bool;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.ref;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityLists;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregates;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EntityReportDatasetsTest {
    private static final String ROOT = "example.datasetroots";
    private static final String TARGET = "example.datasettargets";

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void scopeArchiveAndNativeAliasAreInsideLeftJoinAndRootGrainIsUnchanged() {
        var target = target(false);
        var root = root();
        var resolver = resolver(root, target, dataset("targetColor", "color"));
        signIn(Set.of(ROOT + ".view", TARGET + ".view", "md.modules.view", "md.settings.update"));
        var source = resolver.resolve(ROOT, "example.dataset");
        assertThat(source.list().from())
                .contains(
                        "left join (select",
                        "from ex_products p where 1=1",
                        "p.created_by = :scopeUserId",
                        "p.archived_at is null",
                        "target_id = p.product_id");
        assertThat(source.params()).containsEntry("scopeUserId", 7L);
        var field = source.list().viewerField("targetColor").orElseThrow();
        assertThat(field.nullable()).isTrue();
        assertThat(field.sortable()).isFalse();
        assertThat(field.enumValues()).containsExactly("red", "blue");
    }

    @Test
    void eachRequestRechecksReferenceTargetAndFieldRights() {
        var resolver = resolver(root(), target(false), dataset("targetColor", "color"));
        signIn(Set.of(ROOT + ".view", TARGET + ".view", "md.settings.update"));
        var list = resolver.resolve(ROOT, "example.dataset").list();
        assertThat(list.viewerField("targetColor")).isEmpty();
        assertThatThrownBy(() -> QueryAggregates.compile(list, "[{\"field\":\"targetColor\"}]", null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> QueryAggregates.compile(
                        list, (String) null, null, "[{\"field\":\"targetColor\",\"op\":\"eq\",\"value\":\"red\"}]"))
                .isInstanceOf(ApiException.class);
        signIn(Set.of(ROOT + ".view", TARGET + ".view", "md.modules.view"));
        assertThat(resolver.catalog(ROOT).items()).isEmpty();
        assertThatThrownBy(() -> resolver.resolve(ROOT, "example.dataset")).isInstanceOf(ApiException.class);
        signIn(Set.of(ROOT + ".view", "md.settings.update", "md.modules.view"));
        assertThat(resolver.catalog(ROOT).items()).isEmpty();
        assertThatThrownBy(() -> resolver.resolve(ROOT, "example.dataset")).isInstanceOf(ApiException.class);
    }

    @Test
    void partitionAndTypedJsonProjectionStayInTargetSource() {
        var resolver = resolver(root(), target(true), dataset("targetFlag", "flag"));
        signIn(Set.of(ROOT + ".view", TARGET + ".view", "md.settings.update"));
        var source = resolver.resolve(ROOT, "example.dataset");
        assertThat(source.list().from()).contains("where form_id = 19", "payload->>'flag'", "boolean");
        assertThat(source.list().viewerField("targetFlag")).isPresent();
    }

    @Test
    void invalidDefinitionsNeverBecomeTrustedSql() {
        assertThatThrownBy(() -> resolver(root(), target(false), dataset("name", "color")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver(root(), target(false), dataset("total", "amount")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver(root(), target(false), dataset("flag", "expression")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver(
                        root(),
                        target(false),
                        new EntityReportDataset(
                                "example.bad",
                                ROOT,
                                "dataset",
                                "name",
                                List.of(new EntityReportDataset.Projection("targetColor", "color")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityReportDataset(
                        "example.bad;select",
                        ROOT,
                        "dataset",
                        "targetId",
                        List.of(new EntityReportDataset.Projection("targetColor", "color"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dataset("x;drop", "color")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver(root(), target(false), dataset("color", "missing")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projectionLabelsAreExplicitButNeverReplaceTheSourceRights() {
        var projection = new EntityReportDataset.Projection("targetColor", "color", "dataset.target_color");
        var declaration = new EntityReportDataset("example.dataset", ROOT, "dataset", "targetId", List.of(projection));
        var resolver = resolver(root(), target(false), declaration);
        signIn(Set.of(ROOT + ".view", TARGET + ".view", "md.modules.view", "md.settings.update"));
        var field = resolver.resolve(ROOT, "example.dataset")
                .list()
                .viewerField("targetColor")
                .orElseThrow();
        assertThat(field.labelKey()).isEqualTo("dataset.target_color");
        assertThat(field.label()).isNull();
        signIn(Set.of(ROOT + ".view", TARGET + ".view", "md.settings.update"));
        assertThat(resolver.resolve(ROOT, "example.dataset").list().viewerField("targetColor"))
                .isEmpty();
        for (String label : List.of(" ", "x".repeat(129))) {
            assertThatThrownBy(() -> new EntityReportDataset.Projection("targetColor", "color", label))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void datasetMetadataCatalogHasAnExplicitPerRootBound() {
        var definitions = List.of(root(), target(false));
        var registry = new EntityRegistry(definitions);
        var declarations = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> new EntityReportDataset(
                        "example.dataset" + index,
                        ROOT,
                        "dataset",
                        "targetId",
                        List.of(new EntityReportDataset.Projection("targetColor", "color"))))
                .toList();
        assertThatThrownBy(() -> new EntityReportDatasets(
                        declarations,
                        definitions,
                        EntityGate.of(registry),
                        mock(EntityReads.class),
                        EntityScopes.withoutOrgUnits()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EntityReportDatasets resolver(
            EntityDefinition root, EntityDefinition target, EntityReportDataset dataset) {
        var entities = List.of(root, target);
        var registry = new EntityRegistry(entities);
        var reads = mock(EntityReads.class);
        when(reads.list(any())).thenAnswer(call -> EntityLists.queryList(call.getArgument(0)));
        return new EntityReportDatasets(
                List.of(dataset), entities, EntityGate.of(registry), reads, EntityScopes.withoutOrgUnits());
    }

    private static EntityReportDataset dataset(String key, String field) {
        return new EntityReportDataset(
                "example.dataset",
                ROOT,
                "dataset",
                "targetId",
                List.of(new EntityReportDataset.Projection(key, field)));
    }

    private static EntityDefinition root() {
        return Entity.define(ROOT, ROOT)
                .table("ex_requests", "p")
                .scope(EntityScope.owner("created_by"))
                .field(text("name", "name").column("subject").list(sortable()))
                .field(ref("targetId", "target")
                        .column("product_id")
                        .target(TARGET, "name")
                        .requires("md.settings", "update"))
                .section("main", "main", "name", "targetId")
                .defaultSort("name", Entity.Sort.ASC)
                .build();
    }

    private static EntityDefinition target(boolean partition) {
        var builder = Entity.define(TARGET, TARGET)
                .table(partition ? "studio_records" : "ex_products", "p")
                .scope(EntityScope.owner("created_by"))
                .field(text("name", "name").column("name").list(sortable()));
        if (partition)
            builder.partition("form_id", 19)
                    .field(bool("flag", "flag").jsonProperty("payload", "flag"))
                    .section("main", "main", "name", "flag");
        else
            builder.field(select("color", "color", List.of("red", "blue"), null)
                            .column("unit")
                            .requires("md.modules", "view"))
                    .field(number("amount", "amount").column("price_amount"))
                    .field(bool("expression", "expression").expression("true"))
                    .section("main", "main", "name", "color", "amount");
        return builder.defaultSort("name", Entity.Sort.ASC).archivable().build();
    }

    private static void signIn(Set<String> permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                7L, "viewer", "viewer@example.test", 1L, false, permissions, 1L, false, 1L, null));
    }
}
