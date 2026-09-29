package com.smartup24.cms.instance.fnd.dwh;

import com.smartup24.cms.instance.fnd.FndPref;
import com.smartup24.cms.instance.fnd.config.DwhDataSourceProperties;
import com.smartup24.cms.instance.fnd.error.ConstraintErrorCode;
import com.smartup24.cms.instance.fnd.error.ConstraintViolationException;
import com.smartup24.cms.instance.fnd.load.FndLoad;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Запись строк в {@code raw} второй базы (AC-33, AC-34, AC-36). Раскладка raw скрыта: модули
 * видят только этот фасад, поэтому переход с общей таблицы на таблицу-на-источник их не затронет.
 *
 * <p>Статус загрузки живёт в OLTP, строки — в pg-dwh; распределённой транзакции между ними нет
 * (02 п.18). Строки пишутся одной транзакцией pg-dwh, а OLTP-транзакция открывается только на её
 * коммит (план 10/10, п. 3.8: раньше она держала {@code for share} всю запись, и миллион строк
 * означал минуты открытой транзакции OLTP): строка загрузки берётся {@code for share}, проверяется
 * статус {@code pending}, коммитится pg-dwh, и только затем отпускается блокировка. Параллельный
 * {@code apply}/{@code fail} (они берут {@code for update}) ждёт лишь коммита; загрузка, закрытая во
 * время записи, отвергает её на коммите — строки не попадут в уже применённую загрузку (13 инв.4).
 * Статус проверяется и до записи, чтобы не писать зря. Сбой на любой строке — откат всей записи.
 *
 * <p>Plan 10/10, item 3.9: rows go through one {@code COPY ... from stdin} in text format, encoded into a small buffer
 * as the source pushes them; no row outlives its line. The copy may run for as long as the file takes to parse, so the
 * transaction lifts the pool's statement limit to {@code app.dwh.raw-write-timeout} for itself only.
 */
@Component
public class JdbcFndRawWriter implements FndRawWriter {

    private static final Logger log = LoggerFactory.getLogger(JdbcFndRawWriter.class);
    /** Bytes collected before they go to the server: large enough to keep round trips rare, small enough to ignore. */
    private static final int BUFFER = 1 << 16;
    /** For writers built by hand; the application takes {@code app.dwh.raw-write-timeout}. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    private static final String COPY =
            "copy raw.rows (load_id, source_file_id, row_no, sheet, source_row_no, fields) from stdin";
    private static final String NULL = "\\N";

    private final DataSource dwh;
    private final JdbcClient oltp;
    private final TransactionTemplate oltpTx;
    private final ObjectMapper json;
    private final String timeoutMs;

    @Autowired
    public JdbcFndRawWriter(
            @Qualifier(FndPref.DWH) DataSource dwh,
            JdbcClient oltp,
            PlatformTransactionManager oltpTransactions,
            ObjectMapper json,
            DwhDataSourceProperties props) {
        this(dwh, oltp, oltpTransactions, json, props.rawWriteTimeout());
    }

    public JdbcFndRawWriter(
            DataSource dwh, JdbcClient oltp, PlatformTransactionManager oltpTransactions, ObjectMapper json) {
        this(dwh, oltp, oltpTransactions, json, DEFAULT_TIMEOUT);
    }

    /** A writer whose streamed writes may last up to {@code timeout}, whatever the pool allows one statement. */
    public JdbcFndRawWriter(
            DataSource dwh,
            JdbcClient oltp,
            PlatformTransactionManager oltpTransactions,
            ObjectMapper json,
            Duration timeout) {
        this.dwh = dwh;
        this.oltp = oltp;
        this.oltpTx = new TransactionTemplate(oltpTransactions);
        this.json = json;
        this.timeoutMs = String.valueOf(timeout.toMillis());
    }

    @Override
    public long copy(long loadId, UUID sourceFileId, FndRawSource rows) {
        oltpTx.executeWithoutResult(status -> requirePending(loadId));
        long written;
        try (Connection connection = connect()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                liftTimeout(connection);
                written = stream(connection, loadId, sourceFileId, rows);
                commitWhilePending(loadId, connection);
            } catch (RuntimeException | SQLException failure) {
                // Источник строк тоже может бросить исключение (AC-34): в raw не должно остаться ничего
                rollback(connection);
                throw failure;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException failure) {
            throw new DwhUnavailableException(failure);
        }
        // В журнале только объём: содержимое строк raw в логи не попадает (AC-43)
        log.info("raw_write load_id={} rows={}", loadId, written);
        return written;
    }

    /** Runs the source into one COPY; a failure cancels the copy, the caller rolls the transaction back. */
    private long stream(Connection connection, long loadId, UUID sourceFileId, FndRawSource rows) throws SQLException {
        CopyIn copy = connection.unwrap(PGConnection.class).getCopyAPI().copyIn(COPY);
        try {
            CopyBuffer buffer = new CopyBuffer(copy);
            String prefix = loadId + "\t" + (sourceFileId == null ? NULL : sourceFileId.toString()) + "\t";
            StringBuilder line = new StringBuilder();
            rows.emit(row -> {
                line.setLength(0);
                line.append(prefix).append(row.rowNo()).append('\t');
                appendText(line, row.sheet());
                line.append('\t')
                        .append(
                                row.sourceRowNo() == null
                                        ? NULL
                                        : row.sourceRowNo().toString());
                line.append('\t');
                appendText(line, json.writeValueAsString(row.fields() == null ? Map.of() : row.fields()));
                line.append('\n');
                buffer.add(line);
            });
            buffer.flush();
            return copy.endCopy();
        } catch (CopyFailed failure) {
            cancel(copy);
            throw failure.getCause();
        } catch (IOException failure) {
            cancel(copy);
            throw new UncheckedIOException(failure);
        } catch (RuntimeException failure) {
            cancel(copy);
            throw failure;
        }
    }

    /**
     * A value in COPY text format: backslash, tab and line breaks escaped, so a cell that holds them stays one field.
     */
    private static void appendText(StringBuilder line, String value) {
        if (value == null) {
            line.append(NULL);
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> line.append("\\\\");
                case '\t' -> line.append("\\t");
                case '\n' -> line.append("\\n");
                case '\r' -> line.append("\\r");
                default -> line.append(c);
            }
        }
    }

    @Override
    public long count(long loadId) {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement("select count(*) from raw.rows where load_id = ?")) {
            statement.setLong(1, loadId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException failure) {
            throw new DwhUnavailableException(failure);
        }
    }

    @Override
    public List<FndRawRow> read(long loadId) {
        List<FndRawRow> rows = new ArrayList<>();
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "select row_no, sheet, source_row_no, fields::text as fields from raw.rows"
                                + " where load_id = ? order by row_no")) {
            statement.setLong(1, loadId);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    Object sourceRowNo = rs.getObject("source_row_no");
                    rows.add(new FndRawRow(
                            rs.getLong("row_no"),
                            rs.getString("sheet"),
                            sourceRowNo == null ? null : ((Number) sourceRowNo).intValue(),
                            json.readValue(rs.getString("fields"), Map.class)));
                }
            }
        } catch (SQLException failure) {
            throw new DwhUnavailableException(failure);
        }
        return rows;
    }

    /** The statement and idle limits of this transaction only: the connection goes back to the pool with its own. */
    private void liftTimeout(Connection connection) throws SQLException {
        try (PreparedStatement limits = connection.prepareStatement("select set_config('statement_timeout', ?, true),"
                + " set_config('idle_in_transaction_session_timeout', ?, true)")) {
            limits.setString(1, timeoutMs);
            limits.setString(2, timeoutMs);
            limits.execute();
        }
    }

    /** Commits pg-dwh inside a short OLTP transaction that holds the load {@code for share} and finds it pending. */
    private void commitWhilePending(long loadId, Connection connection) {
        oltpTx.executeWithoutResult(status -> {
            requirePending(loadId);
            try {
                connection.commit();
            } catch (SQLException failure) {
                throw new DwhUnavailableException(failure);
            }
        });
    }

    /** Проверка статуса под {@code for share}: блокировка держится до конца OLTP-транзакции вызывающего. */
    private void requirePending(long loadId) {
        String status = oltp.sql("select status from fnd_loads where id = :id for share")
                .param("id", loadId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (!FndLoad.PENDING.equals(status)) {
            throw new ConstraintViolationException(ConstraintErrorCode.FND_LOAD_STATUS_TRANSITION);
        }
    }

    private Connection connect() throws SQLException {
        return dwh.getConnection();
    }

    private static void cancel(CopyIn copy) {
        try {
            if (copy.isActive()) {
                copy.cancelCopy();
            }
        } catch (SQLException cancelFailure) {
            log.error("raw_write отмена COPY не выполнена", cancelFailure);
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            log.error("raw_write откат не выполнен", rollbackFailure);
        }
    }

    /** Encoded lines waiting for the server; a full buffer goes out before the next line is taken. */
    private static final class CopyBuffer {
        private final CopyIn copy;
        private final byte[] bytes = new byte[BUFFER];
        private int size;

        CopyBuffer(CopyIn copy) {
            this.copy = copy;
        }

        void add(CharSequence line) {
            byte[] encoded = line.toString().getBytes(StandardCharsets.UTF_8);
            if (size + encoded.length > bytes.length) {
                flush();
                if (encoded.length > bytes.length) {
                    // A row longer than the buffer goes out alone; the buffer keeps its size
                    send(encoded, encoded.length);
                    return;
                }
            }
            System.arraycopy(encoded, 0, bytes, size, encoded.length);
            size += encoded.length;
        }

        void flush() {
            if (size > 0) {
                send(bytes, size);
                size = 0;
            }
        }

        private void send(byte[] data, int length) {
            try {
                copy.writeToCopy(data, 0, length);
            } catch (SQLException failure) {
                throw new CopyFailed(failure);
            }
        }
    }

    /** A server refusal inside the sink, carried out of the source's callback as it is. */
    private static final class CopyFailed extends RuntimeException {
        CopyFailed(SQLException cause) {
            super(cause);
        }

        @Override
        public synchronized SQLException getCause() {
            return (SQLException) super.getCause();
        }
    }
}
