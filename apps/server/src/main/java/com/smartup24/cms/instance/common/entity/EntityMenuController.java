package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/entities/menu} (roadmap item 57): the side-menu items the declared entities bring, only those
 * the viewer may open. The screen adds them to their sections, so a new entity needs no menu code of its own; an
 * entity's installed module, when it has one, still switches its item off on the screen.
 */
@RestController
@RequestMapping("/api/v1/entities")
public class EntityMenuController {

    private final EntityRegistry registry;

    public EntityMenuController(EntityRegistry registry) {
        this.registry = registry;
    }

    public record MenuItem(
            String code,
            String form,
            String route,
            String labelKey,
            String icon,
            String section,
            int order,
            String module) {}

    /** Anyone signed in may ask; each item is filtered by its entity's own right. */
    @Operation(
            summary = "Get the entity menu",
            description = "The menu items of the declared entities, each filtered by the caller's right on its entity.")
    @GetMapping("/menu")
    @RequiresPermission(form = "iam.profile", action = "view")
    public ResponseEntity<List<MenuItem>> menu() {
        return ResponseEntity.ok(registry.all().stream()
                .filter(entity -> entity.menu() != null)
                .filter(entity -> SecurityContext.hasPermission(entity.form(), "view"))
                .map(entity -> new MenuItem(
                        entity.code(),
                        entity.form(),
                        entity.menu().route(),
                        entity.menu().labelKey(),
                        entity.menu().icon(),
                        entity.menu().section(),
                        entity.menu().order(),
                        entity.menu().module()))
                .sorted(Comparator.comparing(MenuItem::section)
                        .thenComparingInt(MenuItem::order)
                        .thenComparing(MenuItem::code))
                .toList());
    }
}
