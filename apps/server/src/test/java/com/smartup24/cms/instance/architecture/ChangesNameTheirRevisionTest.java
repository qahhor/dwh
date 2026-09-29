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
 * each such handler is named here with the reason. {@link #NOT_YET_LOCKED} is the debt of the item: it only shrinks.
 */
class ChangesNameTheirRevisionTest {

    private static final String ROOT = "com.smartup24.cms.instance";

    private static final Set<String> BODY_REVISION = Set.of("expectedRevision", "lockVersion");

    /** Handlers that set a state whatever the client read: repeating them changes nothing (idempotent). */
    private static final Map<String, String> STATE_SETTERS = Map.ofEntries(
            Map.entry("MsNoteController#setPin", "the pin the viewer asks for"),
            Map.entry("ModuleRegistryController#setEnabled", "on or off, as asked"),
            Map.entry("ModuleRegistryController#putModule", "a module registration is replaced whole"),
            Map.entry("NavigationItemController#setActive", "shown or hidden, as asked"),
            Map.entry("MsNotificationController#updatePreferences", "the viewer's own delivery choices"),
            Map.entry("MdSettingController#updateUserSettings", "the viewer's own settings"),
            Map.entry("MdOrgUnitController#assignUser", "the units of a user are replaced whole"),
            Map.entry("MdOrgUnitController#setRoleRule", "the scope rule of a role is replaced whole"));

    /** Changes of shared records still saved without a revision: debt of item 3.6, which only shrinks. */
    private static final Map<String, String> NOT_YET_LOCKED = Map.ofEntries(
            Map.entry("MdSettingController#updateSystemSettings", "system settings: a key-value map without revision"),
            Map.entry("SearchManagementController#save", "search settings: one row without revision"),
            Map.entry("MdAssignmentController#replacePersonalPermissions", "personal rights of a user"),
            Map.entry("MdAssignmentController#assignRoles", "roles of a user"));

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
        allowed.addAll(NOT_YET_LOCKED.keySet());

        assertThat(unlocked)
                .as("PUT/PATCH handlers that save a record without its revision: take If-Match (Revisions.required)")
                .isSubsetOf(allowed);
        assertThat(allowed)
                .as("handlers named here that no longer exist: remove them")
                .isSubsetOf(changes);
        assertThat(NOT_YET_LOCKED.keySet())
                .as("handlers of the debt that now name their revision: remove them from NOT_YET_LOCKED")
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
