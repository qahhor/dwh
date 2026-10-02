package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import com.smartup24.cms.instance.kauth.repository.KauthPasswordResetRepository;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.md.service.UserInvitations;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations of new users (ADR-0032, 8 and 19, question 10 — the proposed default, taken as an assumption until the
 * product owner answers it): a user created through the user entity has no password; the invitation is a one-time
 * link to the e-mail the administrator gave, which sets the first password by the same confirmation as a password
 * reset ({@code POST /api/v1/auth/password-reset/confirm}) under the same password policy.
 *
 * <ul>
 *   <li>The link lives {@link #INVITATION_TTL}, longer than a reset link: the person is not waiting at the screen.
 *   <li>The token has 256 bits, is stored as a hash, belongs to one user and one authentication generation and is used
 *       up by one atomic update; a new invitation voids the previous one, and so does a reset link.
 *   <li>The address is not a confirmed channel yet: it is the one the administrator typed, so the message names only
 *       the login and the link, nothing else of the account.
 * </ul>
 */
@Service
public class KauthInvitationService implements UserInvitations {

    /** How long an invitation link works (assumption: three days). */
    static final Duration INVITATION_TTL = Duration.ofHours(72);

    private final MdUserService users;
    private final KauthPasswordResetRepository links;
    private final AuditLogService audit;
    private final ApplicationEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    public KauthInvitationService(
            MdUserService users,
            KauthPasswordResetRepository links,
            AuditLogService audit,
            ApplicationEventPublisher events) {
        this.users = users;
        this.links = links;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public void invite(long userId) {
        var user = users.findAuthUserById(userId)
                .filter(found -> MdPref.STATE_ACTIVE.equals(found.state()) && found.passwordHash() == null);
        if (user.isEmpty()) {
            return;
        }
        links.lockUser(userId);
        links.revokeActive(userId);
        String token = token();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(INVITATION_TTL);
        links.create(
                userId,
                user.get().authenticationVersion(),
                KauthPref.CHANNEL_EMAIL,
                KauthPasswordHasher.sha256(token),
                expiresAt);
        var channel = new KauthChannelRepository.ChannelRecord(
                null, userId, KauthPref.CHANNEL_EMAIL, user.get().email(), false, now);
        events.publishEvent(new KauthInvitationIssued(channel, user.get().login(), token, expiresAt));
        audit.logSecurityEvent("USER_INVITED", userId, null, null, Map.of("channel", KauthPref.CHANNEL_EMAIL));
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
