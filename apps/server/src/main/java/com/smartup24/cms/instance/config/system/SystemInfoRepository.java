package com.smartup24.cms.instance.config.system;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The database questions of the system information page: is the database there, which migration it carries, which
 * organization the instance serves (plan 10/10, item 4.2: the service writes no SQL). Each may fail; the service turns
 * a failure into its own fallback.
 */
@Repository
public class SystemInfoRepository {

    private final JdbcClient jdbc;

    public SystemInfoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs {@code select 1}; an exception means the database does not answer. */
    public void ping() {
        jdbc.sql("select 1").query().singleValue();
    }

    /** The latest successful migration version. */
    public Optional<String> schemaVersion() {
        return jdbc.sql("""
                        select version
                        from flyway_schema_history
                        where success
                        order by installed_rank desc
                        limit 1
                        """).query(String.class).optional();
    }

    /** The organization recorded at the instance's initial setup. */
    public Optional<SystemInfoResponse.Organization> organization() {
        return jdbc.sql("""
                        select client_code as code,
                               client_name as name,
                               resource_profile
                        from md_instance_info
                        limit 1
                        """).query(SystemInfoResponse.Organization.class).optional();
    }
}
