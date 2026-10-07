package com.smartup24.cms.instance.common.xlsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.xlsx.XlsxGuard.Limit;
import com.smartup24.cms.instance.common.xlsx.XlsxGuard.Rejected;
import com.smartup24.cms.instance.support.XlsxBombs;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

/** Plan 10/10, item 7.6: an xlsx out of its bounds is refused before any reader opens it. */
class XlsxGuardTest {

    private static final String SHEET = "xl/worksheets/sheet1.xml";
    private static final String STRINGS = "xl/sharedStrings.xml";

    @TempDir
    Path dir;

    @Test
    void aWorkbookOfTheWriterPasses() throws IOException {
        Path file = dir.resolve("plain.xlsx");
        try (OutputStream out = Files.newOutputStream(file);
                Workbook book = new Workbook(out, "TEST", "1.0")) {
            Worksheet sheet = book.newWorksheet("TEST");
            for (int row = 0; row < 1_000; row++) {
                sheet.value(row, 0, "TEST " + row);
                sheet.value(row, 1, row);
            }
        }

        assertThatCode(() -> XlsxGuard.check(file, XlsxLimits.defaults())).doesNotThrowAnyException();
    }

    @Test
    void aZipBombIsRefusedByItsRatioBeforeItIsInflatedWhole() throws IOException {
        Path bomb = XlsxBombs.bomb(dir.resolve("bomb.xlsx"), 256);

        assertThat(Files.size(bomb)).as("a small file").isLessThan(1024 * 1024);
        assertThatThrownBy(() -> XlsxGuard.check(bomb, XlsxLimits.defaults()))
                .isInstanceOfSatisfying(
                        Rejected.class, rejected -> assertThat(rejected.limit()).isEqualTo(Limit.COMPRESSION_RATIO));
    }

    @Test
    void aBinaryEntryIsCountedLikeAnXmlPart() throws IOException {
        Path file = XlsxBombs.zip(
                dir.resolve("media.xlsx"), Map.of(SHEET, sheet("<row r=\"1\"/>"), "xl/media/image1.bin", zeros(8)));

        assertThatThrownBy(() -> XlsxGuard.check(file, XlsxLimits.defaults()))
                .isInstanceOfSatisfying(
                        Rejected.class, rejected -> assertThat(rejected.limit()).isEqualTo(Limit.COMPRESSION_RATIO));
    }

    @Test
    void theUnpackedSizeIsBoundedWhateverTheRatio() throws IOException {
        Path file = XlsxBombs.zip(dir.resolve("big.xlsx"), Map.of("xl/media/image1.bin", zeros(3)));
        XlsxLimits limits = new XlsxLimits(DataSize.ofMegabytes(2), 0, 1_000_000, 0, null, 0, 0);

        assertThatThrownBy(() -> XlsxGuard.check(file, limits))
                .isInstanceOfSatisfying(
                        Rejected.class, rejected -> assertThat(rejected.limit()).isEqualTo(Limit.UNPACKED_SIZE));
    }

    @Test
    void theEntryCountIsBounded() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 11; i++) entries.put("xl/media/image" + i + ".bin", new byte[] {1});
        Path file = XlsxBombs.zip(dir.resolve("entries.xlsx"), entries);
        XlsxLimits limits = new XlsxLimits(null, 10, 0, 0, null, 0, 0);

        assertThatThrownBy(() -> XlsxGuard.check(file, limits))
                .isInstanceOfSatisfying(
                        Rejected.class, rejected -> assertThat(rejected.limit()).isEqualTo(Limit.ENTRIES));
    }

    @Test
    void sharedStringsAreBoundedByTheirDeclaredAndRealCount() throws IOException {
        XlsxLimits limits = new XlsxLimits(null, 0, 0, 3, null, 0, 0);
        Path declared = XlsxBombs.zip(
                dir.resolve("declared.xlsx"),
                Map.of(
                        STRINGS,
                        utf8("<sst xmlns=\"x\" count=\"1\" uniqueCount=\"2000000000\"><si><t>a</t></si></sst>")));
        Path real = XlsxBombs.zip(
                dir.resolve("real.xlsx"), Map.of(STRINGS, utf8("<sst>" + "<si><t>TEST</t></si>".repeat(4) + "</sst>")));

        for (Path file : new Path[] {declared, real}) {
            assertThatThrownBy(() -> XlsxGuard.check(file, limits))
                    .isInstanceOfSatisfying(
                            Rejected.class,
                            rejected -> assertThat(rejected.limit()).isEqualTo(Limit.SHARED_STRINGS));
        }
    }

    @Test
    void theSharedStringsPartIsBoundedBySize() throws IOException {
        Path file = XlsxBombs.zip(
                dir.resolve("long.xlsx"),
                Map.of(STRINGS, utf8("<sst><si><t>" + "TEST ".repeat(1_000) + "</t></si></sst>")));
        XlsxLimits limits = new XlsxLimits(null, 0, 0, 0, DataSize.ofBytes(1_024), 0, 0);

        assertThatThrownBy(() -> XlsxGuard.check(file, limits))
                .isInstanceOfSatisfying(
                        Rejected.class, rejected -> assertThat(rejected.limit()).isEqualTo(Limit.SHARED_STRINGS_SIZE));
    }

    @Test
    void rowsAndColumnsStayWithinTheSheet() throws IOException {
        Path farRow = XlsxBombs.zip(dir.resolve("row.xlsx"), Map.of(SHEET, sheet("<row r=\"1048577\"/>")));
        Path farColumn = XlsxBombs.zip(
                dir.resolve("col.xlsx"), Map.of(SHEET, sheet("<row r=\"1\"><c r=\"XFE1\"><v>1</v></c></row>")));
        Path absurdColumn = XlsxBombs.zip(
                dir.resolve("absurd.xlsx"),
                Map.of(SHEET, sheet("<row r=\"1\"><c r=\"ZZZZZZZZZZZZ1\"><v>1</v></c></row>")));
        Path limitedColumns =
                XlsxBombs.zip(dir.resolve("narrow.xlsx"), Map.of(SHEET, sheet("<row><c/><c/><c/></row>")));

        assertRejected(farRow, XlsxLimits.defaults(), Limit.ROWS);
        assertRejected(farColumn, XlsxLimits.defaults(), Limit.COLUMNS);
        assertRejected(absurdColumn, XlsxLimits.defaults(), Limit.COLUMNS);
        assertRejected(limitedColumns, new XlsxLimits(null, 0, 0, 0, null, 0, 2), Limit.COLUMNS);
        assertThatCode(() -> XlsxGuard.check(
                        XlsxBombs.zip(
                                dir.resolve("edge.xlsx"),
                                Map.of(SHEET, sheet("<row r=\"1048576\"><c r=\"XFD1048576\"/></row>"))),
                        XlsxLimits.defaults()))
                .doesNotThrowAnyException();
    }

    @Test
    void anEntityDeclarationIsNeverExpanded() throws IOException {
        String laughs = "<?xml version=\"1.0\"?><!DOCTYPE w [<!ENTITY a \"aaaaaaaaaa\">"
                + "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]><worksheet><sheetData>&b;</sheetData></worksheet>";
        Path file = XlsxBombs.zip(dir.resolve("laughs.xlsx"), Map.of(SHEET, utf8(laughs)));

        assertRejected(file, XlsxLimits.defaults(), Limit.MALFORMED_XML);
    }

    @Test
    void aFileThatIsNoZipIsRefused() throws IOException {
        Path file = Files.writeString(dir.resolve("text.xlsx"), "TEST not a zip");

        assertRejected(file, XlsxLimits.defaults(), Limit.NOT_A_ZIP);
    }

    @Test
    void theLimitsBindFromTheConfigurationAndFallBackToTheDefaults() {
        XlsxLimits bound = new Binder(new MapConfigurationPropertySource(Map.of(
                        "smc.uploads.xlsx.max-unpacked-size", "10MB",
                        "smc.uploads.xlsx.max-compression-ratio", "50",
                        "smc.uploads.xlsx.max-rows", "0")))
                .bind("smc.uploads.xlsx", XlsxLimits.class)
                .get();

        assertThat(bound.unpackedBytes()).isEqualTo(DataSize.ofMegabytes(10).toBytes());
        assertThat(bound.maxCompressionRatio()).isEqualTo(50);
        assertThat(bound.maxRows()).isEqualTo(XlsxLimits.DEFAULT_MAX_ROWS);
        assertThat(bound.maxEntries()).isEqualTo(XlsxLimits.DEFAULT_MAX_ENTRIES);
        assertThat(XlsxLimits.defaults().sharedStringsBytes())
                .isEqualTo(XlsxLimits.DEFAULT_MAX_SHARED_STRINGS_SIZE.toBytes());
    }

    private static void assertRejected(Path file, XlsxLimits limits, Limit limit) {
        assertThatThrownBy(() -> XlsxGuard.check(file, limits))
                .isInstanceOfSatisfying(
                        Rejected.class, rejected -> assertThat(rejected.limit()).isEqualTo(limit));
    }

    private static byte[] sheet(String rows) {
        return utf8("<worksheet xmlns=\"x\"><sheetData>" + rows + "</sheetData></worksheet>");
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zeros(int megabytes) {
        return new byte[megabytes * 1024 * 1024];
    }
}
