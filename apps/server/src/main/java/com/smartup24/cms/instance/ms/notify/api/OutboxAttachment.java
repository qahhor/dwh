package com.smartup24.cms.instance.ms.notify.api;

/**
 * One file a letter of the notification outbox carries (ADR-0045): kept with the letter until it is sent or given up.
 *
 * @param fileName    the name the recipient sees
 * @param contentType the media type of the file
 * @param content     the bytes of the file
 */
public record OutboxAttachment(String fileName, String contentType, byte[] content) {}
