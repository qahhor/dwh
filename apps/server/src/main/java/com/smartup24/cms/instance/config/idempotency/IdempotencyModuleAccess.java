package com.smartup24.cms.instance.config.idempotency;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.common.module.ModuleCatalog;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** FR-MOD-01: replay checks the current module switch without running the accepted operation again. */
@Component
public class IdempotencyModuleAccess {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyModuleAccess.class);

    private final ModuleCatalog catalog;
    private final InstalledModules modules;
    private final EntityRegistry entities;
    private final QueryListRegistry lists;
    private final ObjectMapper json;
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;

    public IdempotencyModuleAccess(
            ModuleCatalog catalog,
            InstalledModules modules,
            EntityRegistry entities,
            QueryListRegistry lists,
            ObjectMapper json,
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        this.catalog = catalog;
        this.modules = modules;
        this.entities = entities;
        this.lists = lists;
        this.json = json;
        this.handlerMapping = handlerMapping;
    }

    /** Only access to a module is checked: a replay of a deletion does not need the deleted record to exist. */
    public void requireActive(HttpServletRequest request, byte[] body) {
        try {
            if (active(request, body)) return;
        } catch (Exception unavailable) {
            log.warn(
                    "idempotency_module_lookup_failed uri={} error={}",
                    request.getRequestURI(),
                    unavailable.getClass().getSimpleName());
        }
        throw new ApiException(ErrorCode.NOT_FOUND);
    }

    private boolean active(HttpServletRequest request, byte[] body) throws Exception {
        RequestMappingHandlerMapping mapping = handlerMapping.getIfAvailable();
        HandlerExecutionChain chain = mapping == null ? null : mapping.getHandler(request);
        if (chain == null || !(chain.getHandler() instanceof HandlerMethod handler)) return false;
        RequiresPermission permission = handler.getMethodAnnotation(RequiresPermission.class);
        if (permission != null && !catalog.activeForm(permission.form(), modules)) return false;

        String pattern = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        if (pattern.startsWith("/api/v1/entities/")) {
            Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (!(variables instanceof Map<?, ?> paths) || !(paths.get("code") instanceof String code)) return false;
            return entities.find(code)
                    .map(entity -> catalog.activeForm(entity.form(), modules))
                    .orElse(false);
        }
        if (pattern.equals("/api/v1/exports") && request.getMethod().equals("POST")) {
            return exportActive(body);
        }
        return true;
    }

    private boolean exportActive(byte[] body) {
        JsonNode list = json.readTree(body).path("list");
        if (!list.isTextual()) return false;
        String code = list.asText();
        return entities.findByList(code)
                .map(entity -> catalog.activeForm(entity.form(), modules))
                .orElseGet(() -> lists.find(code)
                        .map(query -> catalog.activeForm(query.form(), modules))
                        .orElse(false));
    }
}
