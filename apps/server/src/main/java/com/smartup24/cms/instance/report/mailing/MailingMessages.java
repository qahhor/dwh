package com.smartup24.cms.instance.report.mailing;

import com.smartup24.cms.instance.md.service.MdUserTexts;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The words of a mailing in its recipient's language (ADR-0045): the catalog's texts with their placeholders filled.
 *
 * <p>The link opens the saved report in the application with the mailing's parameters; the recipient signs in and sees
 * the report through their own rights. It carries no token — nothing in it grants access to data. A letter or a
 * Telegram message carries it only from {@code smc.public-url}, never from a request's host; the in-app notification
 * carries the path.
 */
@Component
public class MailingMessages {
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final MdUserTexts texts;
    private final String publicUrl;

    public MailingMessages(MdUserTexts texts, @Value("${smc.public-url:}") String publicUrl) {
        this.texts = texts;
        this.publicUrl = publicUrl == null ? "" : publicUrl.strip().replaceAll("/+$", "");
    }

    /** What a message is about: the mailing, its slot and the recipient's result. */
    public record Subject(
            long userId,
            String name,
            String entity,
            long viewId,
            String parameters,
            Instant slot,
            ZoneId zone,
            int rows) {}

    /** The path in the application that opens the saved report with the mailing's parameters. */
    public static String path(String entity, long viewId, String parameters) {
        String path = "/e/" + entity + "?mode=report&report=" + viewId;
        if (parameters.isBlank() || "{}".equals(parameters.strip())) return path;
        return path + "&parameters=" + URLEncoder.encode(parameters, StandardCharsets.UTF_8);
    }

    /** The absolute link, or null without {@code smc.public-url}. */
    public @Nullable String link(Subject subject) {
        return publicUrl.isEmpty() ? null : publicUrl + path(subject.entity(), subject.viewId(), subject.parameters());
    }

    public String subject(Subject subject) {
        return text(subject, "channel.report_mailing.subject", Map.of());
    }

    /**
     * The text of a letter: what it is, the file or why it is missing, the link when the mailing gives one.
     *
     * @param attached the XLSX is attached
     * @param tooLarge the XLSX was left out for its size
     */
    public String letter(Subject subject, boolean attached, boolean tooLarge, boolean withLink, long maxBytes) {
        StringBuilder body = new StringBuilder(text(subject, "channel.report_mailing.body", Map.of()));
        if (attached) body.append("\n\n").append(text(subject, "channel.report_mailing.attached", Map.of()));
        if (tooLarge) {
            String megabytes = Long.toString(Math.max(1, maxBytes / (1024 * 1024)));
            body.append("\n\n").append(text(subject, "channel.report_mailing.too_large", Map.of("size", megabytes)));
        }
        String link = withLink || tooLarge ? link(subject) : null;
        if (link != null)
            body.append("\n\n").append(text(subject, "channel.report_mailing.link", Map.of("link", link)));
        body.append("\n\n").append(text(subject, "channel.report_mailing.footer", Map.of()));
        return body.toString();
    }

    /** The text of a Telegram message; its link travels as a button. */
    public String telegram(Subject subject) {
        return text(subject, "channel.report_mailing.telegram", Map.of("name", markdown(subject.name())));
    }

    public String buttonText(Subject subject) {
        return text(subject, "channel.report_mailing.open", Map.of());
    }

    public String inAppTitle(Subject subject) {
        return text(subject, "notify.report_mailing.title", Map.of());
    }

    public String inAppBody(Subject subject) {
        return text(subject, "notify.report_mailing.body", Map.of());
    }

    private String text(Subject subject, String key, Map<String, String> extra) {
        Map<String, String> params = new java.util.HashMap<>();
        params.put("name", subject.name());
        params.put("date", DATE.format(subject.slot().atZone(subject.zone())));
        params.put("rows", Integer.toString(subject.rows()));
        params.putAll(extra);
        return texts.text(subject.userId(), key, params);
    }

    /** A name inside Telegram's Markdown: its marks are escaped, so a name with them is still delivered. */
    static String markdown(String text) {
        return text.replaceAll("([_*\\[`])", "\\\\$1");
    }
}
