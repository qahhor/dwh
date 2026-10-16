package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.api.FormLayoutDtos.FormLayoutPreview;
import com.smartup24.cms.instance.common.entity.FormMetaController.FormMeta;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import org.springframework.stereotype.Service;

/** The prototype reads the current authorized metadata and changes no registry, record or stored layout. */
@Service
public class FormStudioService {
    private final EntityGate gate;
    private final EntityEnums enums;
    private final FormLayoutDeclarations declarations;

    public FormStudioService(EntityGate gate, EntityEnums enums, FormLayoutDeclarations declarations) {
        this.gate = gate;
        this.enums = enums;
        this.declarations = declarations;
    }

    public FormLayoutPreview prototype() {
        FormMeta meta = metadata(FormLayoutDeclarations.ENTITY_CODE);
        return new FormLayoutPreview(declarations.initial(meta), meta);
    }

    public FormLayoutPreview preview(byte[] body) {
        FormMeta meta = metadata(FormLayoutDeclarations.ENTITY_CODE);
        return new FormLayoutPreview(declarations.validate(body, meta), meta);
    }

    public FormMeta metadata(String entityCode) {
        if (!FormLayoutDeclarations.ENTITY_CODE.equals(entityCode)) throw EntityGate.unknownEntity();
        var entity = gate.findVisible(FormLayoutDeclarations.ENTITY_CODE).orElseThrow(EntityGate::unknownEntity);
        return FormMetaController.of(entity, enums::all, gate::visible);
    }
}
