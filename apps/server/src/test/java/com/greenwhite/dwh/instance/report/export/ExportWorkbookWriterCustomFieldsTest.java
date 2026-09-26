package com.greenwhite.dwh.instance.report.export;

import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.common.query.QueryFieldType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Custom fields in a list export (roadmap item 52): headed by their name, read from the row's attributes. */
class ExportWorkbookWriterCustomFieldsTest {

    @Test
    @DisplayName("A custom field is headed by its name, read from attributes, and a value of the wrong shape never fails the file")
    void writesCustomFieldsFromAttributes() throws Exception {
        List<QueryField> fields = List.of(
                QueryField.of("name", "iam.users.col.name", QueryFieldType.TEXT, "u.name"),
                QueryField.custom("cfRegion", "Region", QueryFieldType.TEXT, "(a->>'region')", "region", List.of()),
                QueryField.custom("cfHired", "Hired", QueryFieldType.DATE, "(a->>'hired')", "hired", List.of()),
                QueryField.custom("cfRemote", "Remote", QueryFieldType.BOOLEAN, "(a->>'remote')", "remote", List.of()));
        var out = new ByteArrayOutputStream();
        try (var writer = new ExportWorkbookWriter(out, "Users", fields, key -> "[" + key + "]", "test", "1.0")) {
            writer.add(Map.of("name", "Anna", "attributes", Map.of("region", "Tashkent", "hired", "2024-03-01", "remote", "true")));
            writer.add(Map.of("name", "Old", "attributes", Map.of("hired", "soon", "remote", "maybe")));
            writer.add(Map.of("name", "None"));
            assertThat(writer.rows()).isEqualTo(3);
        }

        String strings = entry(out.toByteArray(), "xl/sharedStrings.xml");
        // Non-ASCII text is XML-escaped in the file, so the test uses ASCII names.
        assertThat(strings).contains("[iam.users.col.name]", "Region", "Hired", "Tashkent", "[common.yes]", "soon", "maybe");
    }

    /** Every XML part of the workbook as text (fastexcel may keep strings shared or inline). */
    private static String entry(byte[] xlsx, String ignored) throws Exception {
        var file = java.nio.file.Files.createTempFile("export", ".xlsx");
        try {
            java.nio.file.Files.write(file, xlsx);
            try (var zip = new java.util.zip.ZipFile(file.toFile())) {
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
