package com.smartup24.cms.spi.mail;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.util.List;

@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record MailMessage(
        String recipientEmail,
        String subject,
        String htmlBody,
        String textBody,
        List<MailAttachment> attachments,
        String idempotencyKey) {
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    public record MailAttachment(String filename, String contentType, byte[] content) {}
}
