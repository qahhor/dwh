package com.smartup24.cms.platform.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A semantic version ({@code MAJOR.MINOR.PATCH}, ADR-0033, 4): of the platform's API, of a module, or the least version
 * a module needs. A pre-release suffix ({@code 1.0.0-SNAPSHOT}) is kept for display and compares as its release.
 *
 * @param major      the major version: an incompatible change of the API raises it
 * @param minor      the minor version: a compatible addition raises it
 * @param patch      the patch version
 * @param preRelease the pre-release suffix without its dash, or null
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record PlatformVersion(
        int major, int minor, int patch, @Nullable String preRelease) implements Comparable<PlatformVersion> {

    private static final Pattern FORMAT =
            Pattern.compile("^(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(?:-([0-9A-Za-z.-]+))?$");

    /** The resource of the API's jar that holds its version, written by the build. */
    public static final String RESOURCE = "/META-INF/smartupcms/platform-api.properties";

    public PlatformVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("A version has no negative part");
        }
        if (preRelease != null && preRelease.isBlank()) {
            throw new IllegalArgumentException("An empty pre-release suffix");
        }
    }

    /**
     * Reads {@code 1.2.3} or {@code 1.2.3-SNAPSHOT}.
     *
     * @throws IllegalArgumentException for anything else
     */
    public static PlatformVersion parse(String text) {
        Matcher matcher = FORMAT.matcher(Objects.requireNonNull(text, "version").strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a version MAJOR.MINOR.PATCH: " + text);
        }
        return new PlatformVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                matcher.group(4));
    }

    /** The version of the platform's API this jar is (ADR-0033, 4). */
    public static PlatformVersion current() {
        try (InputStream in = PlatformVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The platform API jar has no " + RESOURCE);
            }
            Properties properties = new Properties();
            properties.load(in);
            return parse(Objects.requireNonNull(properties.getProperty("version"), "version"));
        } catch (IOException e) {
            throw new UncheckedIOException("The version of the platform API is unreadable", e);
        }
    }

    /**
     * Whether something that needs {@code required} runs on this version: the same major version and no lower than
     * it (ADR-0033, 6.3). Pre-release suffixes are not compared.
     */
    public boolean satisfies(PlatformVersion required) {
        return major == required.major && compareTo(required) >= 0;
    }

    /** Orders by major, minor and patch; the pre-release suffix does not count. */
    @Override
    public int compareTo(PlatformVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) return byMajor;
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    /** {@code MAJOR.MINOR}, the form of {@link PlatformApi#since()}. */
    public String feature() {
        return major + "." + minor;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease == null ? "" : "-" + preRelease);
    }
}
