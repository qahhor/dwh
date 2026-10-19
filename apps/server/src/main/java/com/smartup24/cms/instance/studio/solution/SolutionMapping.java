package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdRoleService;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Placeholder;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.SolutionPackage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The mapping of a package's placeholders to this installation (ADR-0047): a role placeholder takes one active role, a
 * placeholder of users one to fifty active users; a key the package does not name is refused. An application also
 * needs every required placeholder mapped; a check maps a missing one to the importer for the time of the check.
 */
@Component
public class SolutionMapping {
    static final int MAX_USERS = 50;

    private final MdRoleService roles;
    private final MdUserService users;

    public SolutionMapping(MdRoleService roles, MdUserService users) {
        this.roles = roles;
        this.users = users;
    }

    /** The mapping without empty entries, or 422 with each wrong placeholder. */
    public Map<String, List<Long>> check(
            SolutionPackage pack, @Nullable Map<String, List<Long>> mapping, boolean complete) {
        Map<String, List<Long>> given = new LinkedHashMap<>();
        List<FieldErrorItem> problems = new ArrayList<>();
        Map<String, List<Long>> wanted = mapping == null ? Map.of() : mapping;
        for (var entry : wanted.entrySet()) {
            String key = entry.getKey();
            List<Long> ids = entry.getValue() == null
                    ? List.of()
                    : entry.getValue().stream().distinct().toList();
            if (ids.isEmpty()) continue;
            Optional<Placeholder> placeholder = pack.placeholders().stream()
                    .filter(candidate -> candidate.key().equals(key))
                    .findFirst();
            if (placeholder.isEmpty() || !fits(placeholder.get(), ids)) {
                problems.add(problem(key, "error.studio.solution_mapping"));
                continue;
            }
            given.put(key, ids);
        }
        if (complete) {
            for (Placeholder placeholder : pack.placeholders()) {
                if (placeholder.required() && !given.containsKey(placeholder.key()))
                    problems.add(problem(placeholder.key(), "error.studio.solution_unmapped"));
            }
        }
        if (!problems.isEmpty()) throw ApiException.validation("error.studio.solution_mapping_invalid", problems);
        return Map.copyOf(given);
    }

    private boolean fits(Placeholder placeholder, List<Long> ids) {
        if (SolutionChecks.ROLE.equals(placeholder.kind())) return ids.size() == 1 && roles.active(ids.getFirst());
        if (ids.size() > MAX_USERS) return false;
        for (Long id : ids) {
            if (id == null
                    || users.findAuthUserById(id)
                            .filter(user -> "A".equals(user.state()))
                            .isEmpty()) return false;
        }
        return true;
    }

    private static FieldErrorItem problem(String key, String message) {
        return FieldErrorItem.keyed("mapping." + key, "solution_mapping", message, Map.of("key", key));
    }
}
