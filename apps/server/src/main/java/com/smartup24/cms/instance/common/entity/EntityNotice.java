package com.smartup24.cms.instance.common.entity;

import java.util.List;
import java.util.Map;

/**
 * A notice about a record for the people it concerns (ADR-0043): a document of a live form waits for their step. It is
 * published in the transaction of the change, so a rollback takes it back; the notifications module turns it into an
 * in-app notification for each recipient in the recipient's language. {@code common} names no module, so the event
 * lives here.
 *
 * @param entity     the entity's code
 * @param recordId   the record
 * @param recipients the people to tell, each of whom may open the record
 * @param actorId    who made the change; never told
 * @param titleKey   the dictionary key of the notification's title
 * @param bodyKey    the dictionary key of its text
 * @param params     the values of the texts' placeholders, by language ({@code ru}, {@code uz}, {@code en})
 * @param link       the screen of the record
 * @param sourceCode what collapses repeated notices of the same step
 */
public record EntityNotice(
        String entity,
        long recordId,
        List<Long> recipients,
        long actorId,
        String titleKey,
        String bodyKey,
        Map<String, Map<String, String>> params,
        String link,
        String sourceCode) {

    public EntityNotice {
        recipients = List.copyOf(recipients);
        params = Map.copyOf(params);
    }
}
