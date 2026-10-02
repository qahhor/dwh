package com.smartup24.cms.instance.support.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.mf.api.StoredFile;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.support.TestSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.jspecify.annotations.Nullable;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/**
 * Import files for the tests of the import (ADR-0032, 10.1): the template's keys read back, a workbook in the layout of
 * the template (titles, hidden keys, data from the third row) made from values as the API takes them, stored in the
 * files module as its owner's upload, and an import run to its end — started, its job run, its journal read.
 */
public final class ImportFiles {

    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final WebApplicationContext wac;

    public ImportFiles(WebApplicationContext wac) {
        this.wac = wac;
    }

    /** The keys of the template's hidden second row, as {@code session} downloads it. */
    public List<String> templateKeys(TestSession session, String entity) throws Exception {
        MockHttpServletResponse template = session.send(
                get("/api/v1/entities/" + entity + "/import-template").param("lang", "en"));
        assertThat(template.getStatus()).as(template.getContentAsString()).isEqualTo(200);
        return rows(template.getContentAsByteArray()).get(1);
    }

    /** A workbook of {@code keys} and {@code rows}: each row's values by key, as the API takes them. */
    public static byte[] workbook(List<String> keys, List<Map<String, ?>> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Workbook book = new Workbook(out, "SmartupCMS", "1.0");
        Worksheet sheet = book.newWorksheet("data");
        for (int c = 0; c < keys.size(); c++) {
            sheet.value(0, c, keys.get(c));
            sheet.value(1, c, keys.get(c));
        }
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < keys.size(); c++) {
                cell(sheet, r + 2, c, rows.get(r).get(keys.get(c)));
            }
        }
        book.finish();
        return out.toByteArray();
    }

    /** A value as a person types it: a text, a number, a flag; money as its amount and currency; keys by commas. */
    private static void cell(Worksheet sheet, int row, int column, @Nullable Object value) {
        switch (value) {
            case null -> {}
            case Boolean flag -> sheet.value(row, column, flag);
            case Number number -> sheet.value(row, column, new BigDecimal(number.toString()));
            case Map<?, ?> money ->
                sheet.value(
                        row,
                        column,
                        money.get("amount") + (money.get("currency") == null ? "" : " " + money.get("currency")));
            case Collection<?> keys ->
                sheet.value(
                        row,
                        column,
                        String.join(", ", keys.stream().map(String::valueOf).toList()));
            default -> sheet.value(row, column, String.valueOf(value));
        }
    }

    /** Stores the workbook in the files module as {@code userId}'s upload; the id to import. */
    public UUID upload(long userId, byte[] content) {
        StoredFile stored = wac.getBean(MfFileService.class)
                .store("import.xlsx", XLSX, new ByteArrayInputStream(content), content.length, userId);
        return stored.id();
    }

    /** Starts an import of the file as {@code session}: the answer as it comes (202, 403, 404, 422…). */
    public static MockHttpServletResponse start(TestSession session, String entity, UUID fileId, String mode)
            throws Exception {
        return session.send(
                post("/api/v1/entities/" + entity + "/imports"),
                Map.of("fileId", fileId.toString(), "mode", mode, "lang", "en"));
    }

    /** Starts an import, runs its job and answers its journal row once it is finished. */
    public Map<String, Object> run(TestSession session, long userId, String entity, byte[] file, String mode)
            throws Exception {
        UUID fileId = upload(userId, file);
        MockHttpServletResponse started = start(session, entity, fileId, mode);
        assertThat(started.getStatus()).as(started.getContentAsString()).isEqualTo(202);
        String id = String.valueOf(TestSession.object(started).get("id"));
        assertThat(started.getHeader("Location")).isEqualTo("/api/v1/imports/" + id);
        wac.getBean(JobRunner.class).runQueued();
        MockHttpServletResponse journal = session.send(get("/api/v1/imports/" + id));
        assertThat(journal.getStatus()).as(journal.getContentAsString()).isEqualTo(200);
        Map<String, Object> row = TestSession.object(journal);
        assertThat(row.get("state")).as("the import is finished: %s", row).isIn("done", "failed");
        return row;
    }

    /** The addresses of the problems of a finished import's journal row. */
    public static List<String> errorFields(Map<String, Object> journal) {
        List<String> fields = new ArrayList<>();
        for (Object error : (List<?>) Objects.requireNonNull(journal.get("errors"))) {
            fields.add(String.valueOf(((Map<?, ?>) error).get("field")));
        }
        return fields;
    }

    /** The cells of a workbook's first sheet as texts, row by row. */
    public static List<List<String>> rows(byte[] content) throws IOException {
        try (ReadableWorkbook book = new ReadableWorkbook(new ByteArrayInputStream(content))) {
            List<List<String>> rows = new ArrayList<>();
            for (Row row : book.getFirstSheet().read()) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < row.getCellCount(); c++) {
                    cells.add(row.getCell(c) == null ? "" : Objects.requireNonNullElse(row.getCellText(c), ""));
                }
                rows.add(cells);
            }
            return rows;
        }
    }
}
