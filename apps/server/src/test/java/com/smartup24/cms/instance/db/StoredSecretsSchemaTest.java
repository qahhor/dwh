package com.smartup24.cms.instance.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.security.StoredSecrets;
import com.smartup24.cms.instance.common.security.StoredSecretsSealing;
import com.smartup24.cms.instance.kauth.repository.SsoProviderRepository;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.instance.support.TestStoredSecrets;
import com.smartup24.cms.instance.webhook.repository.WebhookSubscriptionRepository;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 4.6 (ADR-0029): secrets in the database are kept only encrypted. Every column named like a secret
 * is classified here: encrypted with the installation key, a one-way hash, or not a secret at all.
 */
class StoredSecretsSchemaTest {

    /** Columns that hold a secret the server must read back: AES-256-GCM with the installation key. */
    static final List<String> ENCRYPTED =
            List.of(WebhookSubscriptionRepository.SECRET_COLUMN, SsoProviderRepository.SECRET_COLUMN);

    /** Columns named like a secret that hold something else, with the reason. */
    static final Map<String, String> NOT_PLAIN_SECRETS = Map.of(
            "md_users.password_hash", "an Argon2id hash",
            "kauth_sessions.token_hash", "a SHA-256 hash of the session token",
            "kauth_api_tokens.token_hash", "a SHA-256 hash of the API token",
            "kauth_otp_codes.otp_token_hash", "a SHA-256 hash of the one-time token",
            "kauth_api_tokens.token_prefix", "the first characters of a token, shown to tell tokens apart",
            "md_sso_providers.token_url", "an address of the identity provider");

    /** Text columns of base tables whose names say they could hold a secret. */
    static final String SECRET_LIKE_COLUMNS = """
            select c.relname || '.' || a.attname
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            where c.relnamespace = 'public'::regnamespace
              and c.relkind in ('r', 'p')
              and not c.relispartition
              and a.attnum > 0 and not a.attisdropped
              and a.atttypid in ('text'::regtype, 'varchar'::regtype, 'bpchar'::regtype, 'bytea'::regtype,
                                 'jsonb'::regtype, 'json'::regtype)
              and a.attname ~ '(secret|token|password|passwd|api_?key|credential|private_?key)'
            order by 1
            """;

    private static JdbcClient jdbc;
    private static StoredSecrets secrets;
    private static WebhookSubscriptionRepository subscriptions;
    private static SsoProviderRepository providers;
    private static StoredSecretsSealing sealing;

    @BeforeAll
    static void migrate() {
        jdbc = JdbcClient.create(TestDatabases.migratedCopy("stored_secrets"));
        secrets = TestStoredSecrets.secrets();
        subscriptions = new WebhookSubscriptionRepository(jdbc, secrets);
        providers = new SsoProviderRepository(jdbc, secrets);
        sealing = new StoredSecretsSealing(List.of(subscriptions, providers), secrets);
    }

    @Test
    @DisplayName("4.6: every column named like a secret is encrypted, hashed or listed as not a secret")
    void secretColumnsAreClassified() {
        TreeSet<String> expected = new TreeSet<>(ENCRYPTED);
        expected.addAll(NOT_PLAIN_SECRETS.keySet());
        assertThat(new TreeSet<>(
                        jdbc.sql(SECRET_LIKE_COLUMNS).query(String.class).list()))
                .as("a new secret column is encrypted through StoredSecrets or listed with its reason")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("4.6: after the start and a write through the repository, secret columns hold only encrypted values")
    void secretsAreStoredEncrypted() {
        // A legacy plain value, as an installation older than the encryption keeps it
        jdbc.sql("""
                        insert into kwh_subscriptions (name, target_url, secret_token, subscribed_events, state)
                        values ('legacy', 'https://hooks.example.test/legacy', 'legacy-plain-key',
                                array['task.created'], 'A')
                        """).update();

        sealing.sealAll();
        var created = subscriptions.create(
                "fresh", "https://hooks.example.test/fresh", "fresh-plain-key", List.of("task.created"), null);

        for (String column : ENCRYPTED) {
            String table = column.substring(0, column.indexOf('.'));
            String field = column.substring(column.indexOf('.') + 1);
            List<String> stored = jdbc.sql("select " + field + " from " + table + " where " + field + " is not null")
                    .query(String.class)
                    .list();
            assertThat(stored)
                    .as(column)
                    .isNotEmpty()
                    .allSatisfy(value ->
                            assertThat(StoredSecrets.isSealed(value)).as(column).isTrue());
        }
        assertThat(created.secretToken()).isEqualTo("fresh-plain-key");
        assertThat(subscriptions.findById(created.id()).orElseThrow().secretToken())
                .isEqualTo("fresh-plain-key");
        assertThat(subscriptions.listSubscriptions())
                .extracting(WebhookSubscriptionRepository.SubscriptionRecord::secretToken)
                .contains("legacy-plain-key", "fresh-plain-key");
        String seed = jdbc.sql("select client_secret from md_sso_providers where provider_id = 'google'")
                .query(String.class)
                .single();
        assertThat(secrets.open(seed, SsoProviderRepository.SECRET_COLUMN)).isEqualTo("secret");
        assertThat(sealing.sealAll())
                .as("a second start has nothing left to encrypt")
                .isZero();
    }

    @Test
    @DisplayName("4.6: a start with another key is refused before anything is written")
    void anotherKeyIsRefused() {
        sealing.sealAll();
        byte[] other = new byte[32];
        new SecureRandom().nextBytes(other);
        StoredSecrets wrong = StoredSecrets.withKey(other);
        var wrongSealing = new StoredSecretsSealing(
                List.of(new WebhookSubscriptionRepository(jdbc, wrong), new SsoProviderRepository(jdbc, wrong)), wrong);

        assertThatThrownBy(wrongSealing::sealAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(StoredSecrets.KEY_VARIABLE);
    }
}
