package com.smartup24.cms.instance.common.security;

import java.util.Optional;

/**
 * A database column that holds a secret, declared by the repository that owns the table (ADR-0029).
 * {@link StoredSecretsSealing} uses it at every start: it checks that the key opens what is stored and encrypts the
 * legacy plain values.
 */
public interface StoredSecretColumn {

    /** The column as {@code table.column}: the encryption context of its values. */
    String secretColumn();

    /** One encrypted value of the column, if there is any, to check that the current key opens it. */
    Optional<String> anySealedSecret();

    /**
     * Encrypts every plain value of the column. A value is replaced only while it is still the plain one, so two nodes
     * starting together never encrypt a value twice.
     *
     * @return the number of values encrypted
     */
    int sealPlainSecrets(StoredSecrets secrets);
}
