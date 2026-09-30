package com.smartup24.cms.instance.fnd.dwh;

import com.smartup24.cms.instance.fnd.FndPref;
import com.smartup24.cms.instance.fnd.error.ConstraintErrorCode;
import com.smartup24.cms.instance.fnd.error.ConstraintViolationException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Reads marts and the cache of the second database.
 *
 * <p>The facade has no "run this SQL" method: the schema name comes from an allow-list, the table name must match
 * a strict pattern, and values are passed only as bound parameters. The {@code raw} and {@code core} layers are not
 * reachable through this facade: build jobs read them, not application code.
 */
@Component
public class FndMartReader {

    private static final Set<String> ALLOWED_SCHEMAS = Set.of("mart", "cache");
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final DataSource dwh;

    public FndMartReader(@Qualifier(FndPref.DWH) DataSource dwh) {
        this.dwh = dwh;
    }

    /** The current cache generation; if there is none, the result is empty rather than an exception. */
    public Optional<FndGeneration> currentGeneration() {
        List<Map<String, Object>> rows = query(
                "select generation_id, load_versions::text as load_versions, switched_at"
                        + " from cache.generations where state = 'current'",
                List.of());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        Timestamp switchedAt = (Timestamp) row.get("switched_at");
        return Optional.of(new FndGeneration(
                ((Number) row.get("generation_id")).longValue(),
                (String) row.get("load_versions"),
                switchedAt == null ? null : switchedAt.toInstant()));
    }

    /**
     * Reads a mart or cache table filtered by equality.
     *
     * @param schema  only {@code mart} or {@code cache}
     * @param table   table name matching {@code ^[a-z][a-z0-9_]{0,62}$}
     * @param filters column → value; values are passed as bound parameters
     */
    public List<Map<String, Object>> read(String schema, String table, Map<String, Object> filters) {
        if (!ALLOWED_SCHEMAS.contains(schema) || !NAME.matcher(table).matches()) {
            throw new ConstraintViolationException(ConstraintErrorCode.DWH_READ_FORBIDDEN);
        }
        StringBuilder sql =
                new StringBuilder("select * from ").append(schema).append('.').append(table);
        List<Object> values = new ArrayList<>();
        if (filters != null && !filters.isEmpty()) {
            List<String> conditions = new ArrayList<>();
            filters.forEach((column, value) -> {
                if (!NAME.matcher(column).matches()) {
                    throw new ConstraintViolationException(ConstraintErrorCode.DWH_READ_FORBIDDEN);
                }
                conditions.add(column + " = ?");
                values.add(value);
            });
            sql.append(" where ").append(String.join(" and ", conditions));
        }
        return query(sql.toString(), values);
    }

    private List<Map<String, Object>> query(String sql, List<Object> values) {
        try (Connection connection = dwh.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) {
                statement.setObject(i + 1, values.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rows(rs);
            }
        } catch (SQLException failure) {
            throw new DwhUnavailableException(failure);
        }
    }

    private static List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= meta.getColumnCount(); column++) {
                row.put(meta.getColumnLabel(column), rs.getObject(column));
            }
            rows.add(row);
        }
        return rows;
    }

    /** A cache generation: its number, the map of load versions it was built from, and when it was switched in. */
    public record FndGeneration(long generationId, String loadVersions, Instant switchedAt) {}
}
