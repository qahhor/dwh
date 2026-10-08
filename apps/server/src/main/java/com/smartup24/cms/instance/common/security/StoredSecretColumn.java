package com.smartup24.cms.instance.common.security;

import java.util.Optional;

/**
 * A database column that holds a secret, declared by the repository that owns the table (ADR-0029).
 * {@link StoredSecretsCheck} uses it at every start: it checks that the key opens what is stored and that no value is
 * stored in plain text.
 */
public interface StoredSecretColumn {

    /** The column as {@code table.column}: the encryption context of its values. */
    String secretColumn();

    /** One encrypted value of the column, if there is any, to check that the current key opens it. */
    Optional<String> anySealedSecret();

    /** The number of values of the column without the {@code v1:} prefix: plain text, which is refused. */
    int countPlainSecrets();
}
