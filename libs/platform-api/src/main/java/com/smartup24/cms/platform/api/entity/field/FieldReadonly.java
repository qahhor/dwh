package com.smartup24.cms.platform.api.entity.field;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * When a form field cannot be changed by a save (ADR-0032, 4.4): always (the server writes it — a hook, a default, a
 * computed value), once the record exists (a reference code set on creation), or while a condition holds (a posted
 * document). A value of a read-only field in a save that equals the record's is passed over, so a client may send the
 * whole record; a different one is refused with {@code readonly}.
 *
 * @param mode when the field is read-only
 * @param when the condition of {@link Mode#WHEN}, null otherwise
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record FieldReadonly(Mode mode, @Nullable FieldCondition when) {

    /** When a field is read-only. */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    public enum Mode {
        ALWAYS,
        ON_UPDATE,
        WHEN;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final FieldReadonly ALWAYS = new FieldReadonly(Mode.ALWAYS, null);
    public static final FieldReadonly ON_UPDATE = new FieldReadonly(Mode.ON_UPDATE, null);

    public FieldReadonly {
        Objects.requireNonNull(mode, "mode");
        if ((mode == Mode.WHEN) != (when != null)) {
            throw new IllegalArgumentException("A read-only condition goes with WHEN, and WHEN needs one");
        }
    }

    public static FieldReadonly when(FieldCondition condition) {
        return new FieldReadonly(Mode.WHEN, condition);
    }

    /**
     * Whether the field is read-only for this save.
     *
     * @param creating the save creates the record
     * @param current  the record's values before the save (the condition is tested over them); empty on creation
     */
    public boolean applies(boolean creating, Map<String, ?> current) {
        return switch (mode) {
            case ALWAYS -> true;
            case ON_UPDATE -> !creating;
            case WHEN -> !creating && Objects.requireNonNull(when).test(current);
        };
    }
}
