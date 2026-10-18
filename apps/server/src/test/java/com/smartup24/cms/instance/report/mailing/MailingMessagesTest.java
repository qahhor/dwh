package com.smartup24.cms.instance.report.mailing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.md.service.MdUserTexts;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0045: the words of a mailing — the link without a token, the letter's notes, Telegram's escaped marks. */
class MailingMessagesTest {

    private static MdUserTexts texts() {
        MdUserTexts texts = mock(MdUserTexts.class);
        when(texts.text(anyLong(), anyString(), anyMap())).thenAnswer(call -> {
            String key = call.getArgument(1);
            Map<String, String> params = call.getArgument(2);
            return key
                    + params.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> " " + entry.getKey() + "=" + entry.getValue())
                            .reduce("", String::concat);
        });
        return texts;
    }

    private static MailingMessages.Subject subject(String parameters) {
        return new MailingMessages.Subject(
                7L,
                "Итоги_*месяца*",
                "studio.orders",
                12L,
                parameters,
                Instant.parse("2026-10-31T21:00:00Z"),
                ZoneId.of("Asia/Tashkent"),
                3);
    }

    @Test
    @DisplayName("ADR-0045: the link opens the report with its parameters and carries no token")
    void link() {
        var messages = new MailingMessages(texts(), "https://cms.example/ ");
        assertThat(MailingMessages.path("studio.orders", 12, "{}")).isEqualTo("/e/studio.orders?mode=report&report=12");
        assertThat(messages.link(subject("{\"period\":{\"preset\":\"last_month\"}}")))
                .isEqualTo("https://cms.example/e/studio.orders?mode=report&report=12&parameters="
                        + "%7B%22period%22%3A%7B%22preset%22%3A%22last_month%22%7D%7D")
                .doesNotContain("token");
        assertThat(new MailingMessages(texts(), "").link(subject("{}"))).isNull();
    }

    @Test
    @DisplayName("ADR-0045: a letter says what is attached, why a file is missing, and links the report")
    void letter() {
        var messages = new MailingMessages(texts(), "https://cms.example");
        String attached = messages.letter(subject("{}"), true, false, false, 5 * 1024 * 1024);
        assertThat(attached)
                .contains("channel.report_mailing.body", "date=01.11.2026", "rows=3", "channel.report_mailing.attached")
                .doesNotContain("channel.report_mailing.link");
        String tooLarge = messages.letter(subject("{}"), false, true, false, 5 * 1024 * 1024);
        assertThat(tooLarge)
                .contains(
                        "channel.report_mailing.too_large",
                        "size=5",
                        "channel.report_mailing.link",
                        "link=https://cms.example/e/")
                .contains("channel.report_mailing.footer");
        assertThat(messages.subject(subject("{}"))).contains("channel.report_mailing.subject", "name=Итоги_*месяца*");
    }

    @Test
    @DisplayName("ADR-0045: Telegram gets the name with its marks escaped, in-app the same words")
    void telegramAndInApp() {
        var messages = new MailingMessages(texts(), "https://cms.example");
        assertThat(messages.telegram(subject("{}"))).contains("name=Итоги\\_\\*месяца\\*");
        assertThat(messages.buttonText(subject("{}"))).startsWith("channel.report_mailing.open");
        assertThat(messages.inAppTitle(subject("{}"))).startsWith("notify.report_mailing.title");
        assertThat(messages.inAppBody(subject("{}"))).contains("rows=3");
        assertThat(MailingMessages.markdown("a_b[c]`d")).isEqualTo("a\\_b\\[c]\\`d");
    }
}
