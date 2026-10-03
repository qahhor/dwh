package com.smartup24.cms.platform.api.entity.field;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What an entity field is in the entity's list (ADR-0032, 3.1), the flags of a registry field (ADR-0016). A value
 * class rather than a record, so the declaration reads {@code sortable().searchable().hidden()}.
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public final class ListPart {

    /** A visible, filterable column. */
    public static final ListPart LISTED = new ListPart(true, false, false, false, true);

    private final boolean filterable;
    private final boolean sortable;
    private final boolean searchable;
    private final boolean nullable;
    private final boolean defaultVisible;

    private ListPart(
            boolean filterable, boolean sortable, boolean searchable, boolean nullable, boolean defaultVisible) {
        this.filterable = filterable;
        this.sortable = sortable;
        this.searchable = searchable;
        this.nullable = nullable;
        this.defaultVisible = defaultVisible;
    }

    /** The list sorts by it; such a field is never empty, or the keyset cursor loses rows. */
    public ListPart sortable() {
        return new ListPart(filterable, true, searchable, nullable, defaultVisible);
    }

    /** It takes part in the free-text search {@code q} (text only). */
    public ListPart searchable() {
        return new ListPart(filterable, sortable, true, nullable, defaultVisible);
    }

    /** It may be empty, so the filter offers {@code empty}/{@code not_empty}. */
    public ListPart nullable() {
        return new ListPart(filterable, sortable, searchable, true, defaultVisible);
    }

    /** It takes no conditions in the filter. */
    public ListPart notFilterable() {
        return new ListPart(false, sortable, searchable, nullable, defaultVisible);
    }

    /** A column the viewer has to switch on. */
    public ListPart hidden() {
        return new ListPart(filterable, sortable, searchable, nullable, false);
    }

    public boolean isFilterable() {
        return filterable;
    }

    public boolean isSortable() {
        return sortable;
    }

    public boolean isSearchable() {
        return searchable;
    }

    public boolean isNullable() {
        return nullable;
    }

    public boolean isDefaultVisible() {
        return defaultVisible;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof ListPart part
                && filterable == part.filterable
                && sortable == part.sortable
                && searchable == part.searchable
                && nullable == part.nullable
                && defaultVisible == part.defaultVisible;
    }

    @Override
    public int hashCode() {
        return Objects.hash(filterable, sortable, searchable, nullable, defaultVisible);
    }

    @Override
    public String toString() {
        return "ListPart[filterable=" + filterable + ", sortable=" + sortable + ", searchable=" + searchable
                + ", nullable=" + nullable + ", defaultVisible=" + defaultVisible + "]";
    }
}
