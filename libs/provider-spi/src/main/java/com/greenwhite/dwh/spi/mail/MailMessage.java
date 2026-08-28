package com.greenwhite.dwh.spi.mail;

import java.util.List;

public record MailMessage(
        String recipientEmail,
        String subject,
        String htmlBody,
        String textBody,
        List<MailAttachment> attachments,
        String idempotencyKey
) {
    public record MailAttachment(
            String filename,
            String contentType,
            byte[] content
    ) {}
}
