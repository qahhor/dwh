package com.smartup24.cms.instance.common.security;

import java.util.Set;

/**
 * The {@code form.action} pairs a module checks in its own code instead of on a handler's {@code @RequiresPermission}
 * (ADR-0028): a right that widens what a person may do with an endpoint every signed-in person reaches, such as keeping
 * the mailings of any report rather than of one's own (ADR-0045). The form catalog counts them as declared, so they stay
 * grantable in the rights matrix.
 */
public interface PermissionDeclarations {

    /** The pairs, each {@code <form>.<action>}. */
    Set<String> pairs();
}
