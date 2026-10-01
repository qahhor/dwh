package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.web.Revisions;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plan 10/10, item 3.6: a change of a record names the revision it was made from, so the second of two concurrent
 * saves is refused (409) instead of overwriting the first, and a change without it is 428. A {@code PUT} or
 * {@code PATCH} handler takes {@code If-Match} or a body with {@code expectedRevision}/{@code lockVersion}, unless it
 * sets a state that does not depend on what the client read (a pin, an on/off switch, the viewer's own settings) —
 * each such handler is named here with the reason. A handler that replaces a shared record whole is not a state
 * setter: two administrators replacing it from different reads still lose one of the two changes. The item has no
 * debt left: a new handler that saves a shared record names its revision, or the test is red.
 */
class ChangesNameTheirRevisionTest {

    private static final String ROOT = "com.smartup24.cms.instance";

    private static final Set<String> BODY_REVISION = Set.of("expectedRevision", "lockVersion");

    /** Handlers that set a state whatever the client read: repeating them changes nothing (idempotent). */
    private static final Map<String, String> STATE_SETTERS = Map.ofEntries(
            Map.entry("ModuleRegistryController#setEnabled", "on or off, as asked"),
            Map.entry("NavigationItemController#setActive", "shown or hidden, as asked"),
            Map.entry("MsNotificationController#updatePreferences", "the viewer's own delivery choices"),
            Map.entry("MdSettingController#updateUserSettings", "the viewer's own settings"));

    /**
     * Handlers whose body is decoded by hand and names the revision under its own name, so the signature does not show
     * it: each is named here with where the revision is checked.
     */
    private static final Map<String, String> REVISION_IN_RAW_BODY = Map.ofEntries(Map.entry(
            "SearchManagementController#save",
            "SaveSettingsRequest.version, checked by SearchSettingsRepository#save (409 when it moved)"));

    @Test
    @DisplayName("3.6: a PUT or PATCH of a record requires the revision it was made from")
    void changesNameTheirRevision() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        TreeSet<String> unlocked = new TreeSet<>();
        TreeSet<String> changes = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(ROOT)) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(PutMapping.class) && !method.isAnnotationPresent(PatchMapping.class)) {
                    continue;
                }
                String name = controller.getSimpleName() + "#" + method.getName();
                changes.add(name);
                if (!namesRevision(method)) {
                    unlocked.add(name);
                }
            }
        }
        TreeSet<String> allowed = new TreeSet<>(STATE_SETTERS.keySet());
        allowed.addAll(REVISION_IN_RAW_BODY.keySet());

        assertThat(unlocked)
                .as("PUT/PATCH handlers that save a record without its revision: take If-Match (Revisions.required)")
                .isSubsetOf(allowed);
        assertThat(allowed)
                .as("handlers named here that no longer exist: remove them")
                .isSubsetOf(changes);
        assertThat(allowed)
                .as("handlers named here whose signature now names the revision: remove them")
                .isSubsetOf(unlocked);
    }

    private static boolean namesRevision(Method method) {
        for (Parameter parameter : method.getParameters()) {
            RequestHeader header = parameter.getAnnotation(RequestHeader.class);
            if (header != null
                    && (Revisions.IF_MATCH.equals(header.name()) || Revisions.IF_MATCH.equals(header.value()))) {
                return true;
            }
            if (parameter.isAnnotationPresent(RequestBody.class) && carriesRevision(parameter.getType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean carriesRevision(Class<?> body) {
        if (body.isRecord()) {
            return Arrays.stream(body.getRecordComponents())
                    .map(RecordComponent::getName)
                    .anyMatch(BODY_REVISION::contains);
        }
        return Arrays.stream(body.getMethods())
                .map(Method::getName)
                .anyMatch(name -> name.equals("setExpectedRevision") || name.equals("setLockVersion"));
    }
}
