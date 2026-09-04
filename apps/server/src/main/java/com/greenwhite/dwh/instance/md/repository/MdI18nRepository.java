package com.greenwhite.dwh.instance.md.repository;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.md.i18n.I18nModels.LanguageRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MdI18nRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public MdI18nRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public List<LanguageRecord> findLanguages(boolean activeOnly) {
        String activeClause = activeOnly ? "where is_active" : "";
        return jdbcClient.sql("""
                        select code, name, is_builtin, is_active, revision,
                               created_by, modified_by, created_at, modified_at
                        from md_i18n_languages
                        %s
                        order by case code
                            when 'ru' then 1 when 'uz' then 2 when 'en' then 3
                            when 'kk' then 4 when 'ky' then 5 when 'tg' then 6
                            when 'de' then 7 when 'tr' then 8 else 100 end,
                            created_at, code
                        """.formatted(activeClause))
                .query((rs, rowNum) -> mapLanguage(rs))
                .list();
    }

    public Optional<LanguageRecord> findLanguage(String code) {
        return jdbcClient.sql("""
                        select code, name, is_builtin, is_active, revision,
                               created_by, modified_by, created_at, modified_at
                        from md_i18n_languages
                        where code = :code
                        """)
                .param("code", code)
                .query((rs, rowNum) -> mapLanguage(rs))
                .optional();
    }

    public Map<String, String> findOverrides(String code) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbcClient.sql("""
                        select translation_key, value
                        from md_i18n_translation_overrides
                        where language_code = :code
                        order by translation_key
                        """)
                .param("code", code)
                .query(rs -> {
                    while (rs.next()) {
                        result.put(rs.getString("translation_key"), rs.getString("value"));
                    }
                    return result;
                });
        return result;
    }

    public Map<String, Map<String, String>> findAllOverrides() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        jdbcClient.sql("""
                        select language_code, translation_key, value
                        from md_i18n_translation_overrides
                        order by language_code, translation_key
                        """)
                .query(rs -> {
                    while (rs.next()) {
                        result.computeIfAbsent(
                                        rs.getString("language_code"), ignored -> new LinkedHashMap<>())
                                .put(rs.getString("translation_key"), rs.getString("value"));
                    }
                    return result;
                });
        return result;
    }

    public LanguageRecord insertLanguage(String code, String name, Long userId) {
        return jdbcClient.sql("""
                        insert into md_i18n_languages
                            (code, name, is_builtin, is_active, revision, created_by, modified_by)
                        values (:code, :name, false, true, 1, :userId, :userId)
                        returning code, name, is_builtin, is_active, revision,
                                  created_by, modified_by, created_at, modified_at
                        """)
                .param("code", code)
                .param("name", name)
                .param("userId", userId)
                .query((rs, rowNum) -> mapLanguage(rs))
                .single();
    }

    /**
     * Replaces the complete override set. The surrounding service transaction
     * makes revision claim, delete and insert one atomic operation.
     */
    public long replaceOverrides(String code, Map<String, String> overrides,
                                 long expectedRevision, Long userId) {
        Optional<Long> nextRevision = jdbcClient.sql("""
                        update md_i18n_languages
                        set revision = revision + 1,
                            modified_by = :userId,
                            modified_at = now()
                        where code = :code and revision = :expectedRevision
                        returning revision
                        """)
                .param("code", code)
                .param("expectedRevision", expectedRevision)
                .param("userId", userId)
                .query(Long.class)
                .optional();

        if (nextRevision.isEmpty()) {
            throw ApiException.conflict(ErrorCode.I18N_REVISION_CONFLICT,
                    "Языковой пакет изменён другим администратором. Обновите данные и повторите операцию");
        }

        jdbcClient.sql("delete from md_i18n_translation_overrides where language_code = :code")
                .param("code", code)
                .update();

        if (!overrides.isEmpty()) {
            jdbcClient.sql("""
                            insert into md_i18n_translation_overrides
                                (language_code, translation_key, value, modified_by, modified_at)
                            select :code, item.key, item.value, :userId, now()
                            from jsonb_each_text(cast(:overrides as jsonb)) item
                            """)
                    .param("code", code)
                    .param("userId", userId)
                    .param("overrides", toJson(overrides))
                    .update();
        }

        return nextRevision.orElseThrow();
    }

    private LanguageRecord mapLanguage(ResultSet rs) throws SQLException {
        return new LanguageRecord(
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("is_builtin"),
                rs.getBoolean("is_active"),
                rs.getLong("revision"),
                rs.getObject("created_by") != null ? rs.getLong("created_by") : null,
                rs.getObject("modified_by") != null ? rs.getLong("modified_by") : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("modified_at").toInstant());
    }

    private String toJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Не удалось сериализовать языковой пакет", exception);
        }
    }
}
