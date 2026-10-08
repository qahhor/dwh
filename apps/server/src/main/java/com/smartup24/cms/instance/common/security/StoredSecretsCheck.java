package com.smartup24.cms.instance.common.security;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * At every start: refuses a key that does not open the stored secrets, and a secret column that holds a value in plain
 * text (ADR-0029). Secrets are written only encrypted; a plain value means a row written around the application, and
 * the start stops with a message that names the column, never the value.
 */
@Component
@Profile("!migrate")
public class StoredSecretsCheck implements ApplicationRunner {

    private final List<StoredSecretColumn> columns;
    private final StoredSecrets secrets;

    public StoredSecretsCheck(List<StoredSecretColumn> columns, StoredSecrets secrets) {
        this.columns = columns;
        this.secrets = secrets;
    }

    @Override
    public void run(ApplicationArguments args) {
        checkAll();
    }

    /**
     * Checks the key against every column, then that no column holds a plain value.
     *
     * @throws IllegalStateException when the key does not open a stored value or a column holds a plain value
     */
    public void checkAll() {
        for (StoredSecretColumn column : columns) {
            column.anySealedSecret().ifPresent(sample -> requireOpens(column.secretColumn(), sample));
        }
        for (StoredSecretColumn column : columns) {
            int plain = column.countPlainSecrets();
            if (plain > 0) {
                throw new IllegalStateException(StoredSecrets.plainRefused(column.secretColumn(), plain));
            }
        }
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
