package com.smartup24.cms.instance.studio.solution;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.md.service.MdUserTexts;
import com.smartup24.cms.instance.studio.api.StudioPackageDtos.Placeholder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The placeholders of a package being made (ADR-0047): a role of this installation becomes {@code role-<n>} named as
 * the role is; the fixed users of a mailing or of an automation's notification become a placeholder of users named
 * after what they receive. No id and no name of a person goes into the package.
 */
final class SolutionPlaceholders {
    private static final int MAX_NAME = 50;
    private static final int MAX_LABEL = 80;

    private final Map<Long, Placeholder> roles = new LinkedHashMap<>();
    private final List<Placeholder> users = new ArrayList<>();
    private final Function<String, Map<String, String>> dictionaries;

    /** @param dictionaries the catalog of a language: ru, uz or en */
    SolutionPlaceholders(Function<String, Map<String, String>> dictionaries) {
        this.dictionaries = dictionaries;
    }

    /** The placeholder of the role, the same for every use of the role. */
    String role(long id, String name) {
        return roles.computeIfAbsent(id, key -> {
                    String label = label(name);
                    return new Placeholder(
                            "role-" + (roles.size() + 1), SolutionChecks.ROLE, new Labels(label, label, label), false);
                })
                .key();
    }

    /** A new placeholder of users named by the catalog text {@code text} with the name of what they receive. */
    String users(String key, String text, String name, boolean required) {
        Map<String, String> values = Map.of("name", trimmed(name, MAX_NAME));
        Labels labels = new Labels(text(text, "ru", values), text(text, "uz", values), text(text, "en", values));
        users.add(new Placeholder(key, SolutionChecks.USERS, labels, required));
        return key;
    }

    List<Placeholder> all() {
        List<Placeholder> all = new ArrayList<>(roles.values());
        all.addAll(users);
        return List.copyOf(all);
    }

    private String text(String key, String language, Map<String, String> values) {
        String template = dictionaries.apply(language).getOrDefault(key, key);
        return label(MdUserTexts.fill(template, values));
    }

    /** A name that is a valid label: trimmed, without control characters, at most 80 characters. */
    private static String label(String name) {
        String text = trimmed(name, MAX_LABEL);
        return text.isEmpty() || !MdSimpleFormDeclarations.validLabel(text) ? "?" : text;
    }

    private static String trimmed(String name, int max) {
        String text = name.replaceAll("\\p{Cc}|\\p{Cf}", " ").strip();
        return text.length() <= max ? text : text.substring(0, max - 1).strip() + "…";
    }
}
