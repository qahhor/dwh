package com.smartup24.cms.instance.common.error;

import java.util.List;

/**
 * The codes of a module, published as a bean for a platform mechanism that writes the module's tables on its behalf:
 * the versioning standard writes a module's versions table and translates a violation of the module's constraints
 * with the module's codes (plan 10/10, item 4.2).
 */
@FunctionalInterface
public interface ConstraintCodes {

    /** The module's codes. */
    List<ConstraintCode> codes();
}
