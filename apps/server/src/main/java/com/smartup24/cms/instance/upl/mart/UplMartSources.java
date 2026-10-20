package com.smartup24.cms.instance.upl.mart;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.date;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.number;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.searchable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;

import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.common.entity.report.EntityReportMartRunner;
import com.smartup24.cms.instance.upl.UplPref;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Column;
import com.smartup24.cms.instance.upl.format.UplFormatModel.FormatVersion;
import com.smartup24.cms.instance.upl.format.UplFormatModel.Sheet;
import com.smartup24.cms.instance.upl.format.UplFormatModel.SourceSummary;
import com.smartup24.cms.instance.upl.format.UplFormatRepository;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.EntityFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Exposes each published {@code upl} file source as a read-only mart entity {@code mart.<slug>} for the report builder,
 * saved reports, report exports, dashboard widgets and administrator datasets (plan 10/10, Constructor stage 3.3).
 */
@Component
public class UplMartSources implements EntityDefinitionSource {

    public static final String PREFIX = EntityReportMartRunner.MART_PREFIX;
    public static final String TABLE = "mart_rows";
    public static final String ALIAS = "m";

    public static final String FIELD_PERIOD_FROM = "periodFrom";
    public static final String FIELD_PERIOD_TO = "periodTo";
    public static final String FIELD_SHEET = "sheetName";

    private static final Set<String> RESERVED_KEYS = Set.of(FIELD_PERIOD_FROM, FIELD_PERIOD_TO, FIELD_SHEET);
    private static final List<String> LANGUAGES = List.of("ru", "uz", "en");
    private static final Logger log = LoggerFactory.getLogger(UplMartSources.class);

    private final UplFormatRepository formats;

    public UplMartSources(UplFormatRepository formats) {
        this.formats = formats;
    }

    @Override
    public Map<String, Declaration> snapshot() {
        Map<String, Declaration> result = new LinkedHashMap<>();
        try {
            for (SourceSummary source : formats.findPublishedSummaries()) {
                Integer publishedVersion = source.lastPublishedVersion();
                if (publishedVersion == null) continue;
                Optional<FormatVersion> version = formats.findVersion(source.id(), publishedVersion);
                if (version.isEmpty()) continue;
                try {
                    Declaration declaration = compile(source, version.get());
                    result.put(declaration.entity().code(), declaration);
                } catch (RuntimeException badSource) {
                    log.warn("upl_mart_compile_skipped source={}", source.code(), badSource);
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("upl_mart_snapshot_unavailable", unavailable);
        }
        return Map.copyOf(result);
    }

    /** Resolves a mart entity code ({@code mart.<slug>}) back to its published {@code upl} source and format version. */
    public Optional<ResolvedSource> findByMartCode(String martCode) {
        if (martCode == null || !martCode.startsWith(PREFIX)) return Optional.empty();
        for (SourceSummary source : formats.findPublishedSummaries()) {
            Integer publishedVersion = source.lastPublishedVersion();
            if (publishedVersion != null && martCode(source.code()).equals(martCode)) {
                return formats.findVersion(source.id(), publishedVersion)
                        .map(version -> new ResolvedSource(source, version));
            }
        }
        return Optional.empty();
    }

    public record ResolvedSource(SourceSummary source, FormatVersion version) {}

    /**
     * Converts a {@code upl} source code ({@code ^[a-z0-9._-]+$}) into a valid entity code {@code mart.<slug>} matching
     * {@code ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$}.
     */
    public static String martCode(String sourceCode) {
        String normalized = sourceCode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        String[] parts = normalized.split("\\.");
        List<String> segments = new ArrayList<>(parts.length);
        for (String part : parts) {
            String clean = part.replaceAll("[^a-z0-9_]", "_");
            if (clean.isEmpty()) continue;
            if (!Character.isLetter(clean.charAt(0))) {
                clean = "s_" + clean;
            }
            segments.add(clean);
        }
        if (segments.isEmpty()) {
            return PREFIX + "source";
        }
        return PREFIX + String.join(".", segments);
    }

    /** Converts a snake_case {@code target_field} into a camelCase {@code EntityField} key. */
    public static String toFieldKey(String targetField) {
        String[] parts = targetField.toLowerCase(Locale.ROOT).split("_+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.isEmpty()) {
                out.append(part);
            } else {
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        String key = out.isEmpty() ? "field" : out.toString();
        if (!Character.isLetter(key.charAt(0))) {
            key = "f" + key;
        }
        if (RESERVED_KEYS.contains(key)) {
            key = "col" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        }
        return key.length() > 64 ? key.substring(0, 64) : key;
    }

    static Declaration compile(SourceSummary source, FormatVersion version) {
        String code = martCode(source.code());
        String keySlug = code.substring(PREFIX.length()).replace('.', '_');
        String titleKey = "upl.mart." + keySlug + ".title";
        Entity builder = Entity.define(code, UplPref.FORM_SOURCES)
                .table(TABLE, ALIAS)
                .scope(EntityScope.all())
                .capabilities(EntityCapability.SAVED_VIEWS)
                .defaultSort(FIELD_PERIOD_FROM, Entity.Sort.DESC);

        List<String> sectionFields = new ArrayList<>();
        builder.field(date(FIELD_PERIOD_FROM, "upl.mart.field.period_from")
                .column("period_from")
                .readonly()
                .list(sortable()));
        builder.field(date(FIELD_PERIOD_TO, "upl.mart.field.period_to")
                .column("period_to")
                .readonly()
                .list(sortable()));
        builder.field(text(FIELD_SHEET, "upl.mart.field.sheet")
                .column("sheet")
                .readonly()
                .list(sortable().searchable()));
        sectionFields.add(FIELD_PERIOD_FROM);
        sectionFields.add(FIELD_PERIOD_TO);
        sectionFields.add(FIELD_SHEET);

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(titleKey, source.name());
        Set<String> addedKeys = new LinkedHashSet<>(sectionFields);

        for (Sheet sheet : version.sheets()) {
            for (Column col : sheet.columns()) {
                String fieldKey = toFieldKey(col.targetField());
                if (!addedKeys.add(fieldKey)) continue;
                String labelKey = "upl.mart." + keySlug + ".field." + col.targetField();
                labels.put(labelKey, columnLabel(col));
                builder.field(buildField(fieldKey, labelKey, col));
                sectionFields.add(fieldKey);
            }
        }
        builder.section("main", titleKey, sectionFields.toArray(String[]::new));
        EntityDefinition entity = builder.build();
        Map<String, Map<String, String>> messages = new LinkedHashMap<>();
        for (String lang : LANGUAGES) {
            messages.put(lang, labels);
        }
        return new Declaration(entity, messages);
    }

    private static EntityFields.Builder buildField(String fieldKey, String labelKey, Column col) {
        return switch (col.dataType()) {
            case INTEGER ->
                number(fieldKey, labelKey)
                        .jsonProperty("fields", col.targetField())
                        .scale(0)
                        .readonly();
            case NUMBER ->
                number(fieldKey, labelKey)
                        .jsonProperty("fields", col.targetField())
                        .readonly();
            case DATE ->
                date(fieldKey, labelKey)
                        .jsonProperty("fields", col.targetField())
                        .readonly();
            case TEXT, OBJECT_KEY, REF_CODE ->
                text(fieldKey, labelKey)
                        .jsonProperty("fields", col.targetField())
                        .readonly()
                        .list(searchable());
        };
    }

    private static String columnLabel(Column col) {
        if (col.baseUnit() != null && !col.baseUnit().isBlank()) {
            return col.nameInFile() + " (" + col.baseUnit() + ")";
        }
        return col.nameInFile();
    }
}
