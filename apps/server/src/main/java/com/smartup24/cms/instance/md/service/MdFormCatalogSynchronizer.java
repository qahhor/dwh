package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import java.util.Collection;
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
 * The single source of truth about whether a permission exists is the
 * {@code @RequiresPermission} annotation on a handler: it is what actually guards the
 * endpoint. The catalog in the database derives from it, not the other way round.
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

    public MdFormCatalogSynchronizer(
            RequestMappingHandlerMapping handlerMapping, MdPermissionService permissionService) {
        this.handlerMapping = handlerMapping;
        this.permissionService = permissionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeOnStartup() {
        Set<String> declared = declaredPairs(handlerMapping.getHandlerMethods().values());
        var result = permissionService.syncFormCatalog(declared);

        log.info(
                "Каталог прав синхронизирован с кодом: {} пар из @RequiresPermission, "
                        + "помечено устаревшими за этот проход: {}",
                declared.size(),
                result.deprecated());

        if (!result.deprecatedPairs().isEmpty()) {
            log.warn(
                    "Устаревшие права в каталоге (за ними нет эндпоинта, выдать их нельзя): {}",
                    String.join(", ", result.deprecatedPairs()));
        }
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
