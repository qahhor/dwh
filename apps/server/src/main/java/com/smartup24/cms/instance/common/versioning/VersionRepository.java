package com.smartup24.cms.instance.common.versioning;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of the versioning standard (plan 10/10, item 4.2). Table and column names come only from the registry
 * {@code fnd_versioned_tables} and from names {@link VersioningService} has checked against a strict pattern; values
 * are always bound parameters.
 */
@Repository
public class VersionRepository {

    private final JdbcClient jdbc;

    public VersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The header column of a table declared through {@code fnd_versioning_enable}. */
    public Optional<String> headerColumn(String versionsTable) {
        return jdbc.sql("select header_column from fnd_versioned_tables where table_name = :t")
                .param("t", versionsTable)
                .query(String.class)
                .optional();
    }

    /** The number of the header's draft, if it has one. */
    public Optional<Integer> draftVersion(String versionsTable, String header, long headerId) {
        return jdbc.sql("select version from " + versionsTable + " where " + header + " = :h and status = 'draft'")
                .param("h", headerId)
                .query(Integer.class)
                .optional();
    }

    /** Inserts a draft with a placeholder start date; the trigger numbers it. Returns the new number. */
    public int insertDraft(String versionsTable, String header, long headerId) {
        return jdbc.sql("insert into " + versionsTable + " (" + header
                        + ", valid_from, status) values (:h, current_date, 'draft') returning version")
                .param("h", headerId)
                .query(Integer.class)
                .single();
    }

    /** The status of a version under a row lock ({@code for update}). */
    public Optional<String> lockStatus(String versionsTable, String header, long headerId, int version) {
        return jdbc.sql("select status from " + versionsTable + " where " + header
                        + " = :h and version = :v for update")
                .param("h", headerId)
                .param("v", version)
                .query(String.class)
                .optional();
    }

    /** The latest published version of the header. */
    public Optional<Version> latestPublished(String versionsTable, String header, long headerId) {
        return jdbc.sql("select " + header + " as header_id, version, valid_from, valid_to,"
                        + " status, published_at, published_by, lock_version from " + versionsTable
                        + " where " + header + " = :h and status = 'published' order by valid_from desc limit 1")
                .param("h", headerId)
                .query(VersionRepository::mapVersion)
                .optional();
    }

    /** Closes the open end of a version on the given day. */
    public void closeVersion(String versionsTable, String header, long headerId, int version, LocalDate validTo) {
        jdbc.sql("update " + versionsTable + " set valid_to = :to where " + header + " = :h and version = :v")
                .param("to", validTo)
                .param("h", headerId)
                .param("v", version)
                .update();
    }

    /** Moves a draft to published with its dates; returns the rows updated. */
    public int publishDraft(
            String versionsTable,
            String header,
            long headerId,
            int version,
            LocalDate validFrom,
            @Nullable LocalDate validTo,
            String publishedBy) {
        return jdbc.sql("update " + versionsTable + " set status = 'published', valid_from = :from,"
                        + " valid_to = :to, published_at = now(), published_by = :by"
                        + " where " + header + " = :h and version = :v and status = 'draft'")
                .param("from", validFrom)
                .param("to", validTo)
                .param("by", publishedBy)
                .param("h", headerId)
                .param("v", version)
                .update();
    }

    /** Withdraws a published version; returns the rows updated. */
    public int supersede(String versionsTable, String header, long headerId, int version) {
        return jdbc.sql("update " + versionsTable
                        + " set status = 'superseded' where " + header + " = :h and version = :v"
                        + " and status = 'published'")
                .param("h", headerId)
                .param("v", version)
                .update();
    }

    /**
     * Updates the checked columns of a draft under optimistic locking; returns the rows updated. The column names
     * are checked by the caller and become named parameters of the same name.
     */
    public int updateDraft(
            String versionsTable,
            String header,
            long headerId,
            int version,
            int expectedLockVersion,
            Map<String, Object> columns) {
        String assignments = String.join(
                ", ", columns.keySet().stream().map(c -> c + " = :" + c).toList());
        JdbcClient.StatementSpec statement = jdbc.sql("update " + versionsTable + " set " + assignments
                        + " where " + header + " = :fnd_header and version = :fnd_version"
                        + " and lock_version = :fnd_lock and status = 'draft'")
                .param("fnd_header", headerId)
                .param("fnd_version", version)
                .param("fnd_lock", expectedLockVersion);
        for (Map.Entry<String, Object> column : columns.entrySet()) {
            statement = statement.param(column.getKey(), column.getValue());
        }
        return statement.update();
    }

    /** The number of the version in effect on the date ({@code fnd_version_at}). */
    public Optional<Integer> versionAt(String versionsTable, long headerId, LocalDate date) {
        return jdbc.sql("select fnd_version_at(cast(:t as regclass), :h, :d)")
                .param("t", versionsTable)
                .param("h", headerId)
                .param("d", date)
                .query(Integer.class)
                .optional();
    }

    /** A version row as stored. */
    public Optional<Version> find(String versionsTable, String header, long headerId, int version) {
        return jdbc.sql("select " + header + " as header_id, version, valid_from, valid_to, status,"
                        + " published_at, published_by, lock_version from " + versionsTable
                        + " where " + header + " = :h and version = :v")
                .param("h", headerId)
                .param("v", version)
                .query(VersionRepository::mapVersion)
                .optional();
    }

    private static Version mapVersion(ResultSet rs, int rowNum) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        java.sql.Date validTo = rs.getDate("valid_to");
        return new Version(
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
