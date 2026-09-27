package com.smartup24.cms.instance.kauth.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Refuses to start while users with two-factor sign-in depend on a stub delivery channel (plan 10/10, item 0.8).
 *
 * <p>A stub channel ({@code console_*}) writes the message to the log and reports success. With two-factor sign-in
 * on, the login code of such a user goes nowhere, and the user is locked out until an administrator notices. The
 * guard resolves each user's code channel the way sign-in does ({@link KauthChannelService#OTP_CHANNEL_PRIORITY})
 * and stops the start when the provider behind it is a stub. {@code SMC_DELIVERY_ENFORCE=false} turns it off for
 * development, where the log is the intended channel. At run time {@link KauthOtpSender#requireDeliverable} keeps a
 * stubbed channel from being bound, so only a configuration change can bring the instance here.
 */
@Component
@Profile("!migrate")
public class KauthDeliveryGuard implements ApplicationRunner {

    private final KauthOtpSender sender;
    private final JdbcClient jdbc;
    private final boolean enforced;

    public KauthDeliveryGuard(
            KauthOtpSender sender, JdbcClient jdbc, @Value("${smc.delivery.enforce:true}") boolean enforced) {
        this.sender = sender;
        this.jdbc = jdbc;
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
                    + String.join(", ", stubbed) + ". Configure the provider (DWH_PROVIDER_MAIL with SMTP_HOST, "
                    + "DWH_PROVIDER_MESSENGER with TELEGRAM_BOT_TOKEN) or set SMC_DELIVERY_ENFORCE=false knowingly.");
        }
    }

    /** Code channels of active two-factor users whose provider is a stub, with the number of users on each. */
    List<String> stubbedCodeChannels() {
        // The priority is a constant of the code, never input: it is safe to inline.
        String priority = KauthChannelService.OTP_CHANNEL_PRIORITY.stream()
                .map(channel -> "'" + channel + "'")
                .collect(Collectors.joining(", ", "array[", "]"));
        List<ChannelUsers> resolved = jdbc.sql("""
                        select channel, count(*) as users
                        from (select distinct on (u.id) u.id, c.channel
                              from md_users u
                              join kauth_user_channels c on c.user_id = u.id and c.is_verified
                              where u.is_2fa_enabled and u.state = 'A'
                              order by u.id, array_position(%s, c.channel)) resolved
                        group by channel
                        order by channel
                        """.formatted(priority))
                .query((rs, rowNum) -> new ChannelUsers(rs.getString("channel"), rs.getLong("users")))
                .list();
        List<String> stubbed = new ArrayList<>();
        for (ChannelUsers row : resolved) {
            String provider = sender.providerCode(row.channel());
            if (KauthOtpSender.isStub(provider)) {
                stubbed.add(row.channel() + " -> " + provider + " (" + row.users() + " users)");
            }
        }
        return stubbed;
    }

    private record ChannelUsers(String channel, long users) {}
}
