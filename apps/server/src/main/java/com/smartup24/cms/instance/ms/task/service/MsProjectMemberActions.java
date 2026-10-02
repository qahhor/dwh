package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.hook.EntityActionCall;
import com.smartup24.cms.instance.common.entity.hook.EntityActionHandler;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The members of a project as record actions (ADR-0032, 6.7 and 8; collections come with ADR-0032, 9.1): {@code POST
 * /api/v1/entities/ms.projects/{id}/actions/add_member {"userId": 5, "accessKind": "W"}} and {@code .../remove_member
 * {"userId": 5}} with If-Match. The runtime reads the project in the actor's scope, raises its revision and audits the
 * action; {@link MsProjectMemberService} writes the membership and audits the access change.
 */
@Configuration
public class MsProjectMemberActions {

    @Bean
    public EntityActionHandler msProjectAddMember(MsProjectMemberService members) {
        return handler(
                MsProjectEntity.ADD_MEMBER,
                call -> members.addMember(
                        project(call),
                        userId(call),
                        text(call.params().get("accessKind")),
                        call.actor().userId()));
    }

    @Bean
    public EntityActionHandler msProjectRemoveMember(MsProjectMemberService members) {
        return handler(MsProjectEntity.REMOVE_MEMBER, call -> {
            Long userId = userId(call);
            if (userId == null) {
                return FieldErrorItem.keyed("params.userId", "required", "error.field.required");
            }
            members.removeMember(project(call), userId);
            return null;
        });
    }

    /** What an action does: null, or the problem of its parameters. */
    @FunctionalInterface
    private interface Action {
        @Nullable
        FieldErrorItem run(EntityActionCall call);
    }

    private static EntityActionHandler handler(String code, Action action) {
        return new EntityActionHandler() {
            @Override
            public String entity() {
                return MsProjectEntity.CODE;
            }

            @Override
            public String action() {
                return code;
            }

            @Override
            public void run(EntityActionCall call) {
                FieldErrorItem problem = action.run(call);
                if (problem != null) {
                    call.reject(problem.field(), problem.code(), problem.messageKey(), params(problem));
                }
            }
        };
    }

    private static long project(EntityActionCall call) {
        return Objects.requireNonNull(call.id());
    }

    /** The user the action names: a whole number, or nothing. */
    private static @Nullable Long userId(EntityActionCall call) {
        Object value = call.params().get("userId");
        return value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
                ? number.longValue()
                : null;
    }

    private static @Nullable String text(@Nullable Object value) {
        return value instanceof String string ? string : null;
    }

    private static Map<String, ?> params(FieldErrorItem problem) {
        return problem.params() == null ? Map.of() : problem.params();
    }
}
