package com.smartup24.cms.instance.warehouse.mart;

import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.QueryAggregate;
import com.smartup24.cms.instance.common.query.QueryAggregateRepository;
import com.smartup24.cms.instance.common.query.QueryAggregateResult;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.warehouse.WarehousePref;
import com.smartup24.cms.instance.warehouse.api.MartRow;
import com.smartup24.cms.instance.warehouse.api.WarehouseMarts;
import com.smartup24.cms.instance.warehouse.api.WarehouseUnavailableException;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * JDBC implementation of {@link WarehouseMarts} over {@code mart.rows} in {@code pg-dwh} (ADR-0048; Constructor Stage 3,
 * step 3.3).
 */
@Component
public class JdbcWarehouseMarts implements WarehouseMarts {

    private static final int BATCH_SIZE = 500;
    private static final String INSERT_SQL = """
            insert into mart.rows (source_code, load_id, period_from, period_to, sheet, row_no, fields)
            values (?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """;

    private final JdbcTemplate dwhTemplate;
    private final JdbcClient dwhJdbc;
    private final TransactionTemplate dwhTx;
    private final QueryAggregateRepository aggregates;
    private final JsonColumns jsonColumns;

    public JdbcWarehouseMarts(@Qualifier(WarehousePref.QUALIFIER) DataSource dwhDataSource, ObjectMapper json) {
        this.dwhTemplate = new JdbcTemplate(dwhDataSource);
        this.dwhJdbc = JdbcClient.create(this.dwhTemplate);
        this.dwhTx = new TransactionTemplate(new DataSourceTransactionManager(dwhDataSource));
        this.aggregates = new QueryAggregateRepository(this.dwhJdbc);
        this.jsonColumns = new JsonColumns(json, "mart.rows");
    }

    @Override
    public void publishLoad(
            String sourceCode,
            long loadId,
            LocalDate periodFrom,
            LocalDate periodTo,
            List<MartRow> rows,
            List<Long> activeLoadIds) {
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(periodFrom, "periodFrom");
        Objects.requireNonNull(periodTo, "periodTo");
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(activeLoadIds, "activeLoadIds");
        try {
            dwhTx.executeWithoutResult(status -> {
                dwhJdbc.sql("delete from mart.rows where load_id = :loadId")
                        .param("loadId", loadId)
                        .update();
                if (activeLoadIds.isEmpty()) {
                    dwhJdbc.sql("delete from mart.rows where source_code = :sourceCode")
                            .param("sourceCode", sourceCode)
                            .update();
                } else {
                    dwhJdbc.sql("delete from mart.rows where source_code = :sourceCode and load_id not in (:activeIds)")
                            .param("sourceCode", sourceCode)
                            .param("activeIds", activeLoadIds)
                            .update();
                }
                if (rows.isEmpty()) {
                    return;
                }
                Date fromDate = Date.valueOf(periodFrom);
                Date toDate = Date.valueOf(periodTo);
                dwhTemplate.batchUpdate(INSERT_SQL, rows, BATCH_SIZE, (PreparedStatement ps, MartRow row) -> {
                    ps.setString(1, sourceCode);
                    ps.setLong(2, loadId);
                    ps.setDate(3, fromDate);
                    ps.setDate(4, toDate);
                    ps.setString(5, row.sheet());
                    ps.setLong(6, row.rowNo());
                    ps.setString(7, jsonColumns.write(row.fields()));
                });
            });
        } catch (DataAccessException | TransactionException e) {
            throw new WarehouseUnavailableException(e);
        }
    }

    @Override
    public List<Long> materializedLoadIds(String sourceCode, List<Long> loadIds) {
        if (loadIds.isEmpty()) {
            return List.of();
        }
        try {
            return dwhJdbc.sql("""
                            select distinct load_id
                              from mart.rows
                             where source_code = :sourceCode
                               and load_id in (:loadIds)
                            """)
                    .param("sourceCode", sourceCode)
                    .param("loadIds", loadIds)
                    .query(Long.class)
                    .list();
        } catch (DataAccessException | TransactionException e) {
            throw new WarehouseUnavailableException(e);
        }
    }

    @Override
    public QueryAggregateResult aggregate(
            String sourceCode, List<Long> activeLoadIds, QueryAggregate aggregate, QueryPlan.SqlFragment extra) {
        QueryPlan.SqlFragment scoped;
        if (activeLoadIds.isEmpty()) {
            scoped = new QueryPlan.SqlFragment(extra.sql() + " and 1 = 0", extra.params());
        } else {
            Map<String, Object> params = new LinkedHashMap<>(extra.params());
            params.put("mart_source", sourceCode);
            params.put("mart_loads", activeLoadIds);
            scoped = new QueryPlan.SqlFragment(
                    extra.sql() + " and m.source_code = :mart_source and m.load_id in (:mart_loads)", params);
        }
        try {
            return aggregates.run(aggregate, scoped);
        } catch (DataAccessException | TransactionException e) {
            throw new WarehouseUnavailableException(e);
        }
    }
}
