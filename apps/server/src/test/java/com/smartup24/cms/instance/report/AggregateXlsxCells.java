package com.smartup24.cms.instance.report;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Reads actual XLSX cell types and values independently from the exporter (ADR-0036). */
public final class AggregateXlsxCells {
    private AggregateXlsxCells() {}

    public record Cell(String type, String value) {}

    public static Map<String, Cell> data(byte[] bytes) throws Exception {
        Map<String, byte[]> parts = parts(bytes);
        List<String> strings = new ArrayList<>();
        byte[] shared = parts.get("xl/sharedStrings.xml");
        if (shared != null) {
            var nodes = parse(shared).getElementsByTagName("si");
            for (int i = 0; i < nodes.getLength(); i++)
                strings.add(nodes.item(i).getTextContent());
        }
        var nodes = parse(parts.get("xl/worksheets/sheet1.xml")).getElementsByTagName("c");
        Map<String, Cell> cells = new HashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            var cell = (Element) nodes.item(i);
            String type = cell.getAttribute("t");
            var values = cell.getElementsByTagName("v");
            String raw = values.getLength() == 0 ? "" : values.item(0).getTextContent();
            if (type.equals("s"))
                cells.put(cell.getAttribute("r"), new Cell("text", strings.get(Integer.parseInt(raw))));
            else if (type.equals("inlineStr"))
                cells.put(
                        cell.getAttribute("r"),
                        new Cell("text", cell.getElementsByTagName("is").item(0).getTextContent()));
            else cells.put(cell.getAttribute("r"), new Cell(type.equals("b") ? "boolean" : "number", raw));
            if (cell.getElementsByTagName("f").getLength() != 0)
                throw new AssertionError("Unexpected spreadsheet formula");
        }
        return cells;
    }

    public static List<String> sheetNames(byte[] bytes) throws Exception {
        var nodes = parse(parts(bytes).get("xl/workbook.xml")).getElementsByTagName("sheet");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) names.add(((Element) nodes.item(i)).getAttribute("name"));
        return names;
    }

    public static String xml(byte[] bytes) throws Exception {
        var text = new StringBuilder();
        for (var part : parts(bytes).entrySet()) {
            if (part.getKey().endsWith(".xml")) text.append(new String(part.getValue(), StandardCharsets.UTF_8));
        }
        return text.toString();
    }

    private static Map<String, byte[]> parts(byte[] bytes) throws Exception {
        var file = Files.createTempFile("aggregate-xlsx", ".xlsx");
        try {
            Files.write(file, bytes);
            Map<String, byte[]> parts = new HashMap<>();
            try (var zip = new ZipFile(file.toFile())) {
                for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                    var entry = entries.nextElement();
                    byte[] content;
                    try (var stream = zip.getInputStream(entry)) {
                        content = stream.readAllBytes();
                    }
                    if (content.length != entry.getSize()) throw new AssertionError("Invalid XLSX entry size");
                    var crc = new java.util.zip.CRC32();
                    crc.update(content);
                    if (crc.getValue() != entry.getCrc()) throw new AssertionError("Invalid XLSX entry checksum");
                    parts.put(entry.getName(), content);
                }
            }
            return parts;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static Document parse(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
}
