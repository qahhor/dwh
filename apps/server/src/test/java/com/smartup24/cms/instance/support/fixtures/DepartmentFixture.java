package com.smartup24.cms.instance.support.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

/**
 * Instance configuration for core tests: units, dated coefficients, loads.
 * Read from the test resources {@code fixtures/dept-a.yaml}, {@code fixtures/dept-b.yaml}; {@code src/main}
 * holds no fixtures (a grep test confirms it). The order of elements is fixed by comments in the YAML.
 */
public record DepartmentFixture(
        String name, List<Unit> units, List<Coefficient> coefficients, List<Load> loads, List<Format> formats) {

    public record Unit(String code, String nameUz, String base) {}

    public record Coefficient(String from, String to, BigDecimal factor, LocalDate validFrom) {}

    public record Load(String source, LocalDate periodFrom, LocalDate periodTo, String format) {}

    /** An instance file profile (the {@code formats:} section); values stay as in the YAML, not mapped to enums. */
    public record Format(
            String code,
            String name,
            String ownerOrg,
            String periodicity,
            int slaDays,
            String fileKind,
            String encoding,
            String delimiter,
            String matchColumnsBy,
            LocalDate validFrom,
            List<FormatSheet> sheets) {}

    public record FormatSheet(String sheetName, int headerRow, String totalRowMarker, List<FormatColumn> columns) {}

    public record FormatColumn(
            Integer filePosition,
            String name,
            String field,
            String type,
            boolean required,
            String sourceUnit,
            String baseUnit,
            String keyMask,
            Integer keyPadLength,
            Integer keyPadMax,
            String refBook) {}

    /** Both configurations, as the source for {@code @MethodSource} of parameterized tests. */
    public static Stream<DepartmentFixture> departments() {
        return Stream.of(load("dept-a"), load("dept-b"));
    }

    @SuppressWarnings("unchecked")
    public static DepartmentFixture load(String name) {
        String resource = "fixtures/" + name + ".yaml";
        try (InputStream in = DepartmentFixture.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Фикстура не найдена: " + resource);
            }
            Map<String, Object> root = new Yaml().load(in);
            List<Unit> units = ((List<Map<String, Object>>) root.get("units"))
                    .stream()
                            .map(u -> new Unit(text(u, "code"), text(u, "name_uz"), text(u, "base")))
                            .toList();
            List<Coefficient> coefficients = ((List<Map<String, Object>>) root.get("coefficients"))
                    .stream()
                            .map(c -> new Coefficient(
                                    text(c, "from"),
                                    text(c, "to"),
                                    new BigDecimal(text(c, "factor")),
                                    date(c, "valid_from")))
                            .toList();
            List<Load> loads = ((List<Map<String, Object>>) root.get("loads"))
                    .stream()
                            .map(l -> new Load(
                                    text(l, "source"), date(l, "period_from"), date(l, "period_to"), text(l, "format")))
                            .toList();
            List<Format> formats = maps(root, "formats").stream()
                    .map(DepartmentFixture::format)
                    .toList();
            DepartmentFixture fixture = new DepartmentFixture(text(root, "name"), units, coefficients, loads, formats);
            fixture.validate();
            return fixture;
        } catch (IOException e) {
            throw new IllegalStateException("Не прочитана фикстура " + resource, e);
        }
    }

    // ---------- named elements by their order in the YAML ----------

    /** Base unit (first in the list, refers to itself). */
    public Unit baseUnit() {
        return units.get(0);
    }

    /** Derived unit (second); its base is {@link #baseUnit()}. */
    public Unit derivedUnit() {
        return units.get(1);
    }

    /** A separate base unit (third), for a pair without a coefficient. */
    public Unit otherUnit() {
        return units.get(2);
    }

    /** First value of the "derived → base" coefficient. */
    public Coefficient firstCoefficient() {
        return coefficients.get(0);
    }

    /** Second, later value of the same pair. */
    public Coefficient secondCoefficient() {
        return coefficients.get(1);
    }

    /** Main load: source and period. */
    public Load mainLoad() {
        return loads.get(0);
    }

    /** Another source for the same period. */
    public Load otherSourceLoad() {
        return loads.get(1);
    }

    /** The same source for another period. */
    public Load otherPeriodLoad() {
        return loads.get(2);
    }

    @Override
    public String toString() {
        return name;
    }

    private void validate() {
        if (units.size() < 3 || coefficients.size() < 2 || loads.size() < 3) {
            throw new IllegalStateException("Фикстура " + name + ": нужны ≥3 единиц, ≥2 коэффициентов, ≥3 загрузок");
        }
        if (!baseUnit().base().equals(baseUnit().code())
                || !derivedUnit().base().equals(baseUnit().code())
                || !otherUnit().base().equals(otherUnit().code())) {
            throw new IllegalStateException("Фикстура " + name + ": порядок единиц — базовая, производная, отдельная");
        }
        for (Coefficient c : coefficients) {
            if (!c.from().equals(derivedUnit().code())
                    || !c.to().equals(baseUnit().code())) {
                throw new IllegalStateException(
                        "Фикстура " + name + ": коэффициенты — только пара производная → базовая");
            }
        }
        if (!firstCoefficient().validFrom().isBefore(secondCoefficient().validFrom())) {
            throw new IllegalStateException("Фикстура " + name + ": второй коэффициент должен быть позже первого");
        }
        if (!mainLoad().source().equals(otherPeriodLoad().source())
                || mainLoad().source().equals(otherSourceLoad().source())
                || !mainLoad().periodFrom().equals(otherSourceLoad().periodFrom())
                || mainLoad().periodFrom().equals(otherPeriodLoad().periodFrom())) {
            throw new IllegalStateException(
                    "Фикстура " + name + ": загрузки — основная, другой источник, другой период");
        }
    }

    private static Format format(Map<String, Object> f) {
        return new Format(
                text(f, "code"),
                text(f, "name"),
                text(f, "owner_org"),
                text(f, "periodicity"),
                Integer.parseInt(text(f, "sla_days")),
                text(f, "file_kind"),
                optionalText(f, "encoding"),
                optionalText(f, "delimiter"),
                text(f, "match_columns_by"),
                date(f, "valid_from"),
                maps(f, "sheets").stream().map(DepartmentFixture::sheet).toList());
    }

    private static FormatSheet sheet(Map<String, Object> s) {
        return new FormatSheet(
                optionalText(s, "sheet_name"),
                Integer.parseInt(text(s, "header_row")),
                optionalText(s, "total_row_marker"),
                maps(s, "columns").stream().map(DepartmentFixture::column).toList());
    }

    private static FormatColumn column(Map<String, Object> c) {
        return new FormatColumn(
                integer(c, "file_position"),
                text(c, "name"),
                text(c, "field"),
                text(c, "type"),
                Boolean.parseBoolean(text(c, "required")),
                optionalText(c, "source_unit"),
                optionalText(c, "base_unit"),
                optionalText(c, "key_mask"),
                integer(c, "key_pad_length"),
                integer(c, "key_pad_max"),
                optionalText(c, "ref_book"));
    }

    /** List of nested elements; an empty list when the key is missing. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    private static String optionalText(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integer(Map<String, Object> map, String key) {
        String value = optionalText(map, key);
        return value == null ? null : Integer.valueOf(value);
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalStateException("В фикстуре нет поля " + key);
        }
        return String.valueOf(value);
    }

    private static LocalDate date(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Date d) {
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.parse(text(map, key));
    }
}
