package com.smartup24.cms.instance.studio.repository;

import com.smartup24.cms.instance.common.query.TimePage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Automations of live forms (ADR-0046), the active declaration and lifecycle of their forms, and the authors of the
 * records they act on. A change names the revision it was made from and matches no row when it is stale; the
 * scheduler moves the next look of a scheduled automation without a revision, as bookkeeping of its own. Users are read
 * through md's published view (ADR-0026).
 */
@Repository
public class StudioAutomationRepository {
    private static final String SELECT = """
            select a.id, a.form_code, a.name, a.trigger_kind, a.trigger::text as trigger, a.condition,
                   a.actions::text as actions, a.time_zone, a.active, a.next_run_at, a.author_id,
                   coalesce(u.name, '') as author_name, a.revision, a.created_at, a.modified_at
            from studio_automations a
            left join md_pub_users u on u.id = a.author_id
            """;

    private final JdbcClient jdbc;

    /** An automation as stored: its trigger and actions as JSON text. */
    public record AutomationRow(
            long id,
            String form,
            String name,
            String triggerKind,
            String trigger,
            @Nullable String condition,
            String actions,
            String timeZone,
            boolean active,
            @Nullable Instant nextRunAt,
            long authorId,
            String authorName,
            long revision,
            Instant createdAt,
            Instant modifiedAt) {}

    /** What a change writes: the trigger and actions as JSON text. */
    public record Values(
            String name,
            String triggerKind,
            String trigger,
            @Nullable String condition,
            String actions,
            String timeZone,
            boolean active,
            @Nullable Instant nextRunAt) {}

    /** The active declaration of a live form as stored, and whether the form is archived. */
    public record FormRow(String declaration, boolean archived) {}

    public StudioAutomationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The automations of a form, or of every form, the newest first; {@code limit + 1} rows tell if more follow. */
    public List<AutomationRow> page(@Nullable String form, TimePage page) {
        TimePage.Position after = page.after();
        StringBuilder where = new StringBuilder(" where true");
        if (form != null) where.append(" and a.form_code = :form");
        if (after != null) where.append(" and (a.created_at, a.id) < (:at, :id)");
        var statement = jdbc.sql(SELECT + where + " order by a.created_at desc, a.id desc limit :limit")
                .param("limit", page.limit() + 1);
        if (form != null) statement = statement.param("form", form);
        if (after != null) {
            statement = statement
                    .param("at", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                    .param("id", after.id());
        }
        return statement.query((rs, row) -> row(rs)).list();
    }

    public Optional<AutomationRow> find(long id) {
        return jdbc.sql(SELECT + " where a.id = :id")
                .param("id", id)
                .query((rs, row) -> row(rs))
                .optional();
    }

    /** The switched-on automations of a form whose trigger is one of {@code kinds}, in the order they were made. */
    public List<AutomationRow> active(String form, Collection<String> kinds) {
        return jdbc.sql(SELECT + " where a.form_code = :form and a.active and a.trigger_kind in (:kinds) order by a.id")
                .param("form", form)
                .param("kinds", List.copyOf(kinds))
                .query((rs, row) -> row(rs))
                .list();
    }

    /** The switched-on automations of the forms named, for the check of a new version of a form. */
    public List<AutomationRow> activeOf(Collection<String> forms) {
        if (forms.isEmpty()) return List.of();
        return jdbc.sql(SELECT + " where a.active and a.form_code in (:forms) order by a.id")
                .param("forms", List.copyOf(forms))
                .query((rs, row) -> row(rs))
                .list();
    }

    /** The switched-on automations whose actions create records of {@code form}. */
    public List<AutomationRow> creatingIn(String form) {
        return jdbc.sql(SELECT + " where a.active and a.actions @> cast(:probe as jsonb) order by a.id")
                .param("probe", "[{\"type\": \"create_record\", \"form\": \"" + form.replace("\"", "") + "\"}]")
                .query((rs, row) -> row(rs))
                .list();
    }

    public long count() {
        Long count = jdbc.sql("select count(*) from studio_automations")
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    public long countFor(String form) {
        Long count = jdbc.sql("select count(*) from studio_automations where form_code = :form")
                .param("form", form)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /** Serializes the creation of automations against their limits. */
    public void lock() {
        jdbc.sql("select pg_advisory_xact_lock(756402046)").query(Object.class).single();
    }

    public long insert(String form, Values values, long userId) {
        Long id = jdbc.sql("""
                        insert into studio_automations
                            (form_code, name, trigger_kind, trigger, condition, actions, time_zone, active, next_run_at,
                             author_id, modified_by)
                        values (:form, :name, :kind, cast(:trigger as jsonb), :condition, cast(:actions as jsonb),
                                :zone, :active, :next, :user, :user)
                        returning id
                        """)
                .param("form", form)
                .params(params(values))
                .param("user", userId)
                .query(Long.class)
                .single();
        return id == null ? 0 : id;
    }

    /** Changes the automation from {@code revision}; the person who saves it becomes its author. */
    public boolean update(long id, long revision, Values values, long userId) {
        return jdbc.sql("""
                        update studio_automations
                        set name = :name, trigger_kind = :kind, trigger = cast(:trigger as jsonb),
                            condition = :condition, actions = cast(:actions as jsonb), time_zone = :zone,
                            active = :active, next_run_at = :next, author_id = :user, modified_by = :user,
                            modified_at = clock_timestamp(), revision = revision + 1
                        where id = :id and revision = :revision
                        """)
                        .param("id", id)
                        .param("revision", revision)
                        .params(params(values))
                        .param("user", userId)
                        .update()
                == 1;
    }

    public boolean delete(long id, long revision) {
        return jdbc.sql("delete from studio_automations where id = :id and revision = :revision")
                        .param("id", id)
                        .param("revision", revision)
                        .update()
                == 1;
    }

    /** The due scheduled automations, locked for this tick; another node skips them. */
    public List<AutomationRow> lockDue(Instant now, int limit) {
        return jdbc.sql(SELECT
                        + " where a.id in (select id from studio_automations where active and trigger_kind = 'scheduled'"
                        + " and next_run_at <= :now order by next_run_at limit :limit for update skip locked)")
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("limit", limit)
                .query((rs, row) -> row(rs))
                .list();
    }

    public void moveNextRun(long id, Instant next) {
        jdbc.sql("update studio_automations set next_run_at = :next where id = :id")
                .param("id", id)
                .param("next", OffsetDateTime.ofInstant(next, ZoneOffset.UTC))
                .update();
    }

    /** The active declaration of a live form and its lifecycle, without counting its records. */
    public Optional<FormRow> form(String code) {
        return jdbc.sql("""
                        select coalesce(v.declaration, f.declaration)::text as declaration,
                               coalesce(s.status, 'active') <> 'active' as archived
                        from studio_forms f
                        left join studio_form_state s on s.form_id = f.id
                        left join studio_form_versions v on v.form_id = f.id and v.version = s.active_version
                        where f.code = :code
                        """)
                .param("code", code)
                .query((rs, row) -> new FormRow(rs.getString("declaration"), rs.getBoolean("archived")))
                .optional();
    }

    /** Whether the record of the live form exists, whoever may see it. */
    public boolean recordExists(String form, long recordId) {
        return jdbc.sql("""
                        select exists (select 1 from studio_records r join studio_forms f on f.id = r.form_id
                                       where f.code = :form and r.id = :id)
                        """)
                .param("form", form)
                .param("id", recordId)
                .query(Boolean.class)
                .single();
    }

    /** Who created the record of the live form, if it still exists. */
    public Optional<Long> recordAuthor(String form, long recordId) {
        return jdbc.sql("""
                        select r.created_by from studio_records r join studio_forms f on f.id = r.form_id
                        where f.code = :form and r.id = :id
                        """)
                .param("form", form)
                .param("id", recordId)
                .query(Long.class)
                .optional();
    }

    /** The active users among {@code ids} with their names, in the order of the ids. */
    public Map<Long, String> activeUsers(Collection<Long> ids) {
        Map<Long, String> users = new LinkedHashMap<>();
        if (ids.isEmpty()) return users;
        Map<Long, String> found = new LinkedHashMap<>();
        jdbc.sql("select id, name from md_pub_users where id in (:ids) and state = 'A'")
                .param("ids", List.copyOf(ids))
                .query(rs -> {
                    while (rs.next()) found.put(rs.getLong("id"), rs.getString("name"));
                    return found;
                });
        for (Long id : ids) {
            if (found.containsKey(id)) users.put(id, found.get(id));
        }
        return users;
    }

    private static Map<String, Object> params(Values values) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", values.name());
        params.put("kind", values.triggerKind());
        params.put("trigger", values.trigger());
        params.put("condition", values.condition());
        params.put("actions", values.actions());
        params.put("zone", values.timeZone());
        params.put("active", values.active());
        params.put(
                "next",
                values.nextRunAt() == null ? null : OffsetDateTime.ofInstant(values.nextRunAt(), ZoneOffset.UTC));
        return params;
    }

    private static AutomationRow row(ResultSet rs) throws SQLException {
        return new AutomationRow(
                rs.getLong("id"),
                rs.getString("form_code"),
                rs.getString("name"),
                rs.getString("trigger_kind"),
                rs.getString("trigger"),
                rs.getString("condition"),
                rs.getString("actions"),
                rs.getString("time_zone"),
                rs.getBoolean("active"),
                instant(rs.getTimestamp("next_run_at")),
                rs.getLong("author_id"),
                rs.getString("author_name"),
                rs.getLong("revision"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("modified_at").toInstant());
    }

    static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
