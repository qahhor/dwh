package com.smartup24.cms.instance.report.service;

import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.MdUserTexts;
import com.smartup24.cms.instance.report.repository.ReportRepository;
import com.smartup24.cms.instance.report.repository.ReportRepository.TaskExportRow;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exports the tasks a user may see as CSV or SpreadsheetML. The column names and priorities are catalog strings
 * ({@code report.task_export.*}) in the language of the user who exports.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    /** The catalog keys of the columns after the ID, in their order. */
    static final List<String> COLUMNS = List.of(
            "report.task_export.title",
            "report.task_export.project",
            "report.task_export.priority",
            "report.task_export.status",
            "report.task_export.deadline",
            "report.task_export.created_at",
            "report.task_export.author");

    static final String SHEET = "report.task_export.sheet";
    static final String PRIORITY = "report.task_export.priority_";

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("UTC"));

    private final ReportRepository reportRepository;
    private final MdScopeService scopeService;
    private final int maxExportRows;
    /** The catalog strings in the language of a user. */
    private final Function<Long, Map<String, String>> dictionaries;

    @Autowired
    public ReportService(
            ReportRepository reportRepository,
            MdScopeService scopeService,
            MdUserTexts texts,
            @Value("${smc.reports.export.max-rows:50000}") int maxExportRows) {
        this(reportRepository, scopeService, texts::dictionary, maxExportRows);
    }

    /** A service built by hand, with the strings of each user given directly. */
    public ReportService(
            ReportRepository reportRepository,
            MdScopeService scopeService,
            Function<Long, Map<String, String>> dictionaries,
            int maxExportRows) {
        this.reportRepository = reportRepository;
        this.scopeService = scopeService;
        this.dictionaries = dictionaries;
        this.maxExportRows = maxExportRows > 0 ? maxExportRows : ReportRepository.DEFAULT_MAX_EXPORT_ROWS;
    }

    public ReportService(
            JdbcClient jdbcClient, MdScopeService scopeService, Function<Long, Map<String, String>> dictionaries) {
        this(new ReportRepository(jdbcClient), scopeService, dictionaries, ReportRepository.DEFAULT_MAX_EXPORT_ROWS);
    }

    /** The strings of one export: column names and priorities in the language of the user who exports. */
    record Labels(Map<String, String> dictionary) {

        String text(String key) {
            return dictionary.getOrDefault(key, key);
        }

        List<String> columns() {
            return COLUMNS.stream().map(this::text).toList();
        }

        String priority(String code) {
            String known = code == null ? "medium" : code.toLowerCase(java.util.Locale.ROOT);
            return text(PRIORITY + (List.of("critical", "high", "low").contains(known) ? known : "medium"));
        }
    }

    public void exportTasksCsv(OutputStream outputStream, Long currentUserId) throws IOException {
        if (currentUserId == null) {
            throw ApiException.unauthorized("error.report.auth_required");
        }
        var scope = scopeService.filterForTasks(currentUserId);
        Labels labels = new Labels(dictionaries.apply(currentUserId));
        // UTF-8 BOM so Microsoft Excel automatically recognizes Russian UTF-8
        outputStream.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        java.io.BufferedWriter writer =
                new java.io.BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        writer.write("ID;"
                + String.join(
                        ";", labels.columns().stream().map(this::escapeCsv).toList()) + "\n");

        try {
            reportRepository.streamScopedTasks(scope, maxExportRows, row -> {
                try {
                    String title = escapeCsv(row.title());
                    String project = escapeCsv(row.projectName());
                    String priority = escapeCsv(labels.priority(row.priority()));
                    String status = escapeCsv(row.statusName());
                    String endTimeStr = row.endTime() != null ? DATE_FMT.format(row.endTime()) : "—";
                    String createdStr = row.createdAt() != null ? DATE_FMT.format(row.createdAt()) : "—";
                    String reporter = escapeCsv(row.reporterName());

                    writer.write(String.format(
                            "%d;%s;%s;%s;%s;%s;%s;%s%n",
                            row.id(), title, project, priority, status, endTimeStr, createdStr, reporter));
                } catch (IOException e) {
                    throw new ClientAbortException(e);
                }
            });
            writer.flush();
        } catch (ClientAbortException | IOException e) {
            // Client aborted or socket closed; terminate streaming and release DB connection cleanly
            log.debug("report_stream_aborted error={}", e.toString());
        }
    }

    /** The workbook's XML head, styles, columns and header row (SpreadsheetML 2003). */
    private void writeWorkbookHead(java.io.BufferedWriter writer, Labels labels) throws IOException {
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        writer.write("<?mso-application progid=\"Excel.Sheet\"?>\n");
        writer.write("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"\n");
        writer.write(" xmlns:o=\"urn:schemas-microsoft-com:office:office\"\n");
        writer.write(" xmlns:x=\"urn:schemas-microsoft-com:office:excel\"\n");
        writer.write(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"\n");
        writer.write(" xmlns:html=\"http://www.w3.org/TR/REC-html40\">\n");
        writer.write(" <Styles>\n");
        writer.write("  <Style ss:ID=\"Header\">\n");
        writer.write("   <Font ss:Bold=\"1\" ss:Color=\"#FFFFFF\"/>\n");
        writer.write("   <Interior ss:Color=\"#0284C7\" ss:Pattern=\"Solid\"/>\n");
        writer.write("   <Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\"/>\n");
        writer.write("  </Style>\n");
        writer.write("  <Style ss:ID=\"Row\">\n");
        writer.write("   <Alignment ss:Vertical=\"Center\"/>\n");
        writer.write("  </Style>\n");
        writer.write(" </Styles>\n");
        writer.write(" <Worksheet ss:Name=\"" + escapeXml(labels.text(SHEET)) + "\">\n");
        writer.write("  <Table>\n");
        writer.write("   <Column ss:Width=\"50\"/>\n");
        writer.write("   <Column ss:Width=\"220\"/>\n");
        writer.write("   <Column ss:Width=\"140\"/>\n");
        writer.write("   <Column ss:Width=\"90\"/>\n");
        writer.write("   <Column ss:Width=\"90\"/>\n");
        writer.write("   <Column ss:Width=\"110\"/>\n");
        writer.write("   <Column ss:Width=\"110\"/>\n");
        writer.write("   <Column ss:Width=\"130\"/>\n");

        // Header
        writer.write("   <Row ss:StyleID=\"Header\">\n");
        writer.write("    <Cell><Data ss:Type=\"String\">ID</Data></Cell>\n");
        for (String column : labels.columns()) {
            writer.write("    <Cell><Data ss:Type=\"String\">" + escapeXml(column) + "</Data></Cell>\n");
        }
        writer.write("   </Row>\n");
    }

    /** One task as a row: text escaped, dates formatted, an empty date as a dash. */
    private void writeTaskRow(java.io.BufferedWriter writer, TaskExportRow row, Labels labels) throws IOException {
        String title = escapeXml(row.title());
        String project = escapeXml(row.projectName());
        String priority = escapeXml(labels.priority(row.priority()));
        String status = escapeXml(row.statusName());
        String endTimeStr = row.endTime() != null ? DATE_FMT.format(row.endTime()) : "—";
        String createdStr = row.createdAt() != null ? DATE_FMT.format(row.createdAt()) : "—";
        String reporter = escapeXml(row.reporterName());

        writer.write("   <Row ss:StyleID=\"Row\">\n");
        writer.write(String.format("    <Cell><Data ss:Type=\"Number\">%d</Data></Cell>%n", row.id()));
        writer.write(String.format("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", title));
        writer.write(String.format("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", project));
        writer.write(String.format("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", priority));
        writer.write(String.format("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", status));
        writer.write(String.format("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", endTimeStr));
        writer.write(String.format("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", createdStr));
        writer.write(String.format("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", reporter));
        writer.write("   </Row>\n");
    }

    public void exportTasksExcelXml(OutputStream outputStream, Long currentUserId) throws IOException {
        if (currentUserId == null) {
            throw ApiException.unauthorized("error.report.auth_required");
        }
        var scope = scopeService.filterForTasks(currentUserId);
        Labels labels = new Labels(dictionaries.apply(currentUserId));
        java.io.BufferedWriter writer =
                new java.io.BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));

        try {
            writeWorkbookHead(writer, labels);

            reportRepository.streamScopedTasks(scope, maxExportRows, row -> {
                try {
                    writeTaskRow(writer, row, labels);
                } catch (IOException e) {
                    throw new ClientAbortException(e);
                }
            });

            writer.write("  </Table>\n");
            writer.write(" </Worksheet>\n");
            writer.write("</Workbook>\n");
            writer.flush();
        } catch (ClientAbortException | IOException e) {
            // Client aborted or socket closed; terminate streaming and release DB connection cleanly
            log.debug("report_stream_aborted error={}", e.toString());
        }
    }

    private static final class ClientAbortException extends RuntimeException {
        ClientAbortException(IOException cause) {
            super(cause);
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        boolean unsafePrefix = hasSpreadsheetPrefix(value);
        if (unsafePrefix) value = "'" + value;
        if (unsafePrefix
                || value.contains(";")
                || value.contains("\"")
                || value.contains("\n")
                || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private boolean hasSpreadsheetPrefix(String value) {
        // Inspect prefixes that importers may trim, but preserve the original text verbatim.
        // This protects initial CSV export, not later save/re-import transformations by spreadsheet clients.
        for (int offset = 0; offset < value.length(); ) {
            int ch = value.codePointAt(offset);
            if (ch == '='
                    || ch == '+'
                    || ch == '-'
                    || ch == '@'
                    || ch == '＝'
                    || ch == '＋'
                    || ch == '－'
                    || ch == '＠'
                    || ch == '\t'
                    || ch == '\r'
                    || ch == '\n') {
                return true;
            }
            if (!Character.isWhitespace(ch)
                    && !Character.isSpaceChar(ch)
                    && !Character.isISOControl(ch)
                    && Character.getType(ch) != Character.FORMAT) {
                return false;
            }
            offset += Character.charCount(ch);
        }
        return false;
    }

    private String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
