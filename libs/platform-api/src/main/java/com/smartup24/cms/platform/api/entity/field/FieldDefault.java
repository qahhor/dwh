package com.smartup24.cms.platform.api.entity.field;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The value a field takes when a new record is created without it (ADR-0032, 4.3). The server puts it in before the
 * checks; {@code form-meta} gives it to the form, which fills the field in ({@code {"kind":"today"}}).
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public sealed interface FieldDefault
        permits FieldDefault.Fixed,
                FieldDefault.Now,
                FieldDefault.Today,
                FieldDefault.CurrentUser,
                FieldDefault.CurrentOrgUnit,
                FieldDefault.Sequence {

    /**
     * The kind as the client sees it: {@code fixed}, {@code now}, {@code today}, {@code current_user},
     * {@code current_org_unit}, {@code sequence}.
     */
    String kind();

    /** The value of a fixed default, the pattern of a sequence; null for the others. */
    @Nullable
    String value();

    /** A fixed value, as the field takes it ({@code "default"}, {@code "true"}, {@code "10"}). */
    static FieldDefault fixed(String value) {
        return new Fixed(value);
    }

    /** The moment of creation. */
    static FieldDefault now() {
        return new Now();
    }

    /** The day of creation, in UTC. */
    static FieldDefault today() {
        return new Today();
    }

    /** The person who creates the record. */
    static FieldDefault currentUser() {
        return new CurrentUser();
    }

    /**
     * The home org unit of the person who creates the record (ADR-0032, 5.1): the unit of an
     * {@code EntityScope.orgUnit(...)} record by default; empty for a user without one.
     */
    static FieldDefault currentOrgUnit() {
        return new CurrentOrgUnit();
    }

    /**
     * The next number of the PostgreSQL sequence {@code name} (created by the module's migration) in {@code pattern},
     * where {@code {000000}} stands for the number padded to as many digits: {@code ORD-{000000}} gives
     * {@code ORD-000042}. A field with it is read-only.
     */
    static FieldDefault sequence(String name, String pattern) {
        return new Sequence(name, pattern);
    }

    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    record Fixed(String value) implements FieldDefault {
        public Fixed {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String kind() {
            return "fixed";
        }
    }

    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    record Now() implements FieldDefault {
        @Override
        public String kind() {
            return "now";
        }

        @Override
        public @Nullable String value() {
            return null;
        }
    }

    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    record Today() implements FieldDefault {
        @Override
        public String kind() {
            return "today";
        }

        @Override
        public @Nullable String value() {
            return null;
        }
    }

    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    record CurrentUser() implements FieldDefault {
        @Override
        public String kind() {
            return "current_user";
        }

        @Override
        public @Nullable String value() {
            return null;
        }
    }

    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    record CurrentOrgUnit() implements FieldDefault {
        @Override
        public String kind() {
            return "current_org_unit";
        }

        @Override
        public @Nullable String value() {
            return null;
        }
    }

    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    record Sequence(String name, String pattern) implements FieldDefault {

        /** The place of the number in the pattern: zeros in braces, as many as the digits. */
        public static final Pattern NUMBER = Pattern.compile("\\{(0+)}");

        public Sequence {
            if (name == null || !FieldSource.IDENTIFIER.matcher(name).matches()) {
                throw new IllegalArgumentException("Bad sequence name: " + name);
            }
            if (pattern == null || !NUMBER.matcher(pattern).find()) {
                throw new IllegalArgumentException("A sequence pattern holds its number as {000000}: " + pattern);
            }
        }

        @Override
        public String kind() {
            return "sequence";
        }

        @Override
        public String value() {
            return pattern;
        }

        /** The pattern with {@code number} in its place, padded with zeros. */
        public String format(long number) {
            var matcher = NUMBER.matcher(pattern);
            matcher.find();
            String digits = String.valueOf(number);
            int width = matcher.group(1).length();
            String padded = digits.length() >= width ? digits : "0".repeat(width - digits.length()) + digits;
            return pattern.substring(0, matcher.start()) + padded + pattern.substring(matcher.end());
        }
    }
}
