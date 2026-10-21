package com.smartup24.cms.instance.upl.mart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.units.api.CoefficientMissingException;
import com.smartup24.cms.instance.units.api.Units;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Column;
import com.smartup24.cms.instance.upl.format.UplFormatModel.DataType;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FormatVersion;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Sheet;
import com.smartup24.cms.instance.upl.format.UplFormatRepository;
import com.smartup24.cms.instance.warehouse.api.MartRow;
import com.smartup24.cms.instance.warehouse.api.RawRow;
import com.smartup24.cms.instance.warehouse.api.RawWriter;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoads;
import com.smartup24.cms.instance.warehouse.api.WarehouseMarts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-0048: a unit without a coefficient keeps the value as loaded and is logged, never dropped silently. */
class UplMartBuilderUnitTest {

    private static final LocalDate PERIOD = LocalDate.of(2026, 3, 1);

    @Test
    void aMissingCoefficientKeepsTheLoadedValue() {
        Column quantity = mock(Column.class);
        when(quantity.targetField()).thenReturn("qty");
        when(quantity.dataType()).thenReturn(DataType.NUMBER);
        when(quantity.sourceUnit()).thenReturn("box");
        when(quantity.baseUnit()).thenReturn("pcs");
        Sheet sheet = mock(Sheet.class);
        when(sheet.sheetName()).thenReturn("data");
        when(sheet.columns()).thenReturn(List.of(quantity));
        FormatVersion version = mock(FormatVersion.class);
        when(version.sheets()).thenReturn(List.of(sheet));
        RawWriter raw = mock(RawWriter.class);
        when(raw.read(7L)).thenReturn(List.of(new RawRow(1, "data", 2, Map.of("qty", "12.50"))));
        Units units = mock(Units.class);
        when(units.toBase(any(), any(), any())).thenThrow(new CoefficientMissingException("box", "pcs", PERIOD));
        UplMartBuilder builder = new UplMartBuilder(
                mock(UplFormatRepository.class), mock(WarehouseLoads.class), raw, mock(WarehouseMarts.class), units);

        List<MartRow> rows = builder.buildMartRows(7L, PERIOD, version);

        assertThat(rows)
                .singleElement()
                .satisfies(row -> assertThat(row.fields()).containsEntry("qty", new BigDecimal("12.5")));
    }
}
