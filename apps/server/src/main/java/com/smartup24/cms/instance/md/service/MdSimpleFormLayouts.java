package com.smartup24.cms.instance.md.service;

import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.invalid;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.known;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.labels;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.required;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.text;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormLayoutDtos.Layout;
import com.smartup24.cms.instance.md.api.MdSimpleFormLayoutDtos.Section;
import com.smartup24.cms.instance.md.api.MdSimpleFormLayoutDtos.Tab;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The parts format 4 of a simple form adds (ADR-0041): the field that names a record and the layout — sections, each
 * shown field on exactly one of them, and optionally tabs, each section on one tab. A hidden field is on no section;
 * the system fields (the name and code of a form named by them, the record's number and org unit) are placed by the
 * compiler, not here.
 */
final class MdSimpleFormLayouts {
    /** The title field of a form that keeps the system name and code fields of formats 1 to 3. */
    static final String NAME = "name";

    static final int MAX_SECTIONS = 10;
    static final int MIN_TABS = 2;
    static final int MAX_TABS = 6;

    private static final String KEY = "[a-z][a-z0-9_]{0,19}";

    private MdSimpleFormLayouts() {}

    /**
     * The title field: {@code name} — the system name and code fields — or a required, listed text field that is shown;
     * null when the entity names none, so the record's number names it.
     */
    static @Nullable String titleField(JsonNode entity, List<Field> fields) {
        if (!entity.has("titleField")) return null;
        String id = text(entity, "titleField", "entity.titleField");
        if (NAME.equals(id)) return id;
        boolean fits = fields.stream()
                .anyMatch(field -> field.id().equals(id)
                        && "text".equals(field.type())
                        && field.required()
                        && field.listed()
                        && field.hidden() == null);
        if (!fits) throw invalid("entity.titleField", "title");
        return id;
    }

    /** The layout of the form, or null when it names none. */
    static @Nullable Layout layout(JsonNode root, List<Field> fields) {
        if (!root.has("layout")) return null;
        JsonNode layout = required(root, "layout", "layout");
        known(layout, Set.of("tabs", "sections"), "layout");
        List<Tab> tabs = layout.has("tabs") ? tabs(required(layout, "tabs", "layout.tabs")) : null;
        Set<String> shown = new LinkedHashSet<>();
        fields.stream().filter(field -> field.hidden() == null).forEach(field -> shown.add(field.id()));
        List<Section> sections = sections(required(layout, "sections", "layout.sections"), tabs, shown);
        if (tabs != null) {
            for (int index = 0; index < tabs.size(); index++) {
                String key = tabs.get(index).key();
                if (sections.stream().noneMatch(section -> key.equals(section.tab())))
                    throw invalid("layout.tabs[" + index + "]", "layout");
            }
        }
        return new Layout(tabs, sections);
    }

    private static List<Tab> tabs(JsonNode tabs) {
        if (!tabs.isArray()) throw invalid("layout.tabs", "structure");
        if (tabs.size() < MIN_TABS || tabs.size() > MAX_TABS) throw invalid("layout.tabs", "limit");
        List<Tab> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < tabs.size(); index++) {
            String at = "layout.tabs[" + index + "]";
            JsonNode tab = tabs.get(index);
            known(tab, Set.of("key", "labels"), at);
            String key = key(tab, at);
            if (!keys.add(key)) throw invalid(at + ".key", "duplicate");
            result.add(new Tab(key, labels(tab, "labels", at + ".labels")));
        }
        return List.copyOf(result);
    }

    private static List<Section> sections(JsonNode sections, @Nullable List<Tab> tabs, Set<String> shown) {
        if (!sections.isArray()) throw invalid("layout.sections", "structure");
        if (sections.isEmpty() || sections.size() > MAX_SECTIONS) throw invalid("layout.sections", "limit");
        Set<String> tabKeys = new HashSet<>();
        if (tabs != null) tabs.forEach(tab -> tabKeys.add(tab.key()));
        List<Section> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Set<String> placed = new HashSet<>();
        for (int index = 0; index < sections.size(); index++) {
            String at = "layout.sections[" + index + "]";
            JsonNode section = sections.get(index);
            known(section, Set.of("key", "labels", "tab", "fields"), at);
            String key = key(section, at);
            if (!keys.add(key)) throw invalid(at + ".key", "duplicate");
            String tab = null;
            if (tabs == null && section.has("tab")) throw invalid(at + ".tab", "unsupported");
            if (tabs != null) {
                tab = text(section, "tab", at + ".tab");
                if (!tabKeys.contains(tab)) throw invalid(at + ".tab", "layout");
            }
            result.add(new Section(
                    key,
                    labels(section, "labels", at + ".labels"),
                    tab,
                    fields(required(section, "fields", at + ".fields"), at, shown, placed)));
        }
        if (!placed.containsAll(shown)) throw invalid("layout.sections", "layout");
        return List.copyOf(result);
    }

    private static List<String> fields(JsonNode fields, String at, Set<String> shown, Set<String> placed) {
        String path = at + ".fields";
        if (!fields.isArray()) throw invalid(path, "structure");
        if (fields.isEmpty() || fields.size() > MdSimpleFormDeclarations.MAX_SHOWN_FIELDS) throw invalid(path, "limit");
        List<String> result = new ArrayList<>();
        for (int index = 0; index < fields.size(); index++) {
            JsonNode field = fields.get(index);
            String item = path + "[" + index + "]";
            if (!field.isString()) throw invalid(item, "structure");
            String id = field.asString();
            if (!shown.contains(id)) throw invalid(item, "layout");
            if (!placed.add(id)) throw invalid(item, "duplicate");
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static String key(JsonNode owner, String at) {
        String key = text(owner, "key", at + ".key");
        if (!key.matches(KEY)) throw invalid(at + ".key", "identifier");
        return key;
    }
}
