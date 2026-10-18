package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.common.entity.EntityNotice;
import com.smartup24.cms.instance.common.security.DataScopes;
import com.smartup24.cms.instance.common.web.RequestZone;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Numbering;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Transition;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.studio.repository.StudioNumberRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * What the documents of live forms need of the platform at a save (ADR-0043): the next number of a form, from its
 * counter of the period in the request's time zone, and the notice of a document that reached a status some people may
 * act on — the holders of the rights of the transitions that leave it and are marked to notify, who may open the record
 * in the form's data scope, without the person who acted and, when the author may take none of them, without the
 * author. The notice goes with the transaction ({@link EntityNotice}).
 */
@Component
public class StudioDocuments {
    /** The most people one notice reaches. */
    static final int MAX_RECIPIENTS = 200;

    static final String TITLE_KEY = "notify.studio_step.title";
    static final String BODY_KEY = "notify.studio_step.body";

    private final StudioNumberRepository numbers;
    private final MdPermissionService permissions;
    private final DataScopes scopes;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public StudioDocuments(
            StudioNumberRepository numbers,
            MdPermissionService permissions,
            DataScopes scopes,
            ApplicationEventPublisher events) {
        this(numbers, permissions, scopes, events, Clock.systemUTC());
    }

    public StudioDocuments(
            StudioNumberRepository numbers,
            MdPermissionService permissions,
            DataScopes scopes,
            ApplicationEventPublisher events,
            Clock clock) {
        this.numbers = numbers;
        this.permissions = permissions;
        this.scopes = scopes;
        this.events = events;
        this.clock = clock;
    }

    /** The next number of the form, from its counter of the period of today in the request's time zone. */
    public String number(long formId, Numbering numbering) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), RequestZone.current());
        long counter = numbers.next(formId, StudioNumbers.period(numbering.reset(), today));
        return StudioNumbers.format(numbering.pattern(), today, counter);
    }

    /** A document that reached a status: who is told, and in what words. */
    public record Step(
            String entity,
            String form,
            String scope,
            long recordId,
            @Nullable Long author,
            @Nullable Long orgUnit,
            long actor,
            String recordTitle,
            Labels formTitle,
            String stateCode,
            Labels state,
            List<Transition> next) {
        public Step {
            next = List.copyOf(next);
        }
    }

    /** Tells the people the step waits for; nobody when no transition of the status notifies. */
    public void notify(Step step) {
        if (step.next().isEmpty()) return;
        Set<String> actions = new LinkedHashSet<>();
        step.next().forEach(transition -> actions.add(transition.code()));
        boolean authorExcluded =
                step.next().stream().allMatch(transition -> Boolean.TRUE.equals(transition.notAuthor()));
        List<Long> recipients = new ArrayList<>();
        for (Long user : permissions.holders(step.form(), actions, MAX_RECIPIENTS)) {
            if (user == step.actor() || (authorExcluded && Objects.equals(user, step.author()))) continue;
            if (sees(step, user)) recipients.add(user);
        }
        if (recipients.isEmpty()) return;
        Map<String, Map<String, String>> params = new LinkedHashMap<>();
        for (String language : List.of("ru", "uz", "en")) {
            List<String> named = step.next().stream()
                    .map(transition -> label(transition.labels(), language))
                    .toList();
            params.put(
                    language,
                    Map.of(
                            "form", label(step.formTitle(), language),
                            "record", step.recordTitle(),
                            "state", label(step.state(), language),
                            "actions", String.join(", ", named)));
        }
        events.publishEvent(new EntityNotice(
                step.entity(),
                step.recordId(),
                recipients,
                step.actor(),
                TITLE_KEY,
                BODY_KEY,
                params,
                "/e/" + step.entity() + "/" + step.recordId(),
                "studio-step-" + step.entity() + "-" + step.recordId() + "-" + step.stateCode()));
    }

    /** Whether the person may open the record in the form's data scope: all, their org units or their own records. */
    private boolean sees(Step step, long user) {
        return switch (step.scope()) {
            case "owner" -> Objects.equals(step.author(), user);
            case "org_unit" ->
                Objects.equals(step.author(), user)
                        || (step.orgUnit() != null && scopes.unitVisible(user, step.orgUnit()));
            default -> true;
        };
    }

    static String label(Labels labels, String language) {
        return switch (language) {
            case "uz" -> labels.uz();
            case "en" -> labels.en();
            default -> labels.ru();
        };
    }
}
