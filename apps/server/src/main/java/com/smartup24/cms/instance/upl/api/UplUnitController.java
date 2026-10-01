package com.smartup24.cms.instance.upl.api;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.fnd.api.FndUnits;
import com.smartup24.cms.instance.upl.UplPref;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Instance units for the file format screen: the name is ru, otherwise uz, otherwise the code. */
@RestController
@RequestMapping("/api/v1/upl/units")
public class UplUnitController {

    private static final String RU = "ru";
    private static final String UZ = "uz";

    private final FndUnits units;
    private final ObjectMapper json;

    public UplUnitController(FndUnits units, ObjectMapper json) {
        this.units = units;
        this.json = json;
    }

    @Operation(summary = "List units of measure", description = "The units of measure.")
    @GetMapping
    @RequiresPermission(form = UplPref.FORM_SOURCES, action = UplPref.ACTION_VIEW)
    public ResponseEntity<List<UnitItem>> list() {
        List<UnitItem> items = units.listUnits().stream()
                .map(u -> new UnitItem(u.code(), displayName(u.code(), names(u.nameI18n())), u.baseUnitCode()))
                .toList();
        return ResponseEntity.ok(items);
    }

    /** ru, then uz, then the code; blank strings count as a missing name. */
    public static String displayName(String code, Map<String, String> names) {
        String ru = names.get(RU);
        if (ru != null && !ru.isBlank()) {
            return ru;
        }
        String uz = names.get(UZ);
        if (uz != null && !uz.isBlank()) {
            return uz;
        }
        return code;
    }

    private Map<String, String> names(String nameI18n) {
        if (nameI18n == null || nameI18n.isBlank()) {
            return Map.of();
        }
        return json.readValue(nameI18n, new TypeReference<Map<String, String>>() {});
    }

    /** A unit for a drop-down list: code, name in the user's language and the code of the base unit. */
    public record UnitItem(String code, String name, String baseUnitCode) {}
}
