package com.smartup24.cms.instance.warehouse.migration;

import com.smartup24.cms.instance.warehouse.WarehousePref;
import org.springframework.boot.ExitCodeGenerator;

/**
 * The schema of one of the databases does not match the one the build expects: startup is aborted
 * and the process exits with code 3.
 *
 * @param db       {@code oltp} or {@code dwh}
 * @param expected the latest migration version on the classpath
 * @param actual   what was found in {@code flyway_schema_history} (a version, {@code failed:<v>} or {@code none})
 */
public class SchemaVersionMismatchException extends RuntimeException implements ExitCodeGenerator {

    private final String db;
    private final String expected;
    private final String actual;

    public SchemaVersionMismatchException(String db, String expected, String actual) {
        super("schema_version_mismatch db=" + db + " expected=" + expected + " actual=" + actual);
        this.db = db;
        this.expected = expected;
        this.actual = actual;
    }

    public String db() {
        return db;
    }

    public String expected() {
        return expected;
    }

    public String actual() {
        return actual;
    }

    @Override
    public int getExitCode() {
        return WarehousePref.EXIT_SCHEMA_MISMATCH;
    }
}
