package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.entity.FormMetaController;
import com.smartup24.cms.instance.md.pref.MdFormCatalog;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * A screen names what it shows in the viewer's language, never by a code (ADR-0031): the general entity screen takes
 * the entity's name from {@code form-meta.titleKey}, and the permission matrix names each permission area by the key
 * {@code iam.roles.area.<area>}. Both keys must exist in every bundled catalog, or the screen falls back to the code
 * ("MS.TASKS", "Module: UPL").
 */
class EntityTitleContractTest {

    private static final List<String> LANGUAGES = List.of("ru", "uz", "en");

    @Test
    void everyDeclaredEntityHasATranslatedTitle() throws Exception {
        MdI18nCatalog catalog = new MdI18nCatalog(new ObjectMapper());
        List<String> problems = new ArrayList<>();
        for (EntityDefinition entity : EntityActionPermissionContractTest.declaredEntities()) {
            String key = FormMetaController.titleKey(entity);
            if (key == null) {
                problems.add(entity.code() + ": neither a menu item nor rights name it");
                continue;
            }
            problems.addAll(missing(catalog, entity.code(), key));
        }
        assertThat(problems).as("entities without a translated title").isEmpty();
    }

    @Test
    void everyPermissionAreaHasATranslatedTitle() throws Exception {
        Set<String> areas = new TreeSet<>();
        Map<String, String> entityAreas = new HashMap<>();
        for (EntityDefinition entity : EntityActionPermissionContractTest.declaredEntities()) {
            if (entity.rights() != null)
                entityAreas.put(entity.form(), entity.rights().module());
        }
        for (String pair : MdFormCatalogTest.declaredPairsFromSources()) {
            String form = pair.substring(0, pair.lastIndexOf('.'));
            areas.add(entityAreas.getOrDefault(form, MdFormCatalog.moduleOf(form)));
        }
        assertThat(areas).as("permission areas of the application").hasSizeGreaterThanOrEqualTo(5);

        MdI18nCatalog catalog = new MdI18nCatalog(new ObjectMapper());
        List<String> problems = new ArrayList<>();
        for (String area : areas) {
            problems.addAll(missing(catalog, area, areaKey(area)));
        }
        assertThat(problems).as("permission areas without a translated title").isEmpty();
    }

    /** The key the role editor names an area by: {@code ms.task} — {@code iam.roles.area.ms_task}. */
    private static String areaKey(String area) {
        return "iam.roles.area." + area.replace('.', '_');
    }

    private static List<String> missing(MdI18nCatalog catalog, String owner, String key) {
        List<String> problems = new ArrayList<>();
        for (String language : LANGUAGES) {
            String text = catalog.bundled(language).get(key);
            if (text == null || text.isBlank()) {
                problems.add(owner + ": no " + language + " text for " + key);
            }
        }
        return problems;
    }
}
