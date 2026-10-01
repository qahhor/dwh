package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdCustomFieldDtos.CreateCustomFieldDto;
import com.smartup24.cms.instance.md.api.MdCustomFieldDtos.CustomFieldView;
import com.smartup24.cms.instance.md.api.MdCustomFieldDtos.UpdateCustomFieldDto;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/custom-fields")
public class MdCustomFieldController {

    private final MdCustomFieldService customFieldService;

    public MdCustomFieldController(MdCustomFieldService customFieldService) {
        this.customFieldService = customFieldService;
    }

    @Operation(
            summary = "List custom fields",
            description = "The custom field definitions, of one entity type or of all.")
    @GetMapping
    @RequiresPermission(form = MdPref.FORM_CUSTOM_FIELDS, action = "view")
    public ResponseEntity<List<CustomFieldView>> getFields(
            @RequestParam(name = "entityType", required = false) String entityType) {

        return ResponseEntity.ok(customFieldService.listFields(entityType));
    }

    @Operation(
            summary = "Create a custom field",
            description =
                    "Adds a custom field to an entity type: its code, type, whether it is required, its default and options.")
    @PostMapping
    @RequiresPermission(form = MdPref.FORM_CUSTOM_FIELDS, action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<CustomFieldView> createField(@Valid @RequestBody CreateCustomFieldDto body) {

        var field = customFieldService.createField(
                body.entityType(),
                body.code(),
                body.name(),
                body.fieldType(),
                body.isRequired(),
                body.defaultValue(),
                body.options(),
                body.orderNo());

        return Created.at("/api/v1/custom-fields/{id}", field.id(), field);
    }

    @Operation(
            summary = "Update a custom field",
            description =
                    "Changes the name, requirement, default, options or order of a custom field; names the revision it was read at.")
    @PatchMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_CUSTOM_FIELDS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AnswersRevision
    public ResponseEntity<Void> updateField(
            @PathVariable("id") Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateCustomFieldDto body) {
        long revision = customFieldService.updateField(
                id,
                body.name(),
                body.isRequired(),
                body.defaultValue(),
                body.options(),
                body.orderNo(),
                Revisions.required(ifMatch));
        return ResponseEntity.noContent().eTag(Revisions.etag(revision)).build();
    }

    @Operation(summary = "Delete a custom field", description = "Removes a custom field definition.")
    @DeleteMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_CUSTOM_FIELDS, action = "delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteField(@PathVariable("id") Long id) {
        customFieldService.deleteField(id);
        return ResponseEntity.noContent().build();
    }
}
