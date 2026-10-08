package com.smartup24.cms.instance.kauth.service;

import com.smartup24.cms.instance.kauth.repository.KauthChannelRepository;
import com.smartup24.cms.instance.md.service.MdUserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start while users with two-factor sign-in depend on a stub delivery channel (plan 10/10, item 0.8).
 *
 * <p>A stub channel ({@code console_*}) writes the message to the log and reports success. With two-factor sign-in
 * on, the login code of such a user goes nowhere, and the user is locked out until an administrator notices. The
 * guard resolves each user's code channel the way sign-in does ({@link KauthChannelService#OTP_CHANNEL_PRIORITY})
 * and stops the start when the provider behind it is a stub. The users come from md, their owner; the channels are
 * kauth's own (ADR-0026). {@code SMC_DELIVERY_ENFORCE=false} turns it off for
 * development, where the log is the intended channel. At run time {@link KauthOtpSender#requireDeliverable} keeps a
 * stubbed channel from being bound, so only a configuration change can bring the instance here.
 */
@Component
@Profile("!migrate")
public class KauthDeliveryGuard implements ApplicationRunner {

    private final KauthOtpSender sender;
    private final KauthChannelRepository channels;
    private final MdUserService users;
    private final boolean enforced;

    public KauthDeliveryGuard(
            KauthOtpSender sender,
            KauthChannelRepository channels,
            MdUserService users,
            @Value("${smc.delivery.enforce:true}") boolean enforced) {
        this.sender = sender;
        this.channels = channels;
        this.users = users;
        this.enforced = enforced;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enforced) {
            return;
        }
        List<String> stubbed = stubbedCodeChannels();
        if (!stubbed.isEmpty()) {
            throw new IllegalStateException("Two-factor sign-in depends on a stub delivery channel: "
                    + String.join(", ", stubbed) + ". Configure the provider (SMC_PROVIDER_MAIL with SMTP_HOST, "
                    + "SMC_PROVIDER_MESSENGER with TELEGRAM_BOT_TOKEN) or set SMC_DELIVERY_ENFORCE=false knowingly.");
        }
    }

    /** Code channels of active two-factor users whose provider is a stub, with the number of users on each. */
    List<String> stubbedCodeChannels() {
        Map<String, Long> resolved =
                channels.codeChannelUsers(users.activeTwoFactorUserIds(), KauthChannelService.OTP_CHANNEL_PRIORITY);
        List<String> stubbed = new ArrayList<>();
        resolved.forEach((channel, count) -> {
            String provider = sender.providerCode(channel);
            if (KauthOtpSender.isStub(provider)) {
                stubbed.add(channel + " -> " + provider + " (" + count + " users)");
            }
        });
        return stubbed;
    }
}
