package com.greenwhite.dwh.instance.fnd.jobs;

import com.greenwhite.dwh.instance.fnd.config.FndDwhMaintenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * Единственное место, где строки удаляются из {@code raw} (AC-31): убирает данные загрузок,
 * оставшихся в статусе {@code failed}. Применённые загрузки не трогаются никогда — raw неизменяем
 * (13 инв.4), а неудачная загрузка данных не образует.
 */
@Component
public class FndLoadCleanupJob implements FndJobHandler {

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
    public void run(java.util.Map<String, Object> args) {
        List<Long> failed = oltp.sql("select id from fnd_loads where status = 'failed'")
                .query(Long.class).list();
        if (failed.isEmpty()) {
            return;
        }
        // Удаление по всему raw может идти дольше обычного предела запроса — предел обслуживания
        int removed = dwh.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "delete from raw.rows where load_id = any (?)")) {
                statement.setArray(1, connection.createArrayOf("bigint", failed.toArray(new Long[0])));
                return statement.executeUpdate();
            }
        });
        log.info("load_cleanup loads={} rows_removed={}", failed.size(), removed);
    }
}
