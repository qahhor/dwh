package com.greenwhite.dwh.instance.md.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MdCustomFieldRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public MdCustomFieldRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public CustomFieldRecord create(String entityType, String code, String name, String fieldType,
                                    boolean isRequired, String defaultValue, Object options, int orderNo) {
        String optionsJson = toJson(options);

        return jdbcClient.sql("""
                insert into md_custom_fields (entity_type, code, name, field_type, is_required, default_value, options_json, order_no, created_at)
                values (:entityType, :code, :name, :fieldType, :isRequired, :defaultValue, cast(:optionsJson as jsonb), :orderNo, now())
                returning id, entity_type, code, name, field_type, is_required, default_value, options_json::text as options_str, order_no, created_at
                """)
                .param("entityType", entityType.toUpperCase())
                .param("code", code.toLowerCase().trim())
                .param("name", name)
                .param("fieldType", fieldType.toLowerCase().trim())
                .param("isRequired", isRequired)
                .param("defaultValue", defaultValue)
                .param("optionsJson", optionsJson)
                .param("orderNo", orderNo)
                .query(this::mapRecord)
                .single();
    }

    public Optional<CustomFieldRecord> findById(Long id) {
        return jdbcClient.sql("""
                select id, entity_type, code, name, field_type, is_required, default_value, options_json::text as options_str, order_no, created_at
                from md_custom_fields
                where id = :id
                """)
                .param("id", id)
                .query(this::mapRecord)
                .optional();
    }

    public Optional<CustomFieldRecord> findByCode(String entityType, String code) {
        return jdbcClient.sql("""
                select id, entity_type, code, name, field_type, is_required, default_value, options_json::text as options_str, order_no, created_at
                from md_custom_fields
                where entity_type = :entityType and code = :code
                """)
                .param("entityType", entityType.toUpperCase())
                .param("code", code.toLowerCase().trim())
                .query(this::mapRecord)
                .optional();
    }

    public List<CustomFieldRecord> findByEntityType(String entityType) {
        return jdbcClient.sql("""
                select id, entity_type, code, name, field_type, is_required, default_value, options_json::text as options_str, order_no, created_at
                from md_custom_fields
                where entity_type = :entityType
                order by order_no asc, id asc
                """)
                .param("entityType", entityType.toUpperCase())
                .query(this::mapRecord)
                .list();
    }

    public List<CustomFieldRecord> findAll() {
        return jdbcClient.sql("""
                select id, entity_type, code, name, field_type, is_required, default_value, options_json::text as options_str, order_no, created_at
                from md_custom_fields
                order by entity_type asc, order_no asc, id asc
                """)
                .query(this::mapRecord)
                .list();
    }

    public void update(Long id, String name, Boolean isRequired, String defaultValue, Object options, Integer orderNo) {
        String optionsJson = options != null ? toJson(options) : null;

        jdbcClient.sql("""
                update md_custom_fields
                set name = coalesce(:name, name),
                    is_required = coalesce(:isRequired, is_required),
                    default_value = coalesce(:defaultValue, default_value),
                    options_json = case when :optionsJson is not null then cast(:optionsJson as jsonb) else options_json end,
                    order_no = coalesce(:orderNo, order_no)
                where id = :id
                """)
                .param("id", id)
                .param("name", name)
                .param("isRequired", isRequired)
                .param("defaultValue", defaultValue)
                .param("optionsJson", optionsJson)
                .param("orderNo", orderNo)
                .update();
    }

    public void delete(Long id) {
        jdbcClient.sql("delete from md_custom_fields where id = :id")
                .param("id", id)
                .update();
    }

    private CustomFieldRecord mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CustomFieldRecord(
                rs.getLong("id"),
                rs.getString("entity_type"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("field_type"),
                rs.getBoolean("is_required"),
                rs.getString("default_value"),
                rs.getString("options_str"),
                rs.getInt("order_no"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private String toJson(Object obj) {
        if (obj == null) return "[]";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException e) {
            return "[]";
        }
    }

    public record CustomFieldRecord(
            Long id,
            String entityType,
            String code,
            String name,
            String fieldType,
            boolean isRequired,
            String defaultValue,
            String optionsJson,
            int orderNo,
            Instant createdAt
    ) {}
}
