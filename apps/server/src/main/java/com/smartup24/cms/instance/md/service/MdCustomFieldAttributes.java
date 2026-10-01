package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.entity.EntityAttributes;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The custom field values of an entity record, checked by their definitions for the runtime (ADR-0019, 2.3; ADR-0032,
 * 6.3, step 8): a value that breaks its definition is a problem on {@code attributes.<code>}.
 */
@Service
public class MdCustomFieldAttributes implements EntityAttributes {

    private final MdCustomFieldService fields;

    public MdCustomFieldAttributes(MdCustomFieldService fields) {
        this.fields = fields;
    }

    @Override
    public @Nullable Map<String, Object> checked(String entityType, @Nullable Map<String, Object> attributes) {
        return fields.checkedAttributes(entityType, attributes);
    }
}
