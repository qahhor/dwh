package com.smartup24.cms.instance.common.entity;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A tab of a record's card (ADR-0032, 9.3; plan 10/10, item 5.7): sections of the form, the rows of a collection, the
 * records of another entity that refer to this one — a related list, read through that entity's runtime list with its
 * own rights and scope, so the tab is offered only to a viewer of that entity — or the record's history.
 *
 * @param key        the tab's key, unique on the card
 * @param labelKey   the dictionary key of its title
 * @param kind       what it shows
 * @param sections   the sections of the form it shows, for {@link Kind#SECTIONS}
 * @param collection the collection it shows, for {@link Kind#COLLECTION}
 * @param entity     the entity whose records it lists, for {@link Kind#RELATED}
 * @param field      the reference field of that entity that names this record, for {@link Kind#RELATED}
 */
public record EntityTab(
        String key,
        String labelKey,
        Kind kind,
        List<String> sections,
        @Nullable String collection,
        @Nullable String entity,
        @Nullable String field) {

    /** What a tab shows. */
    public enum Kind {
        SECTIONS,
        COLLECTION,
        RELATED,
        HISTORY;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,63}$");

    public EntityTab {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Bad tab key: " + key);
        }
        Objects.requireNonNull(labelKey, "labelKey");
        Objects.requireNonNull(kind, "kind");
        sections = List.copyOf(sections);
        boolean fits = switch (kind) {
            case SECTIONS -> !sections.isEmpty() && collection == null && entity == null && field == null;
            case COLLECTION -> sections.isEmpty() && collection != null && entity == null && field == null;
            case RELATED -> sections.isEmpty() && collection == null && entity != null && field != null;
            case HISTORY -> sections.isEmpty() && collection == null && entity == null && field == null;
        };
        if (!fits) {
            throw new IllegalArgumentException("Tab " + key + " names what its kind " + kind.wire() + " shows, only");
        }
    }

    /** A tab of sections of the form. */
    public static EntityTab sections(String key, String labelKey, String... sections) {
        return new EntityTab(key, labelKey, Kind.SECTIONS, List.of(sections), null, null, null);
    }

    /** A tab of the rows of a collection. */
    public static EntityTab collection(String key, String labelKey, String collection) {
        return new EntityTab(key, labelKey, Kind.COLLECTION, List.of(), collection, null, null);
    }

    /** A tab of the records of {@code entity} whose reference {@code field} names this record. */
    public static EntityTab related(String key, String labelKey, String entity, String field) {
        return new EntityTab(key, labelKey, Kind.RELATED, List.of(), null, entity, field);
    }

    /** A tab of the record's history; the entity has the HISTORY capability. */
    public static EntityTab history(String key, String labelKey) {
        return new EntityTab(key, labelKey, Kind.HISTORY, List.of(), null, null, null);
    }
}
