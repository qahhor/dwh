package com.smartup24.cms.instance.upl.upload;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.ErrorRow;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.NewPackage;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads and writes upload packages (tables of migration V114). The service sets transactions and the audit actor.
 * {@code jsonb} fields are written through {@code cast(:p as jsonb)}, read as text and parsed here.
 */
@Repository
public class UplPackageRepository {

    /**
     * The status a reader sees: a verified package that got a load number is being applied (plan 10/10, item 3.9) — its
     * load is open and the apply job has not closed it yet.
     */
    static final String STATUS_SQL =
            "case when p.status = 'verified' and p.load_id is not null then 'applying' else p.status end";

    /** Package columns; together with {@link #PACKAGE_FROM} used both for a single read and for the registry list. */
    static final String PACKAGE_COLUMNS = """
            p.id, p.public_id, p.source_id, s.code as source_code, s.name as source_name,
                   p.format_version, p.period_from, p.period_to, p.file_id, p.file_name, p.file_sha256,
                   p.file_size_bytes, """ + STATUS_SQL + " as status, " + """
                   p.rows_total, p.rows_accepted, p.rows_rejected, p.errors_total,
                   p.reject_code, p.reject_params::text as reject_params, p.load_id, p.raw_rows,
                   p.uploaded_at, p.uploaded_by""";

    static final String PACKAGE_FROM = "upl_packages p join upl_sources s on s.id = p.source_id";

    private static final String PACKAGE_SELECT = "select " + PACKAGE_COLUMNS + " from " + PACKAGE_FROM + " ";

    private static final String ERROR_SELECT = """
            select ordinal, sheet, row_no, column_name, cell_value, code, params::text as params
              from upl_package_errors
             where package_id = :package
             order by ordinal
            """;

    private final JdbcClient jdbc;
    private final JsonColumns jsonColumns;
    private final JsonColumns errorColumns;
    private final QueryListRepository lists;

    public UplPackageRepository(JdbcClient jdbc, ObjectMapper json, QueryListRepository lists) {
        this.jdbc = jdbc;
        this.jsonColumns = new JsonColumns(json, "upl_packages");
        this.errorColumns = new JsonColumns(json, "upl_package_errors");
        this.lists = lists;
    }

    public long insert(NewPackage p, String actorName) {
        return jdbc.sql("""
                        insert into upl_packages (source_id, format_version, period_from, period_to, file_id,
                                                  file_name, file_sha256, file_size_bytes,
                                                  uploaded_by_id, uploaded_by)
                        values (:sourceId, :formatVersion, :periodFrom, :periodTo, :fileId,
                                :fileName, :sha, :size, :userId, :actor)
                        returning id
                        """)
                .param("sourceId", p.sourceId())
                .param("formatVersion", p.formatVersion())
                .param("periodFrom", p.periodFrom())
                .param("periodTo", p.periodTo())
                .param("fileId", p.fileId())
                .param("fileName", p.fileName())
                .param("sha", p.fileSha256())
                .param("size", p.fileSizeBytes())
                .param("userId", p.uploadedById())
                .param("actor", actorName)
                .query(Long.class)
                .single();
    }

    public Optional<PackageRow> findById(long id) {
        return jdbc.sql(PACKAGE_SELECT + " where p.id = :id")
                .param("id", id)
                .query(this::mapPackage)
                .optional();
    }

    public Optional<PackageRow> findByPublicId(UUID publicId) {
        return jdbc.sql(PACKAGE_SELECT + " where p.public_id = :publicId")
                .param("publicId", publicId)
                .query(this::mapPackage)
                .optional();
    }

    /** Package with its row locked until the transaction ends: a second concurrent "Apply" waits for the first. */
    public Optional<PackageRow> lockByPublicId(UUID publicId) {
        return jdbc.sql(PACKAGE_SELECT + " where p.public_id = :publicId for update of p")
                .param("publicId", publicId)
                .query(this::mapPackage)
                .optional();
    }

    /** The package by its internal id, locked until the end of the transaction. */
    public Optional<PackageRow> lockById(long id) {
        return jdbc.sql(PACKAGE_SELECT + " where p.id = :id for update of p")
                .param("id", id)
                .query(this::mapPackage)
                .optional();
    }

    /**
     * "Applying" packages (verified, with a load number) older than {@code staleMinutes} minutes are candidates
     * for interrupted applies (the caller checks the load status through the foundation). Rows are locked;
     * rows held by another worker are skipped.
     */
    public List<PackageRow> lockStaleApplies(int staleMinutes) {
        return jdbc.sql(PACKAGE_SELECT + """
                         where p.status = 'verified' and p.load_id is not null
                           and p.modified_at < now() - make_interval(mins => :stale)
                         order by p.id
                           for update of p skip locked
                        """)
                .param("stale", staleMinutes)
                .query(this::mapPackage)
                .list();
    }

    /** Page of the list by the registry plan ({@link UplPackageQuery#LIST}). */
    public KeysetPage<PackageRow> pagePackages(QueryPlan plan) {
        return lists.page(plan, this::mapPackage);
    }

    /** Turns the package "verified"; returns 0 if the package is no longer "received". */
    public int markVerified(long id, int total, int accepted, int rejected, int errorsTotal) {
        return jdbc.sql("""
                        update upl_packages
                           set status = 'verified',
                               rows_total = :total,
                               rows_accepted = :accepted,
                               rows_rejected = :rejected,
                               errors_total = :errors,
                               modified_at = now()
                         where id = :id and status = 'received'
                        """)
                .param("total", total)
                .param("accepted", accepted)
                .param("rejected", rejected)
                .param("errors", errorsTotal)
                .param("id", id)
                .update();
    }

    /** Turns the package "rejected"; returns 0 if the package is no longer "received". */
    public int markRejected(long id, String rejectCode, Map<String, Object> rejectParams, Integer errorsTotal) {
        return jdbc.sql("""
                        update upl_packages
                           set status = 'rejected',
                               reject_code = :code,
                               reject_params = cast(:params as jsonb),
                               errors_total = :errors,
                               modified_at = now()
                         where id = :id and status = 'received'
                        """)
                .param("code", rejectCode)
                .param("params", jsonColumns.object(rejectParams))
                .param("errors", errorsTotal)
                .param("id", id)
                .update();
    }

    /**
     * Stores the foundation load number on a "verified" package, which becomes "applying"; 0 means the package
     * is not "verified" or already has a number.
     */
    public int setLoadId(long id, long loadId) {
        return jdbc.sql("""
                        update upl_packages
                           set load_id = :load,
                               modified_at = now()
                         where id = :id and status = 'verified' and load_id is null
                        """).param("load", loadId).param("id", id).update();
    }

    /** Turns a "verified" package "applied"; 0 means the package is no longer "verified". */
    public int markApplied(long id, int rawRows) {
        return jdbc.sql("""
                        update upl_packages
                           set status = 'applied',
                               raw_rows = :raw,
                               modified_at = now()
                         where id = :id and status = 'verified'
                        """).param("raw", rawRows).param("id", id).update();
    }

    /** Closes a "verified" package as "rejected" during apply; 0 means the package is no longer "verified". */
    public int markApplyRejected(long id, String rejectCode, Map<String, Object> rejectParams, Integer rawRows) {
        return jdbc.sql("""
                        update upl_packages
                           set status = 'rejected',
                               reject_code = :code,
                               reject_params = cast(:params as jsonb),
                               raw_rows = :raw,
                               modified_at = now()
                         where id = :id and status = 'verified'
                        """)
                .param("code", rejectCode)
                .param("params", jsonColumns.object(rejectParams))
                .param("raw", rawRows)
                .param("id", id)
                .update();
    }

    public void insertErrors(long packageId, List<ErrorRow> errors) {
        for (ErrorRow error : errors) {
            jdbc.sql("""
                            insert into upl_package_errors (package_id, ordinal, sheet, row_no, column_name,
                                                            cell_value, code, params)
                            values (:package, :ordinal, :sheet, :rowNo, :column, :value, :code,
                                    cast(:params as jsonb))
                            """)
                    .param("package", packageId)
                    .param("ordinal", error.ordinal())
                    .param("sheet", error.sheet())
                    .param("rowNo", error.rowNo())
                    .param("column", error.columnName())
                    .param("value", error.cellValue())
                    .param("code", error.code())
                    .param("params", errorColumns.object(error.params()))
                    .update();
        }
    }

    public List<ErrorRow> findErrors(long packageId) {
        return jdbc.sql(ERROR_SELECT)
                .param("package", packageId)
                .query(this::mapError)
                .list();
    }

    private PackageRow mapPackage(ResultSet rs, int rowNum) throws SQLException {
        return new PackageRow(
                rs.getLong("id"),
                rs.getObject("public_id", UUID.class),
                rs.getLong("source_id"),
                rs.getString("source_code"),
                rs.getString("source_name"),
                rs.getInt("format_version"),
                rs.getObject("period_from", LocalDate.class),
                rs.getObject("period_to", LocalDate.class),
                rs.getObject("file_id", UUID.class),
                rs.getString("file_name"),
                rs.getString("file_sha256"),
                rs.getLong("file_size_bytes"),
                rs.getString("status"),
                rs.getObject("rows_total", Integer.class),
                rs.getObject("rows_accepted", Integer.class),
                rs.getObject("rows_rejected", Integer.class),
                rs.getObject("errors_total", Integer.class),
                rs.getString("reject_code"),
                nullableObject(rs.getString("reject_params")),
                rs.getObject("load_id", Long.class),
                rs.getObject("raw_rows", Integer.class),
                toInstant(rs.getTimestamp("uploaded_at")),
                rs.getString("uploaded_by"));
    }

    private ErrorRow mapError(ResultSet rs, int rowNum) throws SQLException {
        return new ErrorRow(
                rs.getInt("ordinal"),
                rs.getString("sheet"),
                rs.getObject("row_no", Integer.class),
                rs.getString("column_name"),
                rs.getString("cell_value"),
                rs.getString("code"),
                nullableObject(rs.getString("params")));
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** A nullable JSON column: null stays null, a stored document is read (plan item 3.11). */
    private @Nullable Map<String, Object> nullableObject(@Nullable String raw) {
        return raw == null ? null : jsonColumns.readObject(raw);
    }
}
