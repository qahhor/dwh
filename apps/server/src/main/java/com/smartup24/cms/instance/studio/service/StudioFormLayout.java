package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormLayoutDtos.Layout;
import com.smartup24.cms.instance.md.api.MdSimpleFormLayoutDtos.Section;
import com.smartup24.cms.instance.md.api.MdSimpleFormLayoutDtos.Tab;
import com.smartup24.cms.instance.md.service.MdSimpleFormDocumentLogic;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityTab;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The record title and the layout of a live form (ADR-0041). Formats 1 to 3 — and a form of format 4 whose title field
 * is {@code name} — are named by the system name and code fields; a form of format 4 is named by its title field or,
 * without one, by the record's number, which the database gives each record of the form in turn. The layout puts the
 * system fields first on the first section and the record's org unit last on it; a form with tabs shows each tab's
 * sections and its history as the last tab.
 */
final class StudioFormLayout {
    /** The title of a form named by the system fields. */
    static final String NAME = "name";

    /** The key of the record's number: a key with a capital letter, which no declared field id has. */
    static final String RECORD_NUMBER = "recordNumber";

    /** The key of the single section of a form without a layout. */
    static final String MAIN = "main";

    /** The key of the tab of every section of a document whose layout has no tabs (ADR-0043). */
    static final String FIELDS_TAB = "fields";

    private StudioFormLayout() {}

    /**
     * The key of the field that names a record of the form: its title field, {@code name}, the document's number of a
     * form of format 6 that numbers its records (ADR-0043) or the record's own number.
     */
    static String titleKey(Declaration source) {
        String field = source.entity().titleField();
        if (source.formatVersion() < 4 || NAME.equals(field)) return NAME;
        if (field != null) return field;
        return source.entity().numbering() != null ? MdSimpleFormDocumentLogic.NUMBER : RECORD_NUMBER;
    }

    /** The served fields, the title field first. */
    static List<Field> ordered(List<Field> shown, String title) {
        List<Field> result = new ArrayList<>();
        shown.stream().filter(field -> field.id().equals(title)).forEach(result::add);
        shown.stream().filter(field -> !field.id().equals(title)).forEach(result::add);
        return result;
    }

    /**
     * Declares the sections and tabs: {@code system} keys open the first section, {@code last} closes it; the fields of a
     * form without a layout make one section in {@code fields} order. A document's tables of lines (ADR-0043) are tabs
     * of the card after the tabs of sections — one tab of every section when the layout has none — and before the
     * history.
     */
    static void declare(
            Entity builder,
            Declaration source,
            String prefix,
            List<String> system,
            List<String> fields,
            List<String> last,
            List<EntityCollection> collections) {
        Layout layout = source.layout();
        List<String> declared = new ArrayList<>();
        if (layout == null) {
            List<String> keys = new ArrayList<>(system);
            keys.addAll(fields);
            keys.addAll(last);
            builder.section(MAIN, "entity.section.main", keys.toArray(String[]::new));
            declared.add(MAIN);
        } else {
            List<Section> sections = layout.sections();
            for (int index = 0; index < sections.size(); index++) {
                Section section = sections.get(index);
                List<String> keys = new ArrayList<>();
                if (index == 0) keys.addAll(system);
                keys.addAll(section.fields());
                if (index == 0) keys.addAll(last);
                builder.section(section.key(), sectionKey(prefix, section.key()), keys.toArray(String[]::new));
                declared.add(section.key());
            }
        }
        if (layout != null && layout.tabs() != null) {
            for (Tab tab : layout.tabs()) {
                String[] shown = layout.sections().stream()
                        .filter(section -> tab.key().equals(section.tab()))
                        .map(Section::key)
                        .toArray(String[]::new);
                builder.tab(EntityTab.sections(tab.key(), tabKey(prefix, tab.key()), shown));
            }
        } else if (!collections.isEmpty()) {
            builder.tab(EntityTab.sections(FIELDS_TAB, "ui.entity_page.tab_fields", declared.toArray(String[]::new)));
        } else {
            return;
        }
        for (EntityCollection collection : collections) {
            builder.tab(EntityTab.collection(collection.key(), collection.labelKey(), collection.key()));
        }
        builder.tab(EntityTab.history("history", "ui.entity_page.tab_history"));
    }

    /** The labels of the sections and tabs, by their dictionary keys. */
    static void messages(Declaration source, String prefix, BiConsumer<String, Labels> labelled) {
        Layout layout = source.layout();
        if (layout == null) return;
        for (Section section : layout.sections()) {
            labelled.accept(sectionKey(prefix, section.key()), section.labels());
        }
        if (layout.tabs() == null) return;
        for (Tab tab : layout.tabs()) {
            labelled.accept(tabKey(prefix, tab.key()), tab.labels());
        }
    }

    private static String sectionKey(String prefix, String key) {
        return prefix + ".section." + key;
    }

    private static String tabKey(String prefix, String key) {
        return prefix + ".tab." + key;
    }
}
