package com.smartup24.cms.instance.fnd.versioning;

import com.smartup24.cms.instance.fnd.FndActors;
import com.smartup24.cms.instance.fnd.api.ConstraintErrorCode;
import com.smartup24.cms.instance.fnd.api.ConstraintViolationException;
import com.smartup24.cms.instance.fnd.api.FndActor;
import com.smartup24.cms.instance.fnd.api.FndSqlErrors;
import com.smartup24.cms.instance.fnd.api.FndVersion;
import com.smartup24.cms.instance.fnd.api.FndVersions;
import com.smartup24.cms.instance.fnd.api.StaleVersionException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one mechanism of versions with an effective date for all DW modules. A module declares its versions table in
 * a migration by calling {@code fnd_versioning_enable}, and writes the version data (for example {@code factor}) to
 * its own columns through {@link #updateDraft}.
 *
 * <p>Lifecycle: {@link #createDraft} (number = max + 1, at most one draft per header) → edits to the draft →
 * {@link #publish} (closes the previous version) → {@link #supersede} if the version was published by mistake.
 * The {@code deny_update_published()} trigger protects a published row.
 */
@Service
public class FndVersioning implements FndVersions {

    /** A table or column name in dynamic SQL: only names we create ourselves in migrations. */
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    /** Columns owned by the versioning standard: they change only through this facade's own methods. */
    private static final Set<String> RESERVED_COLUMNS =
            Set.of("id", "status", "version", "valid_from", "valid_to", "lock_version", "published_at", "published_by");

    private final JdbcClient jdbc;
    private final FndActors actors;
    /** table -&gt; header column; the fnd_versioned_tables registry changes only through migrations. */
    private final Map<String, String> headerColumns = new ConcurrentHashMap<>();

    public FndVersioning(JdbcClient jdbc, FndActors actors) {
        this.jdbc = jdbc;
        this.actors = actors;
    }

    /**
     * Creates a draft with the next number. The draft's {@code valid_from} is a placeholder (the current date).
     * Assumption: a placeholder is acceptable because the column is declared {@code not null} and the effective
     * dates are set on publishing.
     *
     * @throws ConstraintViolationException {@code fnd_version_draft_exists} when the header already has a draft
     * @throws ConstraintViolationException {@code fnd_version_conflict} when a concurrent createDraft for the same
     *     header won the race
     */
    @Transactional
    @Override
    public int createDraft(String versionsTable, long headerId, FndActor actor) {
        String header = headerColumn(versionsTable);
        actors.apply(actor);
        Integer existing = jdbc.sql(
                        "select version from " + versionsTable + " where " + header + " = :h and status = 'draft'")
                .param("h", headerId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (existing != null) {
            throw new ConstraintViolationException(ConstraintErrorCode.FND_VERSION_DRAFT_EXISTS);
        }
        return FndSqlErrors.translatingVersions(
                versionsTable,
                () -> jdbc.sql("insert into " + versionsTable + " (" + header
                                + ", valid_from, status) values (:h, current_date, 'draft') returning version")
                        .param("h", headerId)
                        .query(Integer.class)
                        .single());
    }

    /**
     * Publishes a draft: closes the open end of the previous version on the day before {@code validFrom} and moves
     * the draft to {@code published}.
     *
     * @throws ConstraintViolationException {@code fnd_version_unknown} when there is no draft with that number;
     *                                      {@code fnd_version_not_after_previous} when the date is not later than
     *                                      the previous version's
     */
    @Transactional
    @Override
    public void publish(
            String versionsTable, long headerId, int version, LocalDate validFrom, LocalDate validTo, FndActor actor) {
        String header = headerColumn(versionsTable);
        actors.apply(actor);
        // The draft row is locked: a concurrent publish of the same version waits for the commit, then sees
        // published and is rejected, rather than "succeeding" with zero rows updated
        String status = jdbc.sql("select status from " + versionsTable + " where " + header
                        + " = :h and version = :v for update")
                .param("h", headerId)
                .param("v", version)
                .query(String.class)
                .optional()
                .orElse(null);
        if (!FndVersion.DRAFT.equals(status)) {
            throw new ConstraintViolationException(ConstraintErrorCode.FND_VERSION_UNKNOWN);
        }
        // The service, not a database constraint, checks the version order: on rejection the previous version stays
        // untouched and the draft stays a draft, because nothing has been written before this point.
        Optional<FndVersion> previous = jdbc.sql("select " + header + " as header_id, version, valid_from, valid_to,"
                        + " status, published_at, published_by, lock_version from " + versionsTable
                        + " where " + header + " = :h and status = 'published' order by valid_from desc limit 1")
                .param("h", headerId)
                .query(FndVersioning::mapVersion)
                .optional();
        if (previous.isPresent() && !validFrom.isAfter(previous.get().validFrom())) {
            throw new ConstraintViolationException(ConstraintErrorCode.FND_VERSION_NOT_AFTER_PREVIOUS);
        }
        int published = FndSqlErrors.translating(() -> {
            if (previous.isPresent() && previous.get().validTo() == null) {
                jdbc.sql("update " + versionsTable + " set valid_to = :to where " + header + " = :h and version = :v")
                        .param("to", validFrom.minusDays(1))
                        .param("h", headerId)
                        .param("v", previous.get().version())
                        .update();
            }
            return jdbc.sql("update " + versionsTable + " set status = 'published', valid_from = :from,"
                            + " valid_to = :to, published_at = now(), published_by = :by"
                            + " where " + header + " = :h and version = :v and status = 'draft'")
                    .param("from", validFrom)
                    .param("to", validTo)
                    .param("by", actor.name())
                    .param("h", headerId)
                    .param("v", version)
                    .update();
        });
        if (published != 1) {
            // The draft vanished between the check and the update: the transaction rolls back, together with the
            // closing of the previous version
            throw new ConstraintViolationException(ConstraintErrorCode.FND_VERSION_UNKNOWN);
        }
    }

    /** Withdraws a version published by mistake: its interval is freed and the row stays as history. */
    @Transactional
    @Override
    public void supersede(String versionsTable, long headerId, int version, FndActor actor) {
        String header = headerColumn(versionsTable);
        actors.apply(actor);
        int updated = FndSqlErrors.translating(() -> jdbc.sql("update " + versionsTable
                        + " set status = 'superseded' where " + header + " = :h and version = :v"
                        + " and status = 'published'")
                .param("h", headerId)
                .param("v", version)
                .update());
        if (updated == 0) {
            throw new ConstraintViolationException(ConstraintErrorCode.FND_VERSION_UNKNOWN);
        }
    }

    /**
     * Updates draft columns under optimistic locking: a trigger increments {@code lock_version}, so a client holding
     * an old value gets {@link StaleVersionException}.
     */
    @Transactional
    @Override
    public void updateDraft(
            String versionsTable,
            long headerId,
            int version,
            int expectedLockVersion,
            Map<String, Object> columns,
            FndActor actor) {
        String header = headerColumn(versionsTable);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Нечего обновлять: список колонок пуст");
        }
        Map<String, Object> checked = new LinkedHashMap<>();
        columns.forEach((column, value) -> {
            if (!IDENTIFIER.matcher(column).matches()) {
                throw new IllegalArgumentException("Недопустимое имя колонки: " + column);
            }
            String normalized = column.toLowerCase(Locale.ROOT);
            if (RESERVED_COLUMNS.contains(normalized) || normalized.equals(header)) {
                throw new IllegalArgumentException(
                        "Колонка управляется версионностью, правка через updateDraft запрещена: " + column);
            }
            checked.put(column, value);
        });
        actors.apply(actor);
        String assignments = String.join(
                ", ", checked.keySet().stream().map(c -> c + " = :" + c).toList());
        JdbcClient.StatementSpec statement = jdbc.sql("update " + versionsTable + " set " + assignments
                        + " where " + header + " = :fnd_header and version = :fnd_version"
                        + " and lock_version = :fnd_lock and status = 'draft'")
                .param("fnd_header", headerId)
                .param("fnd_version", version)
                .param("fnd_lock", expectedLockVersion);
        for (Map.Entry<String, Object> column : checked.entrySet()) {
            statement = statement.param(column.getKey(), column.getValue());
        }
        JdbcClient.StatementSpec update = statement;
        int updated = FndSqlErrors.translating(update::update);
        if (updated == 0) {
            if (find(versionsTable, headerId, version).isEmpty()) {
                throw new ConstraintViolationException(ConstraintErrorCode.FND_VERSION_UNKNOWN);
            }
            throw new StaleVersionException();
        }
    }

    /** The number of the version in effect on the date; drafts and withdrawn versions are ignored. */
    @Transactional(readOnly = true)
    @Override
    public Optional<Integer> versionAt(String versionsTable, long headerId, LocalDate date) {
        headerColumn(versionsTable);
        return jdbc.sql("select fnd_version_at(cast(:t as regclass), :h, :d)")
                .param("t", versionsTable)
                .param("h", headerId)
                .param("d", date)
                .query(Integer.class)
                .optional();
    }

    /** A version row as stored, for modules and checks. */
    @Transactional(readOnly = true)
    @Override
    public Optional<FndVersion> find(String versionsTable, long headerId, int version) {
        String header = headerColumn(versionsTable);
        return jdbc.sql("select " + header + " as header_id, version, valid_from, valid_to, status,"
                        + " published_at, published_by, lock_version from " + versionsTable
                        + " where " + header + " = :h and version = :v")
                .param("h", headerId)
                .param("v", version)
                .query(FndVersioning::mapVersion)
                .optional();
    }

    /** The header column from the registry; also checks that the table was declared under the versioning standard. */
    private String headerColumn(String versionsTable) {
        if (!IDENTIFIER.matcher(versionsTable).matches()) {
            throw new IllegalArgumentException("Недопустимое имя таблицы версий: " + versionsTable);
        }
        return headerColumns.computeIfAbsent(
                versionsTable,
                table -> jdbc.sql("select header_column from fnd_versioned_tables where table_name = :t")
                        .param("t", table)
                        .query(String.class)
                        .optional()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Таблица " + table + " не объявлена через fnd_versioning_enable")));
    }

    private static FndVersion mapVersion(ResultSet rs, int rowNum) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        java.sql.Date validTo = rs.getDate("valid_to");
        return new FndVersion(
                rs.getLong("header_id"),
                rs.getInt("version"),
                rs.getDate("valid_from").toLocalDate(),
                validTo == null ? null : validTo.toLocalDate(),
                rs.getString("status"),
                publishedAt == null ? null : publishedAt.toInstant(),
                rs.getString("published_by"),
                rs.getInt("lock_version"));
    }
}
