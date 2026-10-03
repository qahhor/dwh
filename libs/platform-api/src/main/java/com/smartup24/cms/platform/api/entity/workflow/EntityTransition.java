package com.smartup24.cms.platform.api.entity.workflow;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import com.smartup24.cms.platform.api.entity.hook.EntityRule;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A transition of a process (ADR-0032, 9.2): a record action that moves the record from one of {@code from} to
 * {@code to}, with the right of the entity's form it needs (ADR-0028), the rules the record must meet to take it and,
 * for a transition to confirm, the dictionary key of the question.
 *
 * @param code       the action's code ({@code post})
 * @param from       the states it leaves
 * @param to         the state it reaches
 * @param permission the action of the entity's right it needs ({@code post})
 * @param confirmKey the dictionary key of the question the screen asks first, or null
 * @param rules      the rules over the record as the transition leaves it (ADR-0032, 6.6)
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public record EntityTransition(
        String code,
        Set<String> from,
        String to,
        String permission,
        @Nullable String confirmKey,
        List<EntityRule> rules) {

    public EntityTransition {
        Objects.requireNonNull(code, "code");
        from = Set.copyOf(from);
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(permission, "permission");
        rules = List.copyOf(rules);
        if (from.isEmpty()) {
            throw new IllegalArgumentException("Transition " + code + " leaves no state");
        }
    }
}
