package com.smartup24.cms.platform.api.entity;

/** The document declaration of {@link EntityDeclarationTest} for the tests of other packages. */
public final class EntityDeclarationTestAccess {

    private EntityDeclarationTestAccess() {}

    public static EntityDefinition orders() {
        return EntityDeclarationTest.orders();
    }
}
