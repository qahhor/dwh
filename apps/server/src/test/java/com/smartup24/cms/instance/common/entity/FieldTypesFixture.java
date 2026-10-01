package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.instance.common.entity.field.EntityFields.bool;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.date;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.email;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.enumeration;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.file;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.hidden;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.image;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.instant;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.json;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.markdown;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.money;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.multiRef;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.number;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.phone;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.ref;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.searchable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.select;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.sortable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.text;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.textarea;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.time;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.url;

import com.smartup24.cms.instance.common.entity.field.FieldCondition;
import com.smartup24.cms.instance.common.entity.field.FieldDefault;
import com.smartup24.cms.instance.common.entity.field.FieldOptions.JsonRoot;
import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.entity.field.FieldType;
import com.smartup24.cms.instance.common.query.QueryRef;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A test-only entity with a field of every type (plan 10/10, item 5.2; ADR-0032, 4.8) and the reference entity of its
 * enumeration. Not a bean: the application's entities stay as they are (the notes snapshot), and the reference
 * document of item 5.7 brings the types to a real entity. The tables are created by {@link #DDL} in a database of the
 * test's own.
 */
public final class FieldTypesFixture {

    public static final String CODE = "test.field_types";
    public static final String UNITS = "test.units";
    public static final QueryRef USERS = QueryRef.paged("/iam/users", "name");
    public static final QueryRef UNITS_REF = QueryRef.paged("/entities/test-units", "name");
    public static final List<String> CURRENCIES = List.of("UZS", "USD");

    /** The reference entity of the enumeration: units of measure, archived instead of deleted (ADR-0032, 5.4). */
    public static final EntityDefinition REFERENCE = Entity.define(UNITS, "test_units")
            .table("test_units", "u")
            .scope(EntityScope.all())
            .archivable()
            .reference("code", "name")
            .field(text("code", "test.col.code")
                    .column("code")
                    .required()
                    .readonlyOnUpdate()
                    .list(sortable()))
            .field(text("name", "test.col.name").column("name").required())
            .section("main", "entity.section.main", "code", "name")
            .defaultSort("code", Entity.Sort.ASC)
            .build();

    /** One field of every type, and the flags of the form: a default, read-only kinds and a condition. */
    public static final EntityDefinition DEFINITION = Entity.define(CODE, "test_field_types")
            .table("test_field_types", "t")
            .scope(EntityScope.all())
            .field(text("title", "test.col.title")
                    .column("title")
                    .required()
                    .length(1, 200)
                    .list(sortable().searchable()))
            .field(textarea("body", "test.col.body")
                    .column("body")
                    .length(null, 2000)
                    .list(searchable().hidden()))
            .field(markdown("notes", "test.col.notes")
                    .column("notes")
                    .length(null, 5000)
                    .list(hidden()))
            .field(number("qty", "test.col.qty").column("qty").scale(2).list(sortable()))
            .field(date("due", "test.col.due").column("due").defaultValue(FieldDefault.today()))
            .field(instant("startsAt", "test.col.starts_at").column("starts_at"))
            .field(time("callTime", "test.col.call_time").column("call_time"))
            .field(bool("active", "test.col.active").column("active").defaultValue(FieldDefault.fixed("true")))
            .field(select("kind", "test.col.kind", List.of("retail", "wholesale"), "test.kind_")
                    .column("kind")
                    .required()
                    .defaultValue(FieldDefault.fixed("retail")))
            .field(ref("ownerId", "test.col.owner", USERS)
                    .column("owner_id")
                    .visibleWhen(FieldCondition.eq("kind", "wholesale"))
                    .required())
            .field(email("email", "test.col.email").column("email"))
            .field(phone("phone", "test.col.phone").column("phone"))
            .field(url("site", "test.col.site").column("site"))
            .field(money("total", "test.col.total", CURRENCIES.toArray(String[]::new))
                    .money("total_amount", "total_currency")
                    .range(BigDecimal.ZERO, null)
                    .list(sortable()))
            .field(enumeration("unit", "test.col.unit", UNITS).column("unit"))
            .field(multiRef("tagIds", "test.col.tags", UNITS_REF)
                    .link("test_field_type_tags", "record_id", "unit_id")
                    .maxItems(3))
            .field(file("attachment", "test.col.attachment")
                    .column("attachment_id")
                    .files(1_000_000L))
            .field(image("photo", "test.col.photo").column("photo_id"))
            .field(json("extra", "test.col.extra", JsonRoot.OBJECT).column("extra"))
            .field(number("doubled", "test.col.doubled").computed("(t.qty * 2)"))
            .field(text("number", "test.col.number")
                    .column("number")
                    .defaultValue(FieldDefault.sequence("test_field_types_number_seq", "T-{0000}")))
            .field(text("code", "test.col.code").column("code").readonlyOnUpdate())
            .field(text("region", "test.col.region").attribute("region").list(hidden()))
            .field(number("level", "test.col.level").attribute("level").list(hidden()))
            .field(instant("modifiedAt", "test.col.modified_at")
                    .system(SystemColumn.MODIFIED_AT)
                    .list(sortable()))
            .section(
                    "main",
                    "entity.section.main",
                    "title",
                    "body",
                    "notes",
                    "qty",
                    "due",
                    "startsAt",
                    "callTime",
                    "active",
                    "kind",
                    "ownerId",
                    "email",
                    "phone",
                    "site",
                    "total",
                    "unit",
                    "tagIds",
                    "attachment",
                    "photo",
                    "extra",
                    "doubled",
                    "number",
                    "code",
                    "region",
                    "level")
            .defaultSort("modifiedAt", Entity.Sort.DESC)
            .build();

    /** The tables of both entities, the link table and the sequence (ADR-0032, 14.1). */
    public static final String DDL = """
            create table test_units (
                id bigint generated always as identity primary key,
                code text not null unique,
                name text not null,
                sort_order integer not null default 0,
                archived_at timestamptz,
                archived_by bigint references md_users (id),
                attributes jsonb not null default '{}',
                created_by bigint, modified_by bigint,
                created_at timestamptz not null default clock_timestamp(),
                modified_at timestamptz not null default clock_timestamp(),
                revision bigint not null default 1);
            create sequence test_field_types_number_seq;
            create table test_field_types (
                id bigint generated always as identity primary key,
                title text not null, body text, notes text, qty numeric(19, 4), due date, starts_at timestamptz,
                call_time time, active boolean not null default false, kind text not null default 'retail',
                owner_id bigint references md_users (id), email text, phone text, site text,
                total_amount numeric(19, 4), total_currency text check (total_currency ~ '^[A-Z]{3}$'),
                unit text references test_units (code), attachment_id uuid references mf_files (id),
                photo_id uuid references mf_files (id), extra jsonb check (jsonb_typeof(extra) in ('object', 'array')),
                number text, code text,
                attributes jsonb not null default '{}',
                created_by bigint, modified_by bigint,
                created_at timestamptz not null default clock_timestamp(),
                modified_at timestamptz not null default clock_timestamp(),
                revision bigint not null default 1);
            create table test_field_type_tags (
                record_id bigint not null references test_field_types (id) on delete cascade,
                unit_id bigint not null references test_units (id),
                position integer not null,
                primary key (record_id, unit_id));
            """;

    private FieldTypesFixture() {}

    /** A valid value and an invalid one of each type, as a client sends them. */
    public static Map<FieldType, List<Object>> samples(String fileId) {
        Map<FieldType, List<Object>> samples = new LinkedHashMap<>();
        samples.put(FieldType.TEXT, List.of("Shelf", "x".repeat(201)));
        samples.put(FieldType.TEXTAREA, List.of("Two\nlines", "x".repeat(2001)));
        samples.put(FieldType.MARKDOWN, List.of("**bold**", "x".repeat(5001)));
        samples.put(FieldType.NUMBER, List.of("12.5", "12.555"));
        samples.put(FieldType.DATE, List.of("2026-10-01", "01.10.2026"));
        samples.put(FieldType.DATETIME, List.of("2026-10-01T09:30:00Z", "2026-10-01 09:30"));
        samples.put(FieldType.TIME, List.of("09:30", "25:00"));
        samples.put(FieldType.BOOLEAN, List.of(true, "maybe"));
        samples.put(FieldType.SELECT, List.of("wholesale", "other"));
        samples.put(FieldType.REF, List.of(1L, List.of(1L)));
        samples.put(FieldType.EMAIL, List.of("Ann@Example.com", "ann@"));
        samples.put(FieldType.PHONE, List.of("+998 (90) 123-45-67", "8901234567"));
        samples.put(FieldType.URL, List.of("https://example.com/a", "ftp://example.com"));
        samples.put(
                FieldType.MONEY,
                List.of(Map.of("amount", "1250.50", "currency", "UZS"), Map.of("amount", "10", "currency", "EUR")));
        samples.put(FieldType.ENUM, List.of("kg", 7));
        samples.put(FieldType.MULTI_REF, List.of(List.of(1L), List.of(1L, 1L)));
        samples.put(FieldType.FILE, List.of(fileId, "not-a-uuid"));
        samples.put(FieldType.IMAGE, List.of(fileId, "not-a-uuid"));
        samples.put(FieldType.JSON, List.of(Map.of("a", 1), "[1, 2]"));
        return samples;
    }

    /** The key of the fixture's field of each type. */
    public static String keyOf(FieldType type) {
        return switch (type) {
            case TEXT -> "title";
            case TEXTAREA -> "body";
            case MARKDOWN -> "notes";
            case NUMBER -> "qty";
            case DATE -> "due";
            case DATETIME -> "startsAt";
            case TIME -> "callTime";
            case BOOLEAN -> "active";
            case SELECT -> "kind";
            case REF -> "ownerId";
            case EMAIL -> "email";
            case PHONE -> "phone";
            case URL -> "site";
            case MONEY -> "total";
            case ENUM -> "unit";
            case MULTI_REF -> "tagIds";
            case FILE -> "attachment";
            case IMAGE -> "photo";
            case JSON -> "extra";
        };
    }
}
