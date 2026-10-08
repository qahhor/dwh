package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldAccess;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Synchronizes the form catalog with the code (FR-PERM-1).
 *
 * The source of truth about whether a permission exists is what actually guards an
 * endpoint: the {@code @RequiresPermission} annotation on a handler, and the declaration of
 * an entity whose records the general runtime checks against it (ADR-0032, 6.10). The
 * catalog in the database derives from them, not the other way round.
 *
 * Why this was needed: the catalog was filled by migrations, and the registration method
 * was never called from code. As a result the permission matrix held pairs with no
 * endpoint behind them: an administrator saw them and could grant them,
 * the permission opened nothing, and that is indistinguishable from an access misconfiguration.
 *
 * Obsolete records are not deleted: deleting a form would cascade to permissions already
 * granted, and temporarily renaming an endpoint would silently take people's access away.
 */
@Component
@Profile("!migrate")
public class MdFormCatalogSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(MdFormCatalogSynchronizer.class);

    private final RequestMappingHandlerMapping handlerMapping;
    private final MdPermissionService permissionService;
    private final List<EntityDefinition> entities;

    public MdFormCatalogSynchronizer(
            RequestMappingHandlerMapping handlerMapping,
            MdPermissionService permissionService,
            List<EntityDefinition> entities) {
        this.handlerMapping = handlerMapping;
        this.permissionService = permissionService;
        this.entities = List.copyOf(entities);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeOnStartup() {
        Set<String> declared = declaredPairs(handlerMapping.getHandlerMethods().values());
        declared.addAll(entityPairs(entities));
        var result = permissionService.syncFormCatalog(declared);

        log.info("permission_catalog_synced declared={} deprecatedNow={}", declared.size(), result.deprecated());

        if (!result.deprecatedPairs().isEmpty()) {
            log.warn(
                    "permission_catalog_obsolete pairs={}: no endpoint behind them, they cannot be granted",
                    String.join(", ", result.deprecatedPairs()));
        }
    }

    /**
     * The {@code form.action} pairs the entities declare (ADR-0032, 6.10): the runtime checks them, not an annotation —
     * {@code view} of every entity, the right of each of its actions and the rights its fields need.
     */
    public static Set<String> entityPairs(Collection<EntityDefinition> entities) {
        Set<String> pairs = new TreeSet<>();
        for (EntityDefinition entity : entities) {
            pairs.add(entity.form() + ".view");
            entity.actions().forEach(action -> pairs.add(entity.form() + "." + action.permission()));
            if (entity.capabilities().contains(EntityCapability.IMPORT)) {
                pairs.add(entity.form() + "." + EntityDefinition.IMPORT);
            }
            EntityModel model = entity.model();
            if (model == null) continue;
            for (EntityField field : model.fields()) {
                FieldAccess access = field.access();
                if (access.requiredForm() != null) {
                    pairs.add(access.requiredForm() + "." + access.requiredAction());
                }
                if (access.readonlyForm() != null) {
                    pairs.add(access.readonlyForm() + "." + access.readonlyAction());
                }
            }
        }
        return pairs;
    }

    /**
     * {@code form.action} pairs declared by handler annotations.
     * Kept separate and free of Spring context dependencies so the rule
     * can be checked by a test, not only by watching the log.
     */
    public static Set<String> declaredPairs(Collection<HandlerMethod> handlers) {
        Set<String> pairs = new TreeSet<>();
        for (HandlerMethod handler : handlers) {
            RequiresPermission annotation = handler.getMethodAnnotation(RequiresPermission.class);
            if (annotation == null) {
                annotation = handler.getBeanType().getAnnotation(RequiresPermission.class);
            }
            if (annotation != null) {
                pairs.add(annotation.form() + "." + annotation.action());
            }
        }
        return pairs;
    }
}
