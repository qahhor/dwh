package com.smartup24.cms.instance.upl.parse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A stored package file copied to a temporary file for the parser and deleted on close (plan 10/10, item 3.9). The
 * xlsx reader needs random access to the zip: from a stream it would hold the whole file in memory, from disk it holds
 * nothing. The copy streams through a small buffer; the temporary directory needs room for one file per running job.
 */
public final class UplSpooledFile implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UplSpooledFile.class);

    private final Path path;

    private UplSpooledFile(Path path) {
        this.path = path;
    }

    /** Copies {@code content} to a new temporary file; the caller closes {@code content}. */
    public static UplSpooledFile of(InputStream content) throws IOException {
        Path path = Files.createTempFile("upl-package-", ".xlsx");
        try {
            Files.copy(content, path, StandardCopyOption.REPLACE_EXISTING);
            return new UplSpooledFile(path);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(path);
            throw failure;
        }
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            // A leftover temporary file costs disk, not correctness: log it and let the job finish
            log.warn("upl_spool_not_deleted path={}", path, failure);
        }
    }
}
