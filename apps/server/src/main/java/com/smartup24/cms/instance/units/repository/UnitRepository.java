package com.smartup24.cms.instance.units.repository;

import com.smartup24.cms.instance.units.api.Unit;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The tables of the units of measure: {@code fnd_units}, {@code fnd_unit_coefficients} and the coefficient values in
 * {@code fnd_unit_coefficient_versions}, a versions table of the versioning standard (V103; plan 10/10, item 4.2).
 */
@Repository
public class UnitRepository {

    /** The coefficient versions table, following the versioning standard. */
    public static final String COEFFICIENT_VERSIONS = "fnd_unit_coefficient_versions";

    private final JdbcClient jdbc;

    public UnitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a unit with its names as JSON text; returns its id. */
    public long insertUnit(String code, String namesJson, String baseUnitCode) {
        return jdbc.sql("insert into fnd_units (code, name_i18n, base_unit_code)"
                        + " values (:code, cast(:names as jsonb), :base) returning id")
                .param("code", code)
                .param("names", namesJson)
                .param("base", baseUnitCode)
                .query(Long.class)
                .single();
    }

    /** A unit by its code. */
    public Optional<Unit> findUnit(String code) {
        return jdbc.sql("select id, code, name_i18n::text as name_i18n, base_unit_code from fnd_units"
                        + " where code = :code")
                .param("code", code)
                .query(UnitRepository::mapUnit)
                .optional();
    }

    /** All units ordered by code. */
    public List<Unit> listUnits() {
        return jdbc.sql("select id, code, name_i18n::text as name_i18n, base_unit_code from fnd_units order by code")
                .query(UnitRepository::mapUnit)
                .list();
    }

    /** The header of the coefficient {@code from → to}, if it exists. */
    public Optional<Long> findCoefficientId(String fromUnit, String toUnit) {
        return jdbc.sql("select id from fnd_unit_coefficients where from_unit = :from and to_unit = :to")
                .param("from", fromUnit)
                .param("to", toUnit)
                .query(Long.class)
                .optional();
    }

    /** Inserts the header of the coefficient {@code from → to}; returns its id. */
    public long insertCoefficient(String fromUnit, String toUnit) {
        return jdbc.sql("insert into fnd_unit_coefficients (from_unit, to_unit) values (:from, :to) returning id")
                .param("from", fromUnit)
                .param("to", toUnit)
                .query(Long.class)
                .single();
    }

    /** The factor of one version of a coefficient. */
    public BigDecimal factor(long coefficientId, int version) {
        return jdbc.sql("select factor from " + COEFFICIENT_VERSIONS + " where coefficient_id = :id and version = :v")
                .param("id", coefficientId)
                .param("v", version)
                .query(BigDecimal.class)
                .single();
    }

    private static Unit mapUnit(ResultSet rs, int rowNum) throws SQLException {
        return new Unit(
                rs.getLong("id"), rs.getString("code"), rs.getString("name_i18n"), rs.getString("base_unit_code"));
    }
}
