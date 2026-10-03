package com.smartup24.cms.instance.ms.notify;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smartup24.cms.instance.ms.notify.provider.ConsoleMailProvider;
import com.smartup24.cms.instance.ms.notify.provider.ConsoleMessengerProvider;
import com.smartup24.cms.instance.ms.notify.provider.ConsoleSmsProvider;
import com.smartup24.cms.spi.mail.MailMessage;
import com.smartup24.cms.spi.messenger.MessengerMessage;
import com.smartup24.cms.spi.sms.SmsMessage;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * A stub channel writes that a message was not delivered and nothing of the message: the body carries invitation and
 * reset links and one-time codes, the address is personal data, and a production log is read by more people than the
 * recipient.
 */
class StubProvidersLogTest {

    private static final String LINK = "https://cms.example.test/reset-password#token=SECRET-TOKEN-1";
    private static final String CODE = "Your code: 739154";

    @Test
    @DisplayName("The mail stub logs the masked recipient, the subject and the length, never the link")
    void mailStubHidesTheBody() {
        String logged = logged(
                ConsoleMailProvider.class,
                () -> new ConsoleMailProvider()
                        .send(new MailMessage(
                                "invited.person@example.test",
                                "Invitation",
                                "<p>" + LINK + "</p>",
                                LINK,
                                List.of(),
                                "idem-1")));

        assertThat(logged)
                .contains("i***@example.test", "Invitation", "length=")
                .doesNotContain("SECRET-TOKEN-1", "invited.person");
    }

    @Test
    @DisplayName("The messenger and SMS stubs log neither the code nor the address")
    void messengerAndSmsStubsHideTheCode() {
        String messenger = logged(
                ConsoleMessengerProvider.class,
                () -> new ConsoleMessengerProvider()
                        .send(new MessengerMessage("123456789", CODE, null, null, "idem-2")));
        String sms = logged(
                ConsoleSmsProvider.class,
                () -> new ConsoleSmsProvider().send(new SmsMessage("+998901234567", CODE, null, "idem-3")));

        assertThat(messenger).contains("***89").doesNotContain("739154", "123456789");
        assertThat(sms).contains("***67").doesNotContain("739154", "998901234567");
    }

    private static String logged(Class<?> provider, Runnable send) {
        Logger logger = (Logger) LoggerFactory.getLogger(provider);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            send.run();
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).as("the stub says it delivered nothing").isNotEmpty();
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }
}
