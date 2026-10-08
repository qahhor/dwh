package com.smartup24.cms.instance.warehouse.repository;

import com.smartup24.cms.instance.warehouse.datasource.WarehouseMaintenance;
import com.smartup24.cms.instance.warehouse.raw.RawPartitions;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * The maintenance queries over {@code raw.rows} in pg-dwh (plan 10/10, items 4.2 and 7.8). Each runs under the
 * maintenance statement limit ({@link WarehouseMaintenance}), not the pool's. Writing and reading the rows of one load
 * is the {@code RawWriter} facade's.
 *
 * <p>Rows never leave raw one by one: a row trigger forbids {@code UPDATE} and {@code DELETE} (dwh V004), and the
 * rows of a load go with its partition, detached concurrently and dropped. A drop leaves no dead tuples behind and
 * takes no vacuum, whatever the size of the load.
 */
@Repository
public class RawRowRepository {

    private final WarehouseMaintenance dwh;

    public RawRowRepository(WarehouseMaintenance dwh) {
        this.dwh = dwh;
    }

    /**
     * The load partitions of the raw schema, attached or not: a table left by an interrupted cleanup (detached, or
     * with its detach pending) is listed too, so the next cleanup finishes it. Read from the catalog, no table lock.
     */
    public List<RawPartition> partitions() {
        return dwh.inTransaction(connection -> {
            List<RawPartition> found = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    select c.relname, i.inhrelid is not null as attached, coalesce(i.inhdetachpending, false) as pending
                      from pg_class c
                      join pg_namespace n on n.oid = c.relnamespace
                      left join pg_inherits i on i.inhrelid = c.oid and i.inhparent = 'raw.rows'::regclass
                     where n.nspname = ? and c.relkind = 'r'
                    """)) {
                statement.setString(1, RawPartitions.SCHEMA);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        OptionalLong loadId = RawPartitions.loadIdOf(rs.getString("relname"));
                        if (loadId.isPresent()) {
                            found.add(new RawPartition(
                                    loadId.getAsLong(), rs.getBoolean("attached"), rs.getBoolean("pending")));
                        }
                    }
                }
            }
            return List.copyOf(found);
        });
    }

    /**
     * Removes a load's partition with its rows. {@code DETACH ... CONCURRENTLY} takes only {@code SHARE UPDATE
     * EXCLUSIVE} on {@code raw.rows}, so reads and writes of the other loads go on; it refuses a transaction block, so
     * each statement runs on its own. A detach left pending by an interrupted run is finalized first.
     */
    public void drop(RawPartition partition) {
        String table = RawPartitions.table(partition.loadId());
        dwh.outsideTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                if (partition.detachPending()) {
                    statement.execute("alter table raw.rows detach partition " + table + " finalize");
                } else if (partition.attached()) {
                    statement.execute("alter table raw.rows detach partition " + table + " concurrently");
                }
                statement.execute("drop table if exists " + table);
            }
            return null;
        });
    }

    /** The loads and the source files that the raw rows refer to, read in one transaction. */
    public References references() {
        return dwh.inTransaction(connection -> {
            List<Long> loadIds = new ArrayList<>();
            List<UUID> fileIds = new ArrayList<>();
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
            return new References(List.copyOf(loadIds), List.copyOf(fileIds));
        });
    }

    /** The distinct load ids and source file ids found in raw. */
    public record References(List<Long> loadIds, List<UUID> fileIds) {}

    /**
     * A load's partition of {@code raw.rows}.
     *
     * @param loadId        the load ({@code fnd_loads.id}) whose rows it holds
     * @param attached      whether it is a partition of {@code raw.rows} now
     * @param detachPending whether a concurrent detach began and did not finish
     */
    public record RawPartition(long loadId, boolean attached, boolean detachPending) {}
}
