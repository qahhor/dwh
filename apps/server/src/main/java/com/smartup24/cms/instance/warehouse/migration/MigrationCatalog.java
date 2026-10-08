package com.smartup24.cms.instance.warehouse.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Catalog of versioned migrations of one database on the classpath: file names and the latest expected version.
 * Independent of Flyway: used both by the migrator and by {@link WarehouseSchemaVersionGate}.
 */
public final class MigrationCatalog {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

    private final String location;
    private final List<String> fileNames;

    private MigrationCatalog(String location, List<String> fileNames) {
        this.location = location;
        this.fileNames = fileNames;
    }

    /** Reads the catalog {@code classpath:<location>/V*.sql}; a catalog without files is a configuration error. */
    public static MigrationCatalog onClasspath(String location) {
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver().getResources("classpath*:" + location + "/V*.sql");
            List<String> names = Arrays.stream(resources)
                    .map(Resource::getFilename)
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
            if (names.isEmpty()) {
                throw new IllegalStateException("The migration folder is empty: " + location);
            }
            return new MigrationCatalog(location, names);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the migration folder " + location, e);
        }
    }

    public String location() {
        return location;
    }

    public List<String> fileNames() {
        return fileNames;
    }

    /** The highest catalog version in normalized form (as Flyway stores it: without leading zeros). */
    public String latestVersion() {
        return fileNames.stream()
                .map(MigrationCatalog::versionOf)
                .max(BigInteger::compareTo)
                .map(BigInteger::toString)
                .orElseThrow();
    }

    static BigInteger versionOf(String fileName) {
        Matcher m = VERSIONED.matcher(fileName);
        if (!m.matches()) {
            throw new IllegalStateException("The migration name breaks the naming rule: " + fileName);
        }
        return new BigInteger(m.group(1));
    }
}
