package com.smartup24.cms.instance.warehouse.repository;

import com.smartup24.cms.instance.warehouse.datasource.WarehouseMaintenance;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * The maintenance queries over {@code raw.rows} in pg-dwh (plan 10/10, item 4.2). Each scans the whole raw layer, so
 * each runs in one transaction under the maintenance statement limit ({@link WarehouseMaintenance}), not the pool's.
 * Writing and reading the rows of one load is the {@code RawWriter} facade's.
 */
@Repository
public class RawRowRepository {

    private final WarehouseMaintenance dwh;

    public RawRowRepository(WarehouseMaintenance dwh) {
        this.dwh = dwh;
    }

    /** Deletes the rows of the given loads; returns how many were removed. */
    public int deleteRowsOfLoads(List<Long> loadIds) {
        return dwh.inTransaction(connection -> {
            try (PreparedStatement statement =
                    connection.prepareStatement("delete from raw.rows where load_id = any (?)")) {
                statement.setArray(1, connection.createArrayOf("bigint", loadIds.toArray(new Long[0])));
                return statement.executeUpdate();
            }
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
}
