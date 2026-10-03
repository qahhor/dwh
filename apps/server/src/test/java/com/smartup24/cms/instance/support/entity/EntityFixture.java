package com.smartup24.cms.instance.support.entity;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The data an entity's contract test adds to what the kit derives from the declaration (ADR-0032, 11.1): values the
 * kit cannot make up (a reference to another record, a file, an enumeration item, a value of a pattern), the change
 * the update makes, and invalid values of its own rules. Users, roles and scopes are the kit's.
 *
 * <pre>{@code
 * return EntityFixture.valid(Map.of("customerId", ctx.anyUserId()))
 *         .update(Map.of("comment", "changed"))
 *         .invalid("currency", "usd", "invalid");
 * }</pre>
 */
@PlatformApi(since = "1.0", stability = Stability.EXPERIMENTAL)
public final class EntityFixture {

    /** An invalid value of a field and the code of the problem the server answers with. */
    public record Invalid(String field, @Nullable Object value, String code) {}

    private final Map<String, Object> valid;
    private final Map<String, Object> update = new LinkedHashMap<>();
    private final List<Invalid> invalid = new ArrayList<>();

    private EntityFixture(Map<String, Object> valid) {
        this.valid = new LinkedHashMap<>(valid);
    }

    /** Only what the kit derives from the declaration. */
    public static EntityFixture derived() {
        return new EntityFixture(Map.of());
    }

    /** These values on top of the derived ones in every record the kit creates. */
    public static EntityFixture valid(Map<String, ?> values) {
        return new EntityFixture(Map.copyOf(values));
    }

    /** The change the update makes, instead of a new value of the first text field. */
    public EntityFixture update(Map<String, ?> values) {
        update.putAll(values);
        return this;
    }

    /** One more invalid value: a create with it answers 422 with {@code code} on {@code field}. */
    public EntityFixture invalid(String field, @Nullable Object value, String code) {
        invalid.add(new Invalid(field, value, code));
        return this;
    }

    Map<String, Object> validValues() {
        return valid;
    }

    Map<String, Object> updateValues() {
        return update;
    }

    List<Invalid> invalidValues() {
        return invalid;
    }
}
