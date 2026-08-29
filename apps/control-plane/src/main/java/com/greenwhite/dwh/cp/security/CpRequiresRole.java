package com.greenwhite.dwh.cp.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Требуемые роли для эндпоинта control plane. Достаточно любой из перечисленных.
 * Роль cp-admin проходит всегда — она по определению включает остальные.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CpRequiresRole {
    String[] value();
}
