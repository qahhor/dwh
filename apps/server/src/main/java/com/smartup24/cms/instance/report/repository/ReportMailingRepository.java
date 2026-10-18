package com.smartup24.cms.instance.report.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Report mailings and their recipients (ADR-0045). A change names the revision it was made from and matches no row when
 * it is stale; the scheduler moves the next send without a revision, as bookkeeping of its own. Users are read through
 * md's published view (ADR-0026).
 */
@Repository
public class ReportMailingRepository {
    private static final String SELECT = """
            select m.id, m.entity_code, m.view_id, m.name, m.channels, m.attach_xlsx, m.include_link, m.frequency,
                   m.weekday, m.month_day, m.send_time, m.time_zone, m.parameters::text as parameters,
                   m.condition::text as condition, m.active, m.next_run_at, m.owner_id, m.revision, m.created_at,
                   m.modified_at
            from report_mailings m
            """;

    private final JdbcClient jdbc;

    /** A mailing as stored: its JSON parts as text. */
    public record MailingRow(
            long id,
            String entity,
            long viewId,
            String name,
            List<String> channels,
            boolean attachXlsx,
            boolean includeLink,
            String frequency,
            @Nullable Integer weekday,
            @Nullable Integer monthDay,
            LocalTime time,
            String timeZone,
            String parameters,
            String condition,
            boolean active,
            @Nullable Instant nextRunAt,
            long ownerId,
            long revision,
            Instant createdAt,
            Instant modifiedAt) {}

    /** What a change writes: the JSON parts as text. */
    public record Values(
            String name,
            List<String> channels,
            boolean attachXlsx,
            boolean includeLink,
            String frequency,
            @Nullable Integer weekday,
            @Nullable Integer monthDay,
            LocalTime time,
            String timeZone,
            String parameters,
            String condition,
            boolean active,
            @Nullable Instant nextRunAt) {}

    /** A user as a mailing names it; state {@code A} is active. */
    public record RecipientRow(long id, String name, String login, String state) {}

    public ReportMailingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The mailings of a saved report, the newest first; {@code limit + 1} rows tell whether more follow. */
    public List<MailingRow> page(long viewId, TimePage page) {
        TimePage.Position after = page.after();
        String where = " where m.view_id = :view" + (after == null ? "" : " and (m.created_at, m.id) < (:at, :id)");
        var statement = jdbc.sql(SELECT + where + " order by m.created_at desc, m.id desc limit :limit")
                .param("view", viewId)
                .param("limit", page.limit() + 1);
        if (after != null) {
            statement = statement
                    .param("at", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                    .param("id", after.id());
        }
        return statement.query((rs, row) -> row(rs)).list();
    }

    public Optional<MailingRow> find(long id) {
        return jdbc.sql(SELECT + " where m.id = :id")
                .param("id", id)
                .query((rs, row) -> row(rs))
                .optional();
    }

    public long count() {
        Long count = jdbc.sql("select count(*) from report_mailings")
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    public long countFor(long viewId) {
        Long count = jdbc.sql("select count(*) from report_mailings where view_id = :view")
                .param("view", viewId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /** Serializes the creation of mailings against their limits. */
    public void lock() {
        jdbc.sql("select pg_advisory_xact_lock(756402045)").query(Object.class).single();
    }

    public long insert(String entity, long viewId, Values values, long userId) {
        return jdbc.sql("""
                        insert into report_mailings
                            (entity_code, view_id, name, channels, attach_xlsx, include_link, frequency, weekday,
                             month_day, send_time, time_zone, parameters, condition, active, next_run_at, owner_id,
                             modified_by)
                        values (:entity, :view, :name, :channels, :attach, :link, :frequency, :weekday, :monthDay,
                                :time, :zone, cast(:parameters as jsonb), cast(:condition as jsonb), :active, :next,
                                :user, :user)
                        returning id
                        """)
                .param("entity", entity)
                .param("view", viewId)
                .params(params(values))
                .param("user", userId)
                .query(Long.class)
                .single();
    }

    /** Writes the mailing when it is still at {@code revision}; false when it is stale or gone. */
    public boolean update(long id, long revision, Values values, long userId) {
        return jdbc.sql("""
                        update report_mailings
                           set name = :name, channels = :channels, attach_xlsx = :attach, include_link = :link,
                               frequency = :frequency, weekday = :weekday, month_day = :monthDay, send_time = :time,
                               time_zone = :zone, parameters = cast(:parameters as jsonb),
                               condition = cast(:condition as jsonb), active = :active, next_run_at = :next,
                               revision = revision + 1, modified_by = :user, modified_at = clock_timestamp()
                         where id = :id and revision = :revision
                        """)
                        .param("id", id)
                        .param("revision", revision)
                        .params(params(values))
                        .param("user", userId)
                        .update()
                == 1;
    }

    /** Deletes the mailing when it is still at {@code revision}; its recipients and history go with it. */
    public boolean delete(long id, long revision) {
        return jdbc.sql("delete from report_mailings where id = :id and revision = :revision")
                        .param("id", id)
                        .param("revision", revision)
                        .update()
                == 1;
    }

    public void replaceRecipients(long mailingId, Collection<Long> userIds) {
        jdbc.sql("delete from report_mailing_recipients where mailing_id = :id")
                .param("id", mailingId)
                .update();
        jdbc.sql("""
                        insert into report_mailing_recipients (mailing_id, user_id)
                        select :id, unnest(cast(:users as bigint[]))
                        """)
                .param("id", mailingId)
                .param("users", userIds.toArray(Long[]::new))
                .update();
    }

    /** The recipients of each mailing, by name. */
    public Map<Long, List<RecipientRow>> recipients(Collection<Long> mailingIds) {
        Map<Long, List<RecipientRow>> recipients = new LinkedHashMap<>();
        if (mailingIds.isEmpty()) return recipients;
        jdbc.sql("""
                        select r.mailing_id, u.id, u.name, u.login, u.state
                        from report_mailing_recipients r
                        join md_pub_users u on u.id = r.user_id
                        where r.mailing_id in (:ids)
                        order by u.name, u.id
                        """).param("ids", List.copyOf(mailingIds)).query(rs -> {
            while (rs.next()) {
                recipients
                        .computeIfAbsent(rs.getLong("mailing_id"), key -> new ArrayList<>())
                        .add(new RecipientRow(
                                rs.getLong("id"), rs.getString("name"), rs.getString("login"), rs.getString("state")));
            }
            return recipients;
        });
        return recipients;
    }

    /** The users among {@code ids} that exist, with their state. */
    public Map<Long, String> userStates(Collection<Long> ids) {
        Map<Long, String> states = new HashMap<>();
        if (ids.isEmpty()) return states;
        jdbc.sql("select id, state from md_pub_users where id in (:ids)")
                .param("ids", List.copyOf(ids))
                .query(rs -> {
                    while (rs.next()) states.put(rs.getLong("id"), rs.getString("state"));
                    return states;
                });
        return states;
    }

    /** Locks the active mailings due at {@code now}, the oldest due first; other nodes skip them meanwhile. */
    public List<MailingRow> lockDue(Instant now, int limit) {
        return jdbc.sql(SELECT + """
                         where m.active and m.next_run_at <= :now
                         order by m.next_run_at, m.id
                         limit :limit
                         for update skip locked
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rs, row) -> row(rs))
                .list();
    }

    /** Moves the next send of a mailing the scheduler has taken. */
    public void moveNextRun(long id, Instant next) {
        jdbc.sql("update report_mailings set next_run_at = :next where id = :id and active")
                .param("id", id)
                .param("next", Timestamp.from(next))
                .update();
    }

    private static Map<String, Object> params(Values values) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", values.name());
        params.put("channels", values.channels().toArray(String[]::new));
        params.put("attach", values.attachXlsx());
        params.put("link", values.includeLink());
        params.put("frequency", values.frequency());
        params.put("weekday", values.weekday());
        params.put("monthDay", values.monthDay());
        params.put("time", values.time());
        params.put("zone", values.timeZone());
        params.put("parameters", values.parameters());
        params.put("condition", values.condition());
        params.put("active", values.active());
        params.put("next", values.nextRunAt() == null ? null : Timestamp.from(values.nextRunAt()));
        return params;
    }

    private static MailingRow row(ResultSet rs) throws SQLException {
        Array channels = rs.getArray("channels");
        Timestamp next = rs.getTimestamp("next_run_at");
        return new MailingRow(
                rs.getLong("id"),
                rs.getString("entity_code"),
                rs.getLong("view_id"),
                rs.getString("name"),
                Arrays.asList((String[]) channels.getArray()),
                rs.getBoolean("attach_xlsx"),
                rs.getBoolean("include_link"),
                rs.getString("frequency"),
                rs.getObject("weekday", Integer.class),
                rs.getObject("month_day", Integer.class),
                rs.getObject("send_time", LocalTime.class),
                rs.getString("time_zone"),
                rs.getString("parameters"),
                rs.getString("condition"),
                rs.getBoolean("active"),
                next == null ? null : next.toInstant(),
                rs.getLong("owner_id"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("modified_at").toInstant());
    }
}
