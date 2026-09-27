package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.md.service.MdI18nService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Delivers a password reset link to the user's confirmed channel (plan 10/10, item 0.1).
 *
 * <p>Delivery runs after the commit and off the request thread: the answer to a reset request must take the same
 * time whether the email is known or not, and a slow mail server must not show which one it is. A failed delivery
 * is logged; the user asks for a new link.
 *
 * <p>The link is built from {@code smc.public-url} and never from the request's Host header, which the requester
 * controls: a link pointing at a foreign host would hand the token to that host. The text is in the user's language
 * ({@code channel.password_reset.*}, Russian when a string is missing).
 */
@Component
@Profile("!migrate")
public class KauthPasswordResetLinkSender {

    private static final Logger log = LoggerFactory.getLogger(KauthPasswordResetLinkSender.class);

    static final String PATH = "/reset-password#token=";
    static final String SUBJECT_KEY = "channel.password_reset.subject";
    static final String BODY_KEY = "channel.password_reset.body";

    private final KauthOtpSender sender;
    private final MdI18nService i18n;
    private final String publicUrl;

    public KauthPasswordResetLinkSender(KauthOtpSender sender, MdI18nService i18n,
                                        @Value("${smc.public-url:}") String publicUrl) {
        this.sender = sender;
        this.i18n = i18n;
        this.publicUrl = publicUrl == null ? "" : publicUrl.strip().replaceAll("/+$", "");
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLinkIssued(KauthPasswordResetLinkIssued event) {
        if (publicUrl.isEmpty()) {
            log.error("password_reset_link_not_sent reason=smc.public-url_not_set channel={}", event.channel().channel());
            return;
        }
        // Rounded up: a link issued a moment ago has 14:59 left and must still read "15 min".
        long seconds = Duration.between(Instant.now(), event.expiresAt()).toSeconds();
        long minutes = Math.max(1, (seconds + 59) / 60);
        String link = publicUrl + PATH + event.token();
        try {
            Map<String, String> strings = i18n.effectiveDictionary(event.language());
            String body = strings.getOrDefault(BODY_KEY, "{link}")
                    .replace("{link}", link)
                    .replace("{minutes}", Long.toString(minutes));
            sender.send(event.channel(), strings.getOrDefault(SUBJECT_KEY, "SmartupCMS"), body,
                    "reset-" + KauthPasswordHasher.sha256(event.token()));
        } catch (RuntimeException e) {
            // The address is personal data: the log gets the channel only.
            log.warn("password_reset_link_not_sent channel={}", event.channel().channel(), e);
        }
    }
}
