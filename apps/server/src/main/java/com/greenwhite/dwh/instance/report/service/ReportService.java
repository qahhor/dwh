package com.greenwhite.dwh.instance.report.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
@Transactional(readOnly = true)
public class ReportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("UTC"));

    private final JdbcClient jdbcClient;

    public ReportService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void exportTasksCsv(OutputStream outputStream) throws IOException {
        // UTF-8 BOM so Microsoft Excel automatically recognizes Russian UTF-8
        outputStream.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        writer.println("ID;Заголовок;Проект;Приоритет;Статус;Срок;Дата создания;Автор");

        jdbcClient.sql("""
                select
                    t.id,
                    t.title,
                    coalesce(p.name, '—') as project_name,
                    t.priority,
                    coalesce(s.name, 'Новая') as status_name,
                    t.end_time,
                    t.created_at,
                    coalesce(u.name, '—') as reporter_name
                from ms_tasks t
                left join ms_task_projects p on p.id = t.project_id
                left join ms_task_statuses s on s.id = t.status_id
                left join md_users u on u.id = t.reporter_id
                order by t.id desc
                """)
                .query((rs) -> {
                    long id = rs.getLong("id");
                    String title = escapeCsv(rs.getString("title"));
                    String project = escapeCsv(rs.getString("project_name"));
                    String priority = mapPriority(rs.getString("priority"));
                    String status = escapeCsv(rs.getString("status_name"));
                    var endTime = rs.getTimestamp("end_time");
                    var createdAt = rs.getTimestamp("created_at");
                    String reporter = escapeCsv(rs.getString("reporter_name"));

                    String endTimeStr = endTime != null ? DATE_FMT.format(endTime.toInstant()) : "—";
                    String createdStr = createdAt != null ? DATE_FMT.format(createdAt.toInstant()) : "—";

                    writer.printf("%d;%s;%s;%s;%s;%s;%s;%s%n",
                            id, title, project, priority, status, endTimeStr, createdStr, reporter);
                });

        writer.flush();
    }

    public void exportTasksExcelXml(OutputStream outputStream) throws IOException {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));

        writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        writer.println("<?mso-application progid=\"Excel.Sheet\"?>");
        writer.println("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"");
        writer.println(" xmlns:o=\"urn:schemas-microsoft-com:office:office\"");
        writer.println(" xmlns:x=\"urn:schemas-microsoft-com:office:excel\"");
        writer.println(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\"");
        writer.println(" xmlns:html=\"http://www.w3.org/TR/REC-html40\">");
        writer.println(" <Styles>");
        writer.println("  <Style ss:ID=\"Header\">");
        writer.println("   <Font ss:Bold=\"1\" ss:Color=\"#FFFFFF\"/>");
        writer.println("   <Interior ss:Color=\"#0284C7\" ss:Pattern=\"Solid\"/>");
        writer.println("   <Alignment ss:Horizontal=\"Center\" ss:Vertical=\"Center\"/>");
        writer.println("  </Style>");
        writer.println("  <Style ss:ID=\"Row\">");
        writer.println("   <Alignment ss:Vertical=\"Center\"/>");
        writer.println("  </Style>");
        writer.println(" </Styles>");
        writer.println(" <Worksheet ss:Name=\"Задачи\">");
        writer.println("  <Table>");
        writer.println("   <Column ss:Width=\"50\"/>");
        writer.println("   <Column ss:Width=\"220\"/>");
        writer.println("   <Column ss:Width=\"140\"/>");
        writer.println("   <Column ss:Width=\"90\"/>");
        writer.println("   <Column ss:Width=\"90\"/>");
        writer.println("   <Column ss:Width=\"110\"/>");
        writer.println("   <Column ss:Width=\"110\"/>");
        writer.println("   <Column ss:Width=\"130\"/>");

        // Header
        writer.println("   <Row ss:StyleID=\"Header\">");
        writer.println("    <Cell><Data ss:Type=\"String\">ID</Data></Cell>");
        writer.println("    <Cell><Data ss:Type=\"String\">Заголовок</Data></Cell>");
        writer.println("    <Cell><Data ss:Type=\"String\">Проект</Data></Cell>");
        writer.println("    <Cell><Data ss:Type=\"String\">Приоритет</Data></Cell>");
        writer.println("    <Cell><Data ss:Type=\"String\">Статус</Data></Cell>");
        writer.println("    <Cell><Data ss:Type=\"String\">Срок</Data></Cell>");
        writer.println("    <Cell><Data ss:Type=\"String\">Дата создания</Data></Cell>");
        writer.println("    <Cell><Data ss:Type=\"String\">Автор</Data></Cell>");
        writer.println("   </Row>");

        jdbcClient.sql("""
                select
                    t.id,
                    t.title,
                    coalesce(p.name, '—') as project_name,
                    t.priority,
                    coalesce(s.name, 'Новая') as status_name,
                    t.end_time,
                    t.created_at,
                    coalesce(u.name, '—') as reporter_name
                from ms_tasks t
                left join ms_task_projects p on p.id = t.project_id
                left join ms_task_statuses s on s.id = t.status_id
                left join md_users u on u.id = t.reporter_id
                order by t.id desc
                """)
                .query((rs) -> {
                    long id = rs.getLong("id");
                    String title = escapeXml(rs.getString("title"));
                    String project = escapeXml(rs.getString("project_name"));
                    String priority = mapPriority(rs.getString("priority"));
                    String status = escapeXml(rs.getString("status_name"));
                    var endTime = rs.getTimestamp("end_time");
                    var createdAt = rs.getTimestamp("created_at");
                    String reporter = escapeXml(rs.getString("reporter_name"));

                    String endTimeStr = endTime != null ? DATE_FMT.format(endTime.toInstant()) : "—";
                    String createdStr = createdAt != null ? DATE_FMT.format(createdAt.toInstant()) : "—";

                    writer.println("   <Row ss:StyleID=\"Row\">");
                    writer.printf("    <Cell><Data ss:Type=\"Number\">%d</Data></Cell>%n", id);
                    writer.printf("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", title);
                    writer.printf("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", project);
                    writer.printf("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", priority);
                    writer.printf("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", status);
                    writer.printf("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", endTimeStr);
                    writer.printf("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", createdStr);
                    writer.printf("    <Cell><Data ss:Type=\"String\">%s</Data></Cell>%n", reporter);
                    writer.println("   </Row>");
                });

        writer.println("  </Table>");
        writer.println(" </Worksheet>");
        writer.println("</Workbook>");
        writer.flush();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String mapPriority(String p) {
        if (p == null) return "Средний";
        return switch (p.toLowerCase()) {
            case "critical" -> "Критический";
            case "high" -> "Высокий";
            case "low" -> "Низкий";
            default -> "Средний";
        };
    }
}
