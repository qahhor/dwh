package com.smartup24.cms.instance.fnd.jobs;

import com.smartup24.cms.instance.fnd.config.FndDwhMaintenance;
import com.smartup24.cms.instance.jobs.api.JobHandler;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The only place where rows are deleted from {@code raw}: removes the data of loads
 * left in status {@code failed}. Applied loads are never touched, because raw is immutable,
 * and a failed load produces no data.
 */
@Component
public class FndLoadCleanupJob implements JobHandler {

    public static final String CODE = "fnd.load_cleanup";
    private static final Logger log = LoggerFactory.getLogger(FndLoadCleanupJob.class);

    private final JdbcClient oltp;
    private final FndDwhMaintenance dwh;

    public FndLoadCleanupJob(JdbcClient oltp, FndDwhMaintenance dwh) {
        this.oltp = oltp;
        this.dwh = dwh;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void run(Map<String, Object> args) {
        List<Long> failed = oltp.sql("select id from fnd_loads where status = 'failed'")
                .query(Long.class)
                .list();
        if (failed.isEmpty()) {
            return;
        }
        // Deleting across the whole raw layer can take longer than the normal statement limit, hence the maintenance
        // limit
        int removed = dwh.inTransaction(connection -> {
            try (PreparedStatement statement =
                    connection.prepareStatement("delete from raw.rows where load_id = any (?)")) {
                statement.setArray(1, connection.createArrayOf("bigint", failed.toArray(new Long[0])));
                return statement.executeUpdate();
            }
        });
        log.info("load_cleanup loads={} rows_removed={}", failed.size(), removed);
    }
}
