package com.smartup24.cms.instance.warehouse.migration;

import com.smartup24.cms.instance.warehouse.WarehousePref;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Startup check of the second database, {@code pg-dwh}: the build's latest migration has been applied and succeeded,
 * so the application never starts against an outdated or broken schema. The OLTP schema is checked by the
 * framework's gate ({@code com.smartup24.cms.instance.config.db.SchemaVersionGate}) and is not checked again here.
 * The gate only reads {@code flyway_schema_history} and never migrates: migrating is a separate step
 * ({@link MigrateMain}) run before the application starts. A mismatch throws {@link SchemaVersionMismatchException},
 * and the process exits with code 3.
 */
@Component
public class WarehouseSchemaVersionGate implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(WarehouseSchemaVersionGate.class);
    static final String EVENT = "schema_version_mismatch";
    private static final String HISTORY_SQL = """
            select version, success
              from flyway_schema_history
             where version is not null
             order by installed_rank desc
            """;

    private final DataSource dwh;

    public WarehouseSchemaVersionGate(@Qualifier(WarehousePref.QUALIFIER) DataSource dwh) {
        this.dwh = dwh;
    }

    @Override
    public void afterPropertiesSet() {
        check("dwh", dwh, MigrationCatalog.onClasspath(WarehousePref.WAREHOUSE_MIGRATIONS));
        log.info("schema_version_ok db=dwh");
    }

    /** Checks one database: the expected version is found and no history row is marked {@code success=false}. */
    void check(String db, DataSource dataSource, MigrationCatalog catalog) {
        String expected = catalog.latestVersion();
        String actual = readActual(db, dataSource, expected);
        if (!expected.equals(actual)) {
            log.error("{} db={} expected={} actual={}", EVENT, db, expected, actual);
            throw new SchemaVersionMismatchException(db, expected, actual);
        }
    }

    private String readActual(String db, DataSource dataSource, String expected) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(HISTORY_SQL);
                ResultSet rs = ps.executeQuery()) {
            String latest = null;
            boolean expectedFound = false;
            while (rs.next()) {
                // Flyway stores the version as written in the file name (001), so versions are compared as numbers
                String version = new BigInteger(rs.getString(1)).toString();
                if (!rs.getBoolean(2)) {
                    return "failed:" + version;
                }
                if (latest == null) {
                    latest = version;
                }
                if (expected.equals(version)) {
                    expectedFound = true;
                }
            }
            if (latest == null) {
                return "none";
            }
            return expectedFound ? expected : latest;
        } catch (SQLException e) {
            if ("42P01".equals(e.getSQLState())) {
                return "none"; // no history table: no migration has ever been applied
            }
            throw new IllegalStateException("Cannot read flyway_schema_history in " + db, e);
        }
    }
}
