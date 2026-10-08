package com.smartup24.cms.instance.common.xlsx;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jspecify.annotations.Nullable;

/**
 * Checks an xlsx workbook on disk against {@link XlsxLimits} before a reader opens it (plan 10/10, item 7.6). Every
 * entry is inflated once through a counting stream, so the declared sizes of the zip, which a forged file may set at
 * will, are never trusted: the check stops at the first byte past the unpacked size or the compression ratio. XML parts
 * are read with StAX without DTDs or external entities; a worksheet's row and column numbers and the shared strings'
 * count, declared and real, are checked as they stream by. Nothing is extracted to disk or kept in memory.
 */
public final class XlsxGuard {

    /** Which bound a workbook broke. */
    public enum Limit {
        NOT_A_ZIP,
        ENTRIES,
        UNPACKED_SIZE,
        COMPRESSION_RATIO,
        SHARED_STRINGS,
        SHARED_STRINGS_SIZE,
        ROWS,
        COLUMNS,
        MALFORMED_XML
    }

    /** The workbook breaks a bound and must not be read. */
    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Limit limit;

        Rejected(Limit limit, String detail) {
            super(limit + ": " + detail);
            this.limit = limit;
        }

        Rejected(Limit limit, String detail, Throwable cause) {
            super(limit + ": " + detail, cause);
            this.limit = limit;
        }

        public Limit limit() {
            return limit;
        }
    }

    private static final XMLInputFactory XML = secureFactory();

    private final XlsxLimits limits;
    private long unpacked;

    private XlsxGuard(XlsxLimits limits) {
        this.limits = limits;
    }

    /**
     * Passes when {@code file} is a zip within {@code limits}; throws {@link Rejected} otherwise.
     *
     * @throws IOException when the file cannot be read at all
     */
    public static void check(Path file, XlsxLimits limits) throws IOException {
        new XlsxGuard(limits).inspect(file);
    }

    private void inspect(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.size() > limits.maxEntries()) {
                throw new Rejected(Limit.ENTRIES, zip.size() + " entries, at most " + limits.maxEntries());
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream raw = zip.getInputStream(entry);
                        CountingStream counted = new CountingStream(raw, entry)) {
                    inspectEntry(entry, counted);
                }
            }
        } catch (ZipException notZip) {
            throw new Rejected(Limit.NOT_A_ZIP, "the file is not a readable zip", notZip);
        }
    }

    private void inspectEntry(ZipEntry entry, CountingStream counted) throws IOException {
        String name = entry.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xml") && !name.endsWith(".rels")) {
            counted.transferTo(OutputStream.nullOutputStream());
            return;
        }
        // The reader holds no resource of its own: the entry stream is closed by the caller.
        try {
            inspectXml(XML.createXMLStreamReader(counted), counted);
        } catch (XMLStreamException malformed) {
            Rejected cause = rejectedCause(malformed);
            if (cause != null) throw cause;
            throw new Rejected(Limit.MALFORMED_XML, entry.getName() + " is not well-formed XML", malformed);
        }
    }

    private void inspectXml(XMLStreamReader xml, CountingStream counted) throws XMLStreamException, IOException {
        String root = null;
        SheetBounds sheet = null;
        long strings = 0;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.DTD) {
                throw new Rejected(Limit.MALFORMED_XML, "a part declares a DTD, which no workbook part has");
            }
            if (event != XMLStreamConstants.START_ELEMENT) continue;
            String element = xml.getLocalName();
            if (root == null) {
                root = element;
                if ("worksheet".equals(root)) sheet = new SheetBounds(limits);
                if ("sst".equals(root)) checkDeclaredStrings(xml);
            }
            if (sheet != null) {
                sheet.element(element, xml.getAttributeValue(null, "r"));
            } else if ("sst".equals(root) && "si".equals(element) && ++strings > limits.maxSharedStrings()) {
                throw new Rejected(Limit.SHARED_STRINGS, "more than " + limits.maxSharedStrings() + " shared strings");
            }
            if ("sst".equals(root) && counted.entryBytes() > limits.sharedStringsBytes()) {
                throw sharedStringsTooLarge();
            }
        }
        counted.transferTo(OutputStream.nullOutputStream());
        if ("sst".equals(root) && counted.entryBytes() > limits.sharedStringsBytes()) {
            throw sharedStringsTooLarge();
        }
    }

    private Rejected sharedStringsTooLarge() {
        return new Rejected(
                Limit.SHARED_STRINGS_SIZE, "the shared strings exceed " + limits.sharedStringsBytes() + " bytes");
    }

    /** A reader may size its table by the declared counts: they are bounded like the real one. */
    private void checkDeclaredStrings(XMLStreamReader xml) {
        for (String attribute : new String[] {"count", "uniqueCount"}) {
            String declared = xml.getAttributeValue(null, attribute);
            if (declared == null) continue;
            long value;
            try {
                value = Long.parseLong(declared.strip());
            } catch (NumberFormatException notNumber) {
                throw new Rejected(
                        Limit.MALFORMED_XML, "shared strings declare " + attribute + "=" + declared, notNumber);
            }
            if (value > limits.maxSharedStrings() || value < 0) {
                throw new Rejected(
                        Limit.SHARED_STRINGS,
                        "shared strings declare " + attribute + "=" + value + ", at most " + limits.maxSharedStrings());
            }
        }
    }

    private static @Nullable Rejected rejectedCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof Rejected rejected) return rejected;
            if (cause instanceof XMLStreamException stream && stream.getNestedException() instanceof Rejected nested) {
                return nested;
            }
        }
        return null;
    }

    private static XMLInputFactory secureFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return factory;
    }

    /** Counts the inflated bytes of one entry and of the whole workbook, refusing the first byte past a bound. */
    private final class CountingStream extends FilterInputStream {
        private final ZipEntry entry;
        private long entryBytes;

        CountingStream(InputStream in, ZipEntry entry) {
            super(in);
            this.entry = entry;
        }

        long entryBytes() {
            return entryBytes;
        }

        @Override
        public int read() throws IOException {
            int next = super.read();
            if (next >= 0) count(1);
            return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) count(read);
            return read;
        }

        private void count(int bytes) {
            entryBytes += bytes;
            unpacked += bytes;
            if (unpacked > limits.unpackedBytes()) {
                throw new Rejected(Limit.UNPACKED_SIZE, "the entries unpack to more than " + limits.unpackedBytes());
            }
            long compressed = Math.max(1, entry.getCompressedSize());
            if (entryBytes > XlsxLimits.RATIO_FLOOR_BYTES && entryBytes / compressed > limits.maxCompressionRatio()) {
                throw new Rejected(
                        Limit.COMPRESSION_RATIO,
                        entry.getName() + " inflates more than " + limits.maxCompressionRatio() + " times");
            }
        }
    }
}
