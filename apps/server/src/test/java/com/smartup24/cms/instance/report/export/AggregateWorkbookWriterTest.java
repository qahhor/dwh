package com.smartup24.cms.instance.report.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateGroup;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateMeasure;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateRow;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.report.AggregateXlsxCells;
import com.smartup24.cms.instance.report.AggregateXlsxCells.Cell;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AggregateWorkbookWriterTest {
    private static final QueryList LIST = new QueryList(
            "test.rows",
            "test.rows",
            "view",
            "r.id",
            "rows r",
            "r.id",
            List.of(
                    QueryField.of("id", "ID", QueryFieldType.NUMBER, "r.id").asSortable(),
                    QueryField.of("createdAt", "Date", QueryFieldType.INSTANT, "r.created_at"),
                    QueryField.of("totalCurrency", "Currency", QueryFieldType.TEXT, "r.currency"),
                    QueryField.of("total", "Total", QueryFieldType.NUMBER, "r.total")),
            "id");

    @Test
    void preservesRawRowsCurrenciesNullAndPrecisionWithoutFormulas() throws Exception {
        var result = new QueryAggregateResult(
                List.of(new AggregateGroup("id", null, false), new AggregateGroup("totalCurrency", null, true)),
                List.of(new AggregateMeasure("sum", "total"), new AggregateMeasure("avg", "total")),
                List.of(
                        new AggregateRow(
                                List.of(9007199254740993L, "=SUM(A1:A2)"),
                                List.of(new BigDecimal("1234567890123456.78"), new BigDecimal("1.25"))),
                        new AggregateRow(Arrays.asList(null, "UZS"), Arrays.asList(null, new BigDecimal("0")))),
                true);
        byte[] xlsx = AggregateWorkbookWriter.write("test.rows", LIST, result, key -> key);
        var cells = AggregateXlsxCells.data(xlsx);
        String all = AggregateXlsxCells.xml(xlsx);
        assertThat(cells)
                .containsEntry("A2", new Cell("text", "9007199254740993"))
                .containsEntry("C2", new Cell("text", "1234567890123456.78"))
                .containsEntry("B2", new Cell("text", "=SUM(A1:A2)"))
                .containsEntry("D2", new Cell("number", "1.25"))
                .doesNotContainKeys("A3", "C3");
        assertThat(all)
                .contains(
                        "9007199254740993",
                        "1234567890123456.78",
                        "=SUM(A1:A2)",
                        "UZS",
                        "truncated",
                        "true",
                        "NULL",
                        "UTC",
                        "totalCurrency");
    }

    @Test
    void retainsUtcBucketsAndEmptyResultHeaders() throws Exception {
        var result = new QueryAggregateResult(
                List.of(new AggregateGroup("createdAt", "month", false)),
                List.of(new AggregateMeasure("count", null)),
                List.of(),
                false);
        String all = AggregateXlsxCells.xml(AggregateWorkbookWriter.write("test.rows", LIST, result, key -> key));
        assertThat(all).contains("Date", "month", "count", "false", "UTC");
    }

    @Test
    void customTranslationsCannotMakeInvalidSheetNames() throws Exception {
        var result =
                new QueryAggregateResult(List.of(), List.of(new AggregateMeasure("count", null)), List.of(), false);
        var names = AggregateXlsxCells.sheetNames(AggregateWorkbookWriter.write(
                "test.rows",
                LIST,
                result,
                key -> key.startsWith("report.aggregate.") ? "[]:*?/\\" + "Title".repeat(10) : key));
        assertThat(names).hasSize(2).doesNotHaveDuplicates();
        for (String name : names)
            assertThat(name)
                    .isNotBlank()
                    .hasSizeLessThanOrEqualTo(31)
                    .doesNotContain("[", "]", ":", "*", "?", "/", "\\");
    }

    @Test
    void identicalSheetTranslationsRemainUnique() throws Exception {
        var result =
                new QueryAggregateResult(List.of(), List.of(new AggregateMeasure("count", null)), List.of(), false);
        var names = AggregateXlsxCells.sheetNames(AggregateWorkbookWriter.write(
                "test.rows", LIST, result, key -> key.startsWith("report.aggregate.") ? "Metadata" : key));
        assertThat(names).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void sheetNamesAreUniqueIgnoringCase() throws Exception {
        var result =
                new QueryAggregateResult(List.of(), List.of(new AggregateMeasure("count", null)), List.of(), false);
        var names = AggregateXlsxCells.sheetNames(AggregateWorkbookWriter.write(
                "test.rows", LIST, result, key -> key.equals("report.aggregate.sheet") ? "Report" : "report"));
        assertThat(names).hasSize(2);
        assertThat(names.get(0)).isNotEqualToIgnoringCase(names.get(1));
    }

    @Test
    void sheetNamesDoNotRetainEdgeApostrophesOrReservedHistory() throws Exception {
        var result =
                new QueryAggregateResult(List.of(), List.of(new AggregateMeasure("count", null)), List.of(), false);
        for (String translation : List.of("'Report", "Report'", "History", "'", " ", "' 'Report' '")) {
            var names = AggregateXlsxCells.sheetNames(AggregateWorkbookWriter.write(
                    "test.rows", LIST, result, key -> key.startsWith("report.aggregate.") ? translation : key));
            for (String name : names) {
                assertThat(name)
                        .isNotBlank()
                        .doesNotStartWith("'")
                        .doesNotEndWith("'")
                        .isNotEqualToIgnoringCase("History");
            }
        }
    }
}
