package com.greenwhite.dwh.instance.md.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdCustomFieldRepository;
import com.greenwhite.dwh.instance.md.service.MdCustomFieldService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/custom-fields")
public class MdCustomFieldController {

    private final MdCustomFieldService customFieldService;

    public MdCustomFieldController(MdCustomFieldService customFieldService) {
        this.customFieldService = customFieldService;
    }

    @GetMapping
    @RequiresPermission(form = MdPref.FORM_CUSTOM_FIELDS, action = "view")
    public ResponseEntity<List<MdCustomFieldRepository.CustomFieldRecord>> getFields(
            @RequestParam(name = "entity_type", required = false) String entityType) {

        return ResponseEntity.ok(customFieldService.getFields(entityType));
    }

    @PostMapping
    @RequiresPermission(form = MdPref.FORM_CUSTOM_FIELDS, action = "create")
    public ResponseEntity<MdCustomFieldRepository.CustomFieldRecord> createField(
            @Valid @RequestBody CreateCustomFieldDto body) {

        var record = customFieldService.createField(
                body.entityType(),
                body.code(),
                body.name(),
                body.fieldType(),
                body.isRequired(),
                body.defaultValue(),
                body.options(),
                body.orderNo()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(record);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_CUSTOM_FIELDS, action = "update")
    public ResponseEntity<Void> updateField(
            @PathVariable("id") Long id,
            @RequestBody UpdateCustomFieldDto body) {

        customFieldService.updateField(id, body.name(), body.isRequired(), body.defaultValue(), body.options(), body.orderNo());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_CUSTOM_FIELDS, action = "delete")
    public ResponseEntity<Void> deleteField(@PathVariable("id") Long id) {
        customFieldService.deleteField(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateCustomFieldDto(
            @NotBlank String entityType,
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String fieldType,
            boolean isRequired,
            String defaultValue,
            Object options,
            int orderNo
    ) {}

    public record UpdateCustomFieldDto(
            String name,
            Boolean isRequired,
            String defaultValue,
            Object options,
            Integer orderNo
    ) {}
}
