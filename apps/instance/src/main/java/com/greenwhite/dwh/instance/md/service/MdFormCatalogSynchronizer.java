package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Синхронизация каталога форм с кодом (FR-PERM-1, дефект Д-5).
 *
 * Единственный источник правды о существовании права — аннотация
 * {@code @RequiresPermission} на обработчике: именно она реально охраняет
 * эндпоинт. Каталог в базе — производная от неё, а не наоборот.
 *
 * Почему это понадобилось: каталог наполнялся миграциями, а метод регистрации
 * из кода не вызывался ниоткуда. В результате в матрице прав жили пары, за
 * которыми не стоит ни одного эндпоинта — администратор их видел и мог выдать,
 * право ничего не открывало, и это неотличимо от ошибки настройки доступа.
 *
 * Устаревшие записи не удаляются: удаление формы каскадом снимет уже выданные
 * права, а временное переименование эндпоинта молча лишило бы людей доступа.
 */
@Component
public class MdFormCatalogSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(MdFormCatalogSynchronizer.class);

    private final RequestMappingHandlerMapping handlerMapping;
    private final MdPermissionService permissionService;

    public MdFormCatalogSynchronizer(RequestMappingHandlerMapping handlerMapping,
                                     MdPermissionService permissionService) {
        this.handlerMapping = handlerMapping;
        this.permissionService = permissionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeOnStartup() {
        Set<String> declared = declaredPairs(handlerMapping.getHandlerMethods().values());
        var result = permissionService.syncFormCatalog(declared);

        log.info("Каталог прав синхронизирован с кодом: {} пар из @RequiresPermission, "
                + "помечено устаревшими за этот проход: {}", declared.size(), result.deprecated());

        if (!result.deprecatedPairs().isEmpty()) {
            log.warn("Устаревшие права в каталоге (за ними нет эндпоинта, выдать их нельзя): {}",
                    String.join(", ", result.deprecatedPairs()));
        }
    }

    /**
     * Пары {@code form.action}, объявленные аннотациями обработчиков.
     * Вынесено отдельно и без зависимостей на Spring-контекст, чтобы правило
     * можно было проверить тестом, а не только наблюдением за журналом.
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
