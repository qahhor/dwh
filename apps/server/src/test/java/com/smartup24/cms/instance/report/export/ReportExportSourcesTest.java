package com.smartup24.cms.instance.report.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.entity.EntityRecordStore;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.LiveEntities;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryListExporter;
import com.smartup24.cms.instance.common.query.QueryListExporter.Detail;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Entity;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Module;
import com.smartup24.cms.instance.studio.service.StudioEntityCompiler;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The export of a live form follows the live catalog, never a copy taken at the start (ADR-0038). */
class ReportExportSourcesTest {

    @Test
    void aLiveFormServedAtTheStartExportsNothingOnceItStopsBeingServed() {
        Labels title = new Labels("Визиты", "Tashriflar", "Visits");
        var form = new StudioEntityCompiler()
                .compile(
                        7,
                        new Declaration(
                                1, "simple-form", new Module("studio", title), new Entity("visits", title), List.of()));
        var served =
                new AtomicReference<Map<String, EntityDefinitionSource.Declaration>>(Map.of("studio.visits", form));
        var live = new LiveEntities(() -> List.of(served::get));
        var beans = new StaticListableBeanFactory(Map.of("live", live));
        var registry = new EntityRegistry(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                beans.getBeanProvider(EntityRecordStore.class),
                beans.getBeanProvider(LiveEntities.class));
        QueryListExporter module = new QueryListExporter() {
            @Override
            public boolean visible(Set<String> ids) {
                return true;
            }

            @Override
            public String code() {
                return "md.module_list";
            }

            @Override
            public KeysetPage<?> page(
                    int limit, String cursor, String filter, String sort, String search, Map<String, String> options) {
                throw new UnsupportedOperationException();
            }
        };
        var sources = new ReportExportSources(List.of(module), registry);
        String listCode = form.entity().listCode();
        assertThat(sources.find(listCode)).isNotNull();
        assertThat(sources.find("md.module_list")).isSameAs(module);

        served.set(Map.of());
        assertThat(sources.find(listCode)).isNull();
        assertThat(sources.find("md.module_list")).isSameAs(module);
    }

    @Test
    void theRowsOfADocumentNameTheItemsOfTheirReferenceListAsTheyAreNow() {
        QueryField product = QueryField.enumeration("product", "goods", "l1.product", List.of("tea"), null);
        QueryField qty = QueryField.of("qty", "qty", QueryFieldType.NUMBER, "l1.qty");
        QueryListExporter orders = new QueryListExporter() {
            @Override
            public boolean visible(Set<String> ids) {
                return true;
            }

            @Override
            public String code() {
                return "studio.orders";
            }

            @Override
            public List<Detail> details() {
                return List.of(
                        new Detail("items", "lines", List.of(product, qty), Map.of("product", "studio.list.goods")));
            }

            @Override
            public KeysetPage<?> page(
                    int limit, String cursor, String filter, String sort, String search, Map<String, String> options) {
                throw new UnsupportedOperationException();
            }
        };
        EntityEnums enums = mock(EntityEnums.class);
        when(enums.items("studio.list.goods")).thenReturn(Map.of("tea", "Чай"));
        var registry = new EntityRegistry(List.of());

        List<QueryField> named = new ReportExportSources(List.of(orders), registry, enums)
                .details(orders)
                .getFirst()
                .fields();
        assertThat(named.getFirst().enumLabels()).containsEntry("tea", "Чай");
        assertThat(named.get(1)).isSameAs(qty);
        assertThat(new ReportExportSources(List.of(orders), registry)
                        .details(orders)
                        .getFirst()
                        .fields()
                        .getFirst())
                .isSameAs(product);
        Detail plain = new Detail("items", "lines", List.of(qty));
        assertThat(plain.named(code -> Map.of())).isSameAs(plain);
    }
}
