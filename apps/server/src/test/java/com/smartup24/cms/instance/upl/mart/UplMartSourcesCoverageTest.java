package com.smartup24.cms.instance.upl.mart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityDefinitionSource.Declaration;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryAggregate;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Column;
import com.smartup24.cms.instance.upl.format.UplFormatModel.DataType;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FormatVersion;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Sheet;
import com.smartup24.cms.instance.upl.format.UplFormatModel.SourceSummary;
import com.smartup24.cms.instance.upl.format.UplFormatRepository;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoads;
import com.smartup24.cms.instance.warehouse.api.WarehouseMarts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Snapshot, lookup and naming rules of {@link UplMartSources}, and the guards of {@link UplMartReportRunner}. */
class UplMartSourcesCoverageTest {

    private final UplFormatRepository formats = mock(UplFormatRepository.class);
    private final UplMartSources sources = new UplMartSources(formats);

    private static SourceSummary source(long id, String code, Integer published) {
        SourceSummary source = mock(SourceSummary.class);
        when(source.id()).thenReturn(id);
        when(source.code()).thenReturn(code);
        when(source.name()).thenReturn("Name " + code);
        when(source.lastPublishedVersion()).thenReturn(published);
        return source;
    }

    private static Column column(String target, DataType type, String baseUnit) {
        return new Column(
                1L, 1, 1, "File " + target, target, type, false, null, baseUnit, null, null, null, null, List.of());
    }

    private static FormatVersion version(Column... columns) {
        Sheet sheet = mock(Sheet.class);
        when(sheet.columns()).thenReturn(List.of(columns));
        FormatVersion version = mock(FormatVersion.class);
        when(version.sheets()).thenReturn(List.of(sheet));
        return version;
    }

    @Test
    void snapshotCompilesPublishedSourcesAndSkipsTheRest() {
        SourceSummary draft = source(1, "draft", null);
        SourceSummary missing = source(2, "missing", 1);
        SourceSummary broken = source(3, "broken", 1);
        SourceSummary good = source(4, "sales-2026", 1);
        when(formats.findPublishedSummaries()).thenReturn(List.of(draft, missing, broken, good));
        when(formats.findVersion(2, 1)).thenReturn(Optional.empty());
        FormatVersion bad = mock(FormatVersion.class);
        when(bad.sheets()).thenThrow(new IllegalStateException("bad"));
        when(formats.findVersion(3, 1)).thenReturn(Optional.of(bad));
        FormatVersion goodVersion = version(
                column("qty_total", DataType.NUMBER, "pcs"),
                column("qty_total", DataType.NUMBER, null),
                column("count", DataType.INTEGER, null),
                column("day", DataType.DATE, null),
                column("period_from", DataType.TEXT, null),
                column("code", DataType.OBJECT_KEY, null));
        when(formats.findVersion(4, 1)).thenReturn(Optional.of(goodVersion));

        Map<String, Declaration> snapshot = sources.snapshot();

        assertThat(snapshot).containsOnlyKeys("mart.sales_2026");
    }

    @Test
    void snapshotSurvivesAnUnavailableRepository() {
        when(formats.findPublishedSummaries()).thenThrow(new IllegalStateException("down"));

        assertThat(sources.snapshot()).isEmpty();
    }

    @Test
    void martCodesResolveBackToThePublishedSource() {
        SourceSummary draft = source(1, "sales.2026", null);
        SourceSummary good = source(2, "sales.2026", 3);
        when(formats.findPublishedSummaries()).thenReturn(List.of(draft, good));
        FormatVersion version = version();
        when(formats.findVersion(2, 3)).thenReturn(Optional.of(version));

        assertThat(sources.findByMartCode("mart.sales.s_2026")).get().satisfies(resolved -> {
            assertThat(resolved.source()).isSameAs(good);
            assertThat(resolved.version()).isSameAs(version);
        });
        assertThat(sources.findByMartCode("mart.unknown")).isEmpty();
        assertThat(sources.findByMartCode("other.x")).isEmpty();
        assertThat(sources.findByMartCode(null)).isEmpty();
    }

    @Test
    void martCodeNormalizesSourceCodes() {
        assertThat(UplMartSources.martCode(" Sales-2026.Q1 ")).isEqualTo("mart.sales_2026.q1");
        assertThat(UplMartSources.martCode("1a.b")).isEqualTo("mart.s_1a.b");
        assertThat(UplMartSources.martCode("a..b")).isEqualTo("mart.a.b");
        assertThat(UplMartSources.martCode("a.b!c")).isEqualTo("mart.a.b_c");
        assertThat(UplMartSources.martCode(".")).isEqualTo("mart.source");
    }

    @Test
    void fieldKeysAreCamelCasedAndGuarded() {
        assertThat(UplMartSources.toFieldKey("qty_total")).isEqualTo("qtyTotal");
        assertThat(UplMartSources.toFieldKey("__")).isEqualTo("field");
        assertThat(UplMartSources.toFieldKey("1st")).isEqualTo("f1st");
        assertThat(UplMartSources.toFieldKey("period_from")).isEqualTo("colPeriodFrom");
        assertThat(UplMartSources.toFieldKey("a".repeat(80))).hasSize(64);
    }

    @Test
    void reportRunnerSupportsOnlyMartEntitiesAndRejectsUnknownOnes() {
        UplMartSources runnerSources = mock(UplMartSources.class);
        UplMartReportRunner runner = new UplMartReportRunner(
                runnerSources, mock(UplMartBuilder.class), mock(WarehouseLoads.class), mock(WarehouseMarts.class));
        when(runnerSources.findByMartCode("mart.none")).thenReturn(Optional.empty());

        assertThat(runner.supports("mart.sales")).isTrue();
        assertThat(runner.supports("other")).isFalse();
        assertThat(runner.supports(null)).isFalse();
        QueryAggregate aggregate = mock(QueryAggregate.class);
        QueryPlan.SqlFragment extra = new QueryPlan.SqlFragment("", Map.of());
        assertThatThrownBy(() -> runner.aggregate("mart.none", aggregate, extra))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void reportRunnerMaterializesOnlyWhenLoadsAreActive() {
        UplMartSources runnerSources = mock(UplMartSources.class);
        WarehouseLoads loads = mock(WarehouseLoads.class);
        WarehouseMarts marts = mock(WarehouseMarts.class);
        UplMartBuilder martBuilder = mock(UplMartBuilder.class);
        UplMartReportRunner runner = new UplMartReportRunner(runnerSources, martBuilder, loads, marts);
        SourceSummary summary = source(1, "sales", 1);
        FormatVersion version = version();
        when(runnerSources.findByMartCode("mart.sales"))
                .thenReturn(Optional.of(new UplMartSources.ResolvedSource(summary, version)));
        QueryAggregate aggregate = mock(QueryAggregate.class);
        QueryPlan.SqlFragment extra = new QueryPlan.SqlFragment("", Map.of());

        when(loads.appliedLoadIds("sales")).thenReturn(List.of());
        runner.aggregate("mart.sales", aggregate, extra);
        verify(martBuilder, never()).ensureMaterialized(any(), any(), anyList());

        when(loads.appliedLoadIds("sales")).thenReturn(List.of(9L));
        runner.aggregate("mart.sales", aggregate, extra);
        verify(martBuilder).ensureMaterialized(summary, version, List.of(9L));
        verify(marts).aggregate("sales", List.of(9L), aggregate, extra);
    }
}
