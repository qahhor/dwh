package com.smartup24.cms.instance.upl.mart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.units.api.Units;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Column;
import com.smartup24.cms.instance.upl.format.UplFormatModel.DataType;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FormatVersion;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Sheet;
import com.smartup24.cms.instance.upl.format.UplFormatModel.SourceSummary;
import com.smartup24.cms.instance.upl.format.UplFormatRepository;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import com.smartup24.cms.instance.warehouse.api.MartRow;
import com.smartup24.cms.instance.warehouse.api.RawRow;
import com.smartup24.cms.instance.warehouse.api.RawWriter;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoad;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoads;
import com.smartup24.cms.instance.warehouse.api.WarehouseMarts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Branch coverage of {@link UplMartBuilder}: eager publishing, lazy materialization and cell conversion. */
class UplMartBuilderCoverageTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 3, 1);

    private UplFormatRepository formats;
    private WarehouseLoads loads;
    private RawWriter raw;
    private WarehouseMarts marts;
    private UplMartBuilder builder;

    @BeforeEach
    void setUp() {
        formats = mock(UplFormatRepository.class);
        loads = mock(WarehouseLoads.class);
        raw = mock(RawWriter.class);
        marts = mock(WarehouseMarts.class);
        builder = new UplMartBuilder(formats, loads, raw, marts, mock(Units.class));
    }

    private static Column column(DataType type, boolean required, String mask, Integer padLength, Integer padMax) {
        return new Column(1L, 1, 1, "Field", "f", type, required, null, null, mask, padLength, padMax, null, List.of());
    }

    private static Column text() {
        return column(DataType.TEXT, false, null, null, null);
    }

    private static FormatVersion versionOf(Column... columns) {
        Sheet sheet = mock(Sheet.class);
        when(sheet.sheetName()).thenReturn("data");
        when(sheet.columns()).thenReturn(List.of(columns));
        FormatVersion version = mock(FormatVersion.class);
        when(version.sheets()).thenReturn(List.of(sheet));
        return version;
    }

    private List<MartRow> build(Column column, Object value) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("f", value);
        when(raw.read(7L)).thenReturn(List.of(new RawRow(1, "data", 2, fields)));
        return builder.buildMartRows(7L, PERIOD, versionOf(column));
    }

    private Object value(Column column, Object cell) {
        List<MartRow> rows = build(column, cell);
        assertThat(rows).hasSize(1);
        return rows.getFirst().fields().get("f");
    }

    private static PackageRow pkg(Long loadId, Integer rowsTotal) {
        PackageRow row = mock(PackageRow.class);
        when(row.loadId()).thenReturn(loadId);
        when(row.rowsTotal()).thenReturn(rowsTotal);
        when(row.sourceId()).thenReturn(5L);
        when(row.sourceCode()).thenReturn("sales");
        when(row.formatVersion()).thenReturn(2);
        when(row.periodFrom()).thenReturn(PERIOD);
        when(row.periodTo()).thenReturn(PERIOD.plusDays(30));
        when(row.publicId()).thenReturn(UUID.randomUUID());
        return row;
    }

    private static WarehouseLoad load(long id, String formatVersion) {
        return new WarehouseLoad(
                id, "sales", null, PERIOD, PERIOD.plusDays(30), formatVersion, null, null, 1, 1, 0, "applied", null);
    }

    @Test
    void eagerPublishIsSkippedForNullLoadNullTotalOrBigLoads() {
        builder.publishAfterApply(pkg(null, 10));
        builder.publishAfterApply(pkg(1L, null));
        builder.publishAfterApply(pkg(1L, UplMartBuilder.EAGER_ROW_LIMIT + 1));

        verify(marts, never()).publishLoad(any(), anyLong(), any(), any(), anyList(), anyList());
    }

    @Test
    void eagerPublishIsSkippedWithoutFormatVersion() {
        when(formats.findVersion(5L, 2)).thenReturn(Optional.empty());

        builder.publishAfterApply(pkg(1L, 10));

        verify(marts, never()).publishLoad(any(), anyLong(), any(), any(), anyList(), anyList());
    }

    @Test
    void eagerPublishPublishesNormalizedRows() {
        FormatVersion version = versionOf(text());
        when(formats.findVersion(5L, 2)).thenReturn(Optional.of(version));
        when(loads.appliedLoadIds("sales")).thenReturn(List.of(1L));
        when(raw.read(1L)).thenReturn(List.of(new RawRow(1, "data", 2, Map.of("f", "x"))));

        builder.publishAfterApply(pkg(1L, 10));

        verify(marts).publishLoad(eq("sales"), eq(1L), eq(PERIOD), eq(PERIOD.plusDays(30)), anyList(), eq(List.of(1L)));
    }

    @Test
    void eagerPublishFailureIsDeferredNotThrown() {
        when(formats.findVersion(5L, 2)).thenThrow(new IllegalStateException("db down"));

        builder.publishAfterApply(pkg(1L, 10));

        verify(marts, never()).publishLoad(any(), anyLong(), any(), any(), anyList(), anyList());
    }

    @Test
    void ensureMaterializedDoesNothingForNoLoads() {
        builder.ensureMaterialized(mock(SourceSummary.class), versionOf(), List.of());

        verify(marts, never()).materializedLoadIds(any(), anyList());
    }

    @Test
    void ensureMaterializedSkipsReadyAndMissingLoadsAndResolvesVersions() {
        SourceSummary source = mock(SourceSummary.class);
        when(source.id()).thenReturn(5L);
        when(source.code()).thenReturn("sales");
        FormatVersion fallback = versionOf(text());
        List<Long> active = List.of(1L, 2L, 3L, 4L, 5L, 6L);
        when(marts.materializedLoadIds("sales", active)).thenReturn(List.of(1L));
        when(loads.find(2L)).thenReturn(Optional.empty());
        when(loads.find(3L)).thenReturn(Optional.of(load(3L, "v3")));
        when(loads.find(4L)).thenReturn(Optional.of(load(4L, " ")));
        when(loads.find(5L)).thenReturn(Optional.of(load(5L, "abc")));
        when(loads.find(6L)).thenReturn(Optional.of(load(6L, "7")));
        FormatVersion v3 = versionOf(text());
        when(formats.findVersion(5L, 3)).thenReturn(Optional.of(v3));
        when(formats.findVersion(5L, 7)).thenReturn(Optional.empty());
        for (long id : new long[] {3, 4, 5, 6}) {
            when(raw.read(id)).thenReturn(List.of());
        }

        builder.ensureMaterialized(source, fallback, active);

        verify(raw, never()).read(1L);
        verify(raw, never()).read(2L);
        verify(formats).findVersion(5L, 3);
        verify(formats).findVersion(5L, 7);
        verify(marts, times(4)).publishLoad(eq("sales"), anyLong(), any(), any(), anyList(), eq(active));
    }

    @Test
    void firstSheetColumnsServeUnknownSheetsAndEmptyVersionsYieldBareRows() {
        FormatVersion empty = mock(FormatVersion.class);
        when(empty.sheets()).thenReturn(List.of());
        when(raw.read(7L)).thenReturn(List.of(new RawRow(1, "other", 2, null)));

        List<MartRow> rows = builder.buildMartRows(7L, PERIOD, empty);

        assertThat(rows)
                .singleElement()
                .satisfies(row -> assertThat(row.fields()).isEmpty());
    }

    @Test
    void requiredEmptyCellDropsTheRowOptionalEmptyCellIsSkipped() {
        Column required = column(DataType.TEXT, true, null, null, null);

        assertThat(build(required, "  ")).isEmpty();
        assertThat(build(required, null)).isEmpty();
        assertThat(build(text(), ""))
                .singleElement()
                .satisfies(row -> assertThat(row.fields()).isEmpty());
    }

    @Test
    void keysAreNormalizedPaddedAndMasked() {
        Column plain = column(DataType.OBJECT_KEY, false, null, null, null);
        assertThat(value(plain, "123.0")).isEqualTo("123");
        assertThat(value(plain, "007")).isEqualTo("007");
        assertThat(value(plain, "AB-1")).isEqualTo("AB-1");
        assertThat(build(plain, "12.5")).isEmpty();

        Column padded = column(DataType.OBJECT_KEY, false, null, 6, 4);
        assertThat(value(padded, "42")).isEqualTo("000042");
        assertThat(value(padded, "123456")).isEqualTo("123456");
        assertThat(value(padded, "7")).isEqualTo("7");
        Column paddedNoMax = column(DataType.OBJECT_KEY, false, null, 4, null);
        assertThat(value(paddedNoMax, "7")).isEqualTo("0007");

        Column masked = column(DataType.OBJECT_KEY, false, "\\d{4}", 4, null);
        assertThat(value(masked, "5")).isEqualTo("0005");
        assertThat(build(masked, "ABCD")).isEmpty();
    }

    @Test
    void integersRejectFractionsAndGarbage() {
        Column integer = column(DataType.INTEGER, false, null, null, null);
        assertThat(value(integer, "5.0")).isEqualTo(new BigDecimal("5"));
        assertThat(build(integer, "5.5")).isEmpty();
        assertThat(build(integer, "abc")).isEmpty();
    }

    @Test
    void numbersAcceptCommaAndRejectGarbage() {
        Column number = column(DataType.NUMBER, false, null, null, null);
        assertThat(value(number, "1,50")).isEqualTo(new BigDecimal("1.5"));
        assertThat(build(number, "x")).isEmpty();
    }

    @Test
    void datesAcceptIsoDottedAndExcelSerialValues() {
        Column date = column(DataType.DATE, false, null, null, null);
        assertThat(value(date, "2026-03-05")).isEqualTo("2026-03-05");
        assertThat(value(date, "05.03.2026")).isEqualTo("2026-03-05");
        assertThat(value(date, "46086"))
                .isEqualTo(LocalDate.of(1899, 12, 30).plusDays(46086).toString());
        assertThat(build(date, "2026-13-45")).isEmpty();
        assertThat(build(date, "tomorrow")).isEmpty();
    }

    @Test
    void textAndReferenceCodesAreKeptStripped() {
        assertThat(value(column(DataType.REF_CODE, false, null, null, null), " A1 "))
                .isEqualTo("A1");
    }
}
