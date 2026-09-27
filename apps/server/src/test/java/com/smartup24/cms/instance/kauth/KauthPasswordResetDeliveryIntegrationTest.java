package com.smartup24.cms.instance.kauth;

import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import com.smartup24.cms.instance.kauth.service.KauthOtpSender;
import com.smartup24.cms.instance.kauth.service.KauthPasswordResetService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Plan 10/10, item 0.1, wired as in production: the link leaves after the commit, from the event listener, on a
 * thread other than the request's. The service-level test calls the sender directly and cannot see this wiring.
 */
@TestPropertySource(properties = "smc.public-url=https://cms.delivery.test/")
class KauthPasswordResetDeliveryIntegrationTest extends EmbeddedPostgresTest {

    @Autowired
    KauthPasswordResetService resetService;
    @Autowired
    KauthChannelRepository channels;
    @Autowired
    JdbcClient jdbc;
    @MockitoSpyBean
    KauthOtpSender sender;

    @Test
    @DisplayName("0.1: the link leaves after the commit, off the request thread, built from smc.public-url")
    void linkLeavesAfterTheCommitOffTheRequestThread() {
        // A login of its own: the shared test database keeps the rows of other tests.
        String login = "reset_delivery_" + UUID.randomUUID();
        Long userId = jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state)
                        values ('Delivery', :login, :login || '@test.local', 'hash', 'A')
                        returning id
                        """)
                .param("login", login).query(Long.class).single();
        channels.bindOrUpdate(userId, "email", "reset_delivery@mailbox.test", true);
        AtomicReference<String> senderThread = new AtomicReference<>();
        doAnswer(invocation -> {
            senderThread.set(Thread.currentThread().getName());
            return invocation.callRealMethod();
        }).when(sender).send(any(), anyString(), anyString(), anyString());

        resetService.requestReset(login + "@test.local", "10.3.0.1", "ua");

        verify(sender, timeout(5_000)).send(argThat(channel -> channel.userId().equals(userId)), anyString(),
                contains("https://cms.delivery.test/reset-password#token="), anyString());
        assertThat(senderThread.get()).isNotEqualTo(Thread.currentThread().getName());
    }
}
