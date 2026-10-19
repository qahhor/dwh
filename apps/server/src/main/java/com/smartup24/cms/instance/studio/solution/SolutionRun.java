package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Placeholder;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One check or application of a solution package (ADR-0047): the package under its suffix, the scope its objects are
 * remembered in, the mapping of its placeholders and what the earlier items made for the later ones. A check maps an
 * unmapped required placeholder of users to the importer, so the rest of the item is still checked.
 *
 * @param apply    whether the run keeps what it does
 * @param importId the import an application remembers its objects with; 0 for a check of an upload
 */
public record SolutionRun(
        SolutionPackage pack,
        String scope,
        Map<String, List<Long>> mapping,
        boolean apply,
        long importId,
        long importer,
        Map<String, Long> reports) {

    public SolutionRun(
            SolutionPackage pack,
            String scope,
            Map<String, List<Long>> mapping,
            boolean apply,
            long importId,
            long importer) {
        this(pack, scope, Map.copyOf(mapping), apply, importId, importer, new HashMap<>());
    }

    /** The users a placeholder is mapped to; in a check, the importer for a required one that is not mapped. */
    public List<Long> users(@Nullable String key) {
        if (key == null) return List.of();
        List<Long> mapped = mapping.get(key);
        if (mapped != null && !mapped.isEmpty()) return mapped;
        return !apply && placeholder(key).map(Placeholder::required).orElse(false) ? List.of(importer) : List.of();
    }

    /** Whether the placeholder is mapped. */
    public boolean mapped(@Nullable String key) {
        return key != null && mapping.containsKey(key) && !mapping.get(key).isEmpty();
    }

    /** The role a placeholder is mapped to, or null. */
    public @Nullable Long role(String key) {
        List<Long> mapped = mapping.get(key);
        return mapped == null || mapped.isEmpty() ? null : mapped.getFirst();
    }

    public Optional<Placeholder> placeholder(String key) {
        return pack.placeholders().stream()
                .filter(placeholder -> placeholder.key().equals(key))
                .findFirst();
    }

    /** The name of a placeholder, for the item of its grant. */
    public Labels title(String key) {
        return placeholder(key).map(Placeholder::labels).orElse(new Labels(key, key, key));
    }
}
