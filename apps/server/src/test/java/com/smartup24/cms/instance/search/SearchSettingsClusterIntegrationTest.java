package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.cache.CacheInvalidations;
import com.smartup24.cms.instance.search.api.SearchManagementDtos.SaveSettingsRequest;
import com.smartup24.cms.instance.search.api.SearchManagementDtos.SettingsSnapshot;
import com.smartup24.cms.instance.search.api.SearchOwnerRateLimits;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import com.smartup24.cms.instance.search.service.SearchPolicyProvider;
import com.smartup24.cms.instance.search.service.SearchSettingsService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 10/10, item 3.13 (ADR-0025): the search settings are a node-local copy, kept in step like the caches. A save
 * on one node reaches the other within two seconds of its commit; a rolled-back save reaches nobody. The second node
 * is a provider of its own with its own listener on the same database.
 */
class SearchSettingsClusterIntegrationTest extends EmbeddedPostgresTest {

    private static final Duration WITHIN = Duration.ofSeconds(2);

    @Autowired
    private SearchSettingsService settings;

    @Autowired
    private SearchSettingsRepository repository;

    @Autowired
    private SearchOwnerRateLimits ownerRateLimits;

    @Autowired
    private CacheInvalidations invalidations;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    private CacheInvalidations secondNode;
    private SearchPolicyProvider secondProvider;

    @BeforeEach
    void startSecondNode() {
        secondNode = new CacheInvalidations(dataSource, jdbc);
        secondProvider = new SearchPolicyProvider(ownerRateLimits, repository, secondNode);
        secondProvider.refresh();
        secondNode.start();
        awaitTrue(() -> secondNode.isListening() && invalidations.isListening(), Duration.ofSeconds(10));
        long user = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                user, "system", "system@localhost", null, false, Set.of("*.*"), 1, false, 0, null));
    }

    @AfterEach
    void stopSecondNode() {
        SecurityContext.clear();
        secondNode.stop();
    }

    @Test
    @DisplayName("3.13: a settings save on one node is seen by the other within two seconds")
    void saveReachesTheOtherNode() {
        SettingsSnapshot before = repository.current();
        assertThat(secondProvider.snapshot().version()).isEqualTo(before.version());

        long started = System.nanoTime();
        SettingsSnapshot saved = settings.save(new SaveSettingsRequest(before.version(), before.policy()));

        awaitTrue(() -> secondProvider.snapshot().version() == saved.version(), WITHIN);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(WITHIN);
    }

    @Test
    @DisplayName("3.13: a rolled-back settings save tells no other node")
    void rolledBackSaveTellsNobody() throws InterruptedException {
        SettingsSnapshot before = repository.current();
        AtomicInteger notices = new AtomicInteger();
        secondNode.onNotice(SearchPolicyProvider.NOTICE, notices::incrementAndGet);

        transactions.executeWithoutResult(status -> {
            settings.save(new SaveSettingsRequest(before.version(), before.policy()));
            status.setRollbackOnly();
        });

        Thread.sleep(WITHIN.toMillis());
        assertThat(repository.current().version()).isEqualTo(before.version());
        assertThat(notices).hasValue(0);
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", interrupted);
            }
        }
    }
}
