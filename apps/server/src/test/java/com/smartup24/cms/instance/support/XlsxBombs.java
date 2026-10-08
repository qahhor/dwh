package com.smartup24.cms.instance.support;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Hostile xlsx files written by the tests themselves (plan 10/10, item 7.6): the repository keeps no binary sample. A
 * bomb is a workbook of the regular parts whose sheet inflates to hundreds of megabytes of blanks from a file of a few
 * hundred kilobytes.
 */
public final class XlsxBombs {

    private static final String CONTENT_TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\""
            + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\""
            + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "</Types>";
    private static final String ROOT_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\""
            + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
            + " Target=\"xl/workbook.xml\"/></Relationships>";
    private static final String WORKBOOK = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + "<sheets><sheet name=\"TEST\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
    private static final String WORKBOOK_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\""
            + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
            + " Target=\"worksheets/sheet1.xml\"/></Relationships>";

    private XlsxBombs() {}

    /** A workbook whose only sheet inflates to {@code megabytes} MiB of blanks between its rows. */
    public static Path bomb(Path file, int megabytes) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            regularParts(zip);
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet"
                    + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                    + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>TEST</t></is></c></row>"));
            byte[] blanks = new byte[1024 * 1024];
            Arrays.fill(blanks, (byte) ' ');
            for (int i = 0; i < megabytes; i++) zip.write(blanks);
            zip.write(utf8("</sheetData></worksheet>"));
            zip.closeEntry();
        }
        return file;
    }

    /** A zip of exactly {@code entries}, deflated, in their iteration order. */
    public static Path zip(Path file, Map<String, byte[]> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    private static void regularParts(ZipOutputStream zip) throws IOException {
        Map<String, String> parts = Map.of(
                "[Content_Types].xml", CONTENT_TYPES,
                "_rels/.rels", ROOT_RELS,
                "xl/workbook.xml", WORKBOOK,
                "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
        for (Map.Entry<String, String> part : parts.entrySet()) {
            zip.putNextEntry(new ZipEntry(part.getKey()));
            zip.write(utf8(part.getValue()));
            zip.closeEntry();
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
