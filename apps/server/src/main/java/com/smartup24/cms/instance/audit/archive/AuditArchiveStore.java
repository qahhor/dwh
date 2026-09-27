package com.smartup24.cms.instance.audit.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Where audit archives live: a directory on the server or an S3 bucket ({@code smc.audit.archive.target}). */
public interface AuditArchiveStore {

    /** {@code local} or {@code s3}, as recorded in {@code audit_log_archives.storage}. */
    String storage();

    /** Stores a finished file under the key; the staged file may be moved or left for the caller to delete. */
    void put(String key, Path file) throws IOException;

    /** Reads a stored file back, to verify it. */
    InputStream open(String key) throws IOException;

    /** Whether the file is still there: checked before a partition leaves the database on its strength. */
    boolean exists(String key) throws IOException;

    /** Removes a stored file; a missing one is not an error. */
    void delete(String key) throws IOException;
}
