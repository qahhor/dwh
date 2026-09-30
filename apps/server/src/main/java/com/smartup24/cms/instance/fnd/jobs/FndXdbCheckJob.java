package com.smartup24.cms.instance.fnd.jobs;

import com.smartup24.cms.instance.fnd.FndPref;
import com.smartup24.cms.instance.fnd.config.FndDwhMaintenance;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Cross-database check: there are no foreign keys between OLTP and pg-dwh, so a job looks for orphans instead.
 * A {@code raw} row that refers to a load or a framework file that does not exist becomes an {@code xdb_mismatch}
 * event in {@code security_events}; the data itself is left untouched.
 */
@Component
public class FndXdbCheckJob implements FndJobHandler {

    public static final String CODE = "fnd.xdb_check";
    public static final String EVENT = "xdb_mismatch";
    private static final Logger log = LoggerFactory.getLogger(FndXdbCheckJob.class);
    /** The check runs on the server itself: it has no client address, yet the ip column is mandatory. */
    private static final String LOCAL_IP = "127.0.0.1";

    private final JdbcClient oltp;
    private final FndDwhMaintenance dwh;

    public FndXdbCheckJob(JdbcClient oltp, FndDwhMaintenance dwh) {
        this.oltp = oltp;
        this.dwh = dwh;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void run(Map<String, Object> args) {
        List<Long> loadIds = new ArrayList<>();
        List<UUID> fileIds = new ArrayList<>();
        // Both passes read the whole of raw, so they run under the maintenance limit, not the usual query limit
        dwh.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                try (ResultSet rs = statement.executeQuery("select distinct load_id from raw.rows")) {
                    while (rs.next()) {
                        loadIds.add(rs.getLong(1));
                    }
                }
                try (ResultSet rs = statement.executeQuery(
                        "select distinct source_file_id from raw.rows where source_file_id is not null")) {
                    while (rs.next()) {
                        fileIds.add(rs.getObject(1, UUID.class));
                    }
                }
            }
            return null;
        });

        Long systemUserId = systemUserId();
        int mismatches = 0;
        for (Long orphan : orphans(loadIds, knownLoadIds(loadIds))) {
            report(systemUserId, "{\"load_id\": " + orphan + "}");
            mismatches++;
        }
        for (UUID orphan : orphans(fileIds, knownFileIds(fileIds))) {
            report(systemUserId, "{\"source_file_id\": \"" + orphan + "\"}");
            mismatches++;
        }
        log.info("xdb_check loads={} files={} mismatches={}", loadIds.size(), fileIds.size(), mismatches);
    }

    /** One query for all ids, matched against an array to avoid N+1 queries. An empty list issues no query. */
    private Set<Long> knownLoadIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(oltp.sql("select id from fnd_loads where id = any(:ids)")
                .param("ids", ids.toArray(Long[]::new))
                .query(Long.class)
                .list());
    }

    private Set<UUID> knownFileIds(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(oltp.sql("select id from mf_files where id = any(cast(:ids as uuid[]))")
                .param("ids", ids.stream().map(UUID::toString).toArray(String[]::new))
                .query(UUID.class)
                .list());
    }

    private static <T> List<T> orphans(List<T> all, Set<T> known) {
        return all.stream().filter(id -> !known.contains(id)).toList();
    }

    private Long systemUserId() {
        return oltp.sql("select id from md_users where login = :login")
                .param("login", FndPref.SYSTEM_ACTOR)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private void report(Long systemUserId, String details) {
        oltp.sql("insert into security_events (event_type, user_id, ip, user_agent, details)"
                        + " values (:event, :user, cast(:ip as inet), :agent, cast(:details as jsonb))")
                .param("event", EVENT)
                .param("user", systemUserId)
                .param("ip", LOCAL_IP)
                .param("agent", CODE)
                .param("details", details)
                .update();
    }
}
