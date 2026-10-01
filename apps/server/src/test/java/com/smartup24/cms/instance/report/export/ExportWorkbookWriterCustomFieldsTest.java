package com.smartup24.cms.instance.report.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Custom fields in a list export (roadmap item 52): headed by their name, read from the row's attributes. */
class ExportWorkbookWriterCustomFieldsTest {

    @Test
    @DisplayName(
            "A custom field is headed by its name, read from attributes, and a value of the wrong shape never fails the file")
    void writesCustomFieldsFromAttributes() throws Exception {
        List<QueryField> fields = List.of(
                QueryField.of("name", "iam.users.col.name", QueryFieldType.TEXT, "u.name"),
                QueryField.custom("cfRegion", "Region", QueryFieldType.TEXT, "(a->>'region')", "region", List.of()),
                QueryField.custom("cfHired", "Hired", QueryFieldType.DATE, "(a->>'hired')", "hired", List.of()),
                QueryField.custom("cfRemote", "Remote", QueryFieldType.BOOLEAN, "(a->>'remote')", "remote", List.of()));
        var out = new ByteArrayOutputStream();
        try (var writer = new ExportWorkbookWriter(out, "Users", fields, key -> "[" + key + "]", "test", "1.0")) {
            writer.add(Map.of(
                    "name",
                    "Anna",
                    "attributes",
                    Map.of("region", "Tashkent", "hired", "2024-03-01", "remote", "true")));
            writer.add(Map.of("name", "Old", "attributes", Map.of("hired", "soon", "remote", "maybe")));
            writer.add(Map.of("name", "None"));
            assertThat(writer.rows()).isEqualTo(3);
        }

        String strings = entry(out.toByteArray(), "xl/sharedStrings.xml");
        // Non-ASCII text is XML-escaped in the file, so the test uses ASCII names.
        assertThat(strings)
                .contains("[iam.users.col.name]", "Region", "Hired", "Tashkent", "[common.yes]", "soon", "maybe");
    }

    /**
     * Plan 10/10, item 5.0: a moment is a date and time of the sheet (in UTC), declared or custom; a time of day is
     * written as it is; a custom value of the wrong shape stays text.
     */
    @Test
    void writesMomentsAndTimesOfDay() throws Exception {
        List<QueryField> fields = List.of(
                QueryField.of("startsAt", "x.col.starts_at", QueryFieldType.INSTANT, "t.starts_at"),
                QueryField.of("callTime", "x.col.call_time", QueryFieldType.TIME, "t.call_time"),
                QueryField.custom("cfDueAt", "Due", QueryFieldType.INSTANT, "(a->>'due_at')", "due_at", List.of()),
                QueryField.custom("cfSlot", "Slot", QueryFieldType.TIME, "(a->>'slot')", "slot", List.of()));
        var out = new ByteArrayOutputStream();
        try (var writer = new ExportWorkbookWriter(out, "Moments", fields, key -> "[" + key + "]", "test", "1.0")) {
            writer.add(Map.of(
                    "startsAt",
                    "2026-10-01T09:30:00Z",
                    "callTime",
                    "14:45",
                    "attributes",
                    Map.of("due_at", "2026-10-02T10:00:00+05:00", "slot", "08:15")));
            writer.add(Map.of("attributes", Map.of("due_at", "tomorrow")));
            assertThat(writer.rows()).isEqualTo(2);
        }

        String xml = entry(out.toByteArray(), "xl/worksheets/sheet1.xml");
        assertThat(xml).contains("14:45", "08:15", "tomorrow", "Due", "Slot");
        // 2026-10-01 09:30 UTC and 2026-10-02 05:00 UTC as sheet serial numbers, formatted as date and time.
        assertThat(xml).contains("46296.395833", "46297.208333", "dd.mm.yyyy hh:mm");
    }

    /** Every XML part of the workbook as text (fastexcel may keep strings shared or inline). */
    private static String entry(byte[] xlsx, String ignored) throws Exception {
        var file = java.nio.file.Files.createTempFile("export", ".xlsx");
        try {
            java.nio.file.Files.write(file, xlsx);
            try (var zip = new ZipFile(file.toFile())) {
                StringBuilder text = new StringBuilder();
                for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                    var entry = entries.nextElement();
                    if (entry.getName().endsWith(".xml")) {
                        text.append(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
                return text.toString();
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
