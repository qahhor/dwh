package com.smartup24.cms.instance.common.security;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * At every start: refuses a key that does not open the stored secrets, then encrypts the plain ones (ADR-0029).
 *
 * <p>Plain values come from installations older than the encryption and from seed rows of old migrations. The step is
 * idempotent, so it runs on each start instead of as a migration: migrations run without the key (the migrate
 * profile), and a Flyway Java migration would need it there.
 */
@Component
@Profile("!migrate")
public class StoredSecretsSealing implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StoredSecretsSealing.class);

    private final List<StoredSecretColumn> columns;
    private final StoredSecrets secrets;

    public StoredSecretsSealing(List<StoredSecretColumn> columns, StoredSecrets secrets) {
        this.columns = columns;
        this.secrets = secrets;
    }

    @Override
    public void run(ApplicationArguments args) {
        sealAll();
    }

    /**
     * Checks the key against every column and encrypts the plain values.
     *
     * @return the number of values encrypted
     * @throws IllegalStateException when the key does not open a stored value
     */
    public int sealAll() {
        for (StoredSecretColumn column : columns) {
            column.anySealedSecret().ifPresent(sample -> requireOpens(column.secretColumn(), sample));
        }
        int sealed = 0;
        for (StoredSecretColumn column : columns) {
            int count = column.sealPlainSecrets(secrets);
            if (count > 0) {
                log.info("stored_secrets_sealed column={} count={}", column.secretColumn(), count);
            }
            sealed += count;
        }
        return sealed;
    }

    private void requireOpens(String column, String sample) {
        try {
            secrets.open(sample, column);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    StoredSecrets.KEY_VARIABLE + " does not open the secrets stored in " + column
                            + ": the key of this installation has changed. Restore the previous key.",
                    e);
        }
    }
}
