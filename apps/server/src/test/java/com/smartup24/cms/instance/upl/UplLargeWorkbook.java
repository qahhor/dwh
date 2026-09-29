package com.smartup24.cms.instance.upl;

import com.smartup24.cms.instance.upl.format.UplFormatModel.Column;
import com.smartup24.cms.instance.upl.format.UplFormatModel.DataType;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Periodicity;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Sheet;
import com.smartup24.cms.instance.upl.format.UplFormatModel.SourceData;
import com.smartup24.cms.instance.upl.format.UplSourceService;
import com.smartup24.cms.instance.upl.format.UplSourceService.DraftData;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * A large synthetic package file written straight to disk (plan 10/10, item 3.9): a sales-like sheet of seven columns
 * — numbers with many digits, a few repeated names — so a million rows weigh about the product limit of 50 MB. The
 * writer flushes every few thousand rows: the generator itself holds no rows either.
 */
final class UplLargeWorkbook {

    static final String SHEET = "TEST продажи";
    /** Every cell of a data row is filled: cells per row, for the cell limit. */
    static final int COLUMNS = 7;

    private static final int FLUSH_EVERY = 5_000;
    private static final int NAMES = 2_000;
    private static final List<String> HEADER = List.of("№", "Ключ", "Название", "Сумма", "Дата", "Количество", "Цена");

    private UplLargeWorkbook() {}

    /** Publishes a source whose format reads this file; returns the source id. */
    static long publishedSource(UplSourceService service, long userId, LocalDate validFrom) {
        SourceData data = new SourceData(
                "test.large." + UUID.randomUUID().toString().substring(0, 8),
                "TEST large source",
                "TEST org",
                null,
                Periodicity.MONTH,
                5,
                null,
                null);
        long sourceId = service.createSource(data, userId).source().id();
        int version = service.createDraft(sourceId, null, userId).version();
        int lockVersion = service.getVersion(sourceId, version).lockVersion();
        service.replaceDraft(sourceId, version, lockVersion, draft(), userId);
        service.publish(sourceId, version, validFrom, userId);
        return sourceId;
    }

    private static DraftData draft() {
        List<Column> columns = List.of(
                column(1, "№", "row_no", DataType.INTEGER, true),
                new Column(
                        null,
                        0,
                        2,
                        "Ключ",
                        "object_key",
                        DataType.OBJECT_KEY,
                        true,
                        null,
                        null,
                        "^[0-9]{9}$",
                        9,
                        1,
                        null),
                column(3, "Название", "org_name", DataType.TEXT, false),
                column(4, "Сумма", "amount", DataType.NUMBER, false),
                column(5, "Дата", "doc_date", DataType.DATE, false),
                column(6, "Количество", "quantity", DataType.NUMBER, false),
                column(7, "Цена", "price", DataType.NUMBER, false));
        return new DraftData(null, null, null, null, List.of(new Sheet(null, 0, SHEET, 1, null, columns)));
    }

    /** Writes {@code rows} data rows under a header in the first row; the same seed gives the same file. */
    static Path write(Path file, int rows) {
        SplittableRandom random = new SplittableRandom(rows);
        try (OutputStream out = Files.newOutputStream(file);
                Workbook book = new Workbook(out, "TEST", "1.0")) {
            Worksheet sheet = book.newWorksheet(SHEET);
            for (int column = 0; column < HEADER.size(); column++) {
                sheet.value(0, column, HEADER.get(column));
            }
            for (int row = 1; row <= rows; row++) {
                sheet.value(row, 0, row);
                sheet.value(row, 1, 100_000_000 + random.nextInt(900_000_000));
                sheet.value(row, 2, "TEST орг " + random.nextInt(NAMES));
                sheet.value(row, 3, cents(random.nextDouble() * 100_000));
                sheet.value(
                        row, 4, LocalDate.of(2026, 3, 1 + random.nextInt(31)).toString());
                sheet.value(row, 5, random.nextInt(1_000));
                sheet.value(row, 6, cents(random.nextDouble() * 1_000));
                if (row % FLUSH_EVERY == 0) {
                    sheet.flush();
                }
            }
            sheet.finish();
        } catch (IOException failure) {
            throw new UncheckedIOException("Не удалось записать большой тестовый xlsx", failure);
        }
        return file;
    }

    /** Money and shares with two decimals, as in the files people send: full-precision doubles do not compress. */
    private static double cents(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static Column column(int position, String nameInFile, String targetField, DataType type, boolean required) {
        return new Column(
                null, 0, position, nameInFile, targetField, type, required, null, null, null, null, null, null);
    }
}
