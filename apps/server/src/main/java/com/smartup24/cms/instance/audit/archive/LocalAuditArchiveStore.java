package com.smartup24.cms.instance.audit.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Archives in a directory on the server. The files hold personal data of the audit log: on POSIX systems they are
 * readable by the server's user only.
 */
public class LocalAuditArchiveStore implements AuditArchiveStore {

    private final Path root;

    public LocalAuditArchiveStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String storage() {
        return AuditArchiveProperties.LOCAL;
    }

    @Override
    public void put(String key, Path file) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.move(file, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    /** Keys are built by the service from dates; a key that leaves the root is refused all the same. */
    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Archive key leaves the archive directory: " + key);
        }
        return target;
    }
}
