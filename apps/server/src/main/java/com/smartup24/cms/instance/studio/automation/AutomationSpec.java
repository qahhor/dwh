package com.smartup24.cms.instance.studio.automation;

import com.smartup24.cms.instance.common.formula.Formula;
import com.smartup24.cms.instance.common.schedule.CalendarSchedule;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Right;
import com.smartup24.cms.instance.studio.api.StudioAutomationDtos.Texts;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * An automation checked against the active versions of the forms it names (ADR-0046): what starts it, the condition and
 * the actions, each formula parsed and typed. Built when the automation is saved and again for every run, so a run
 * follows the forms as they are then.
 *
 * @param form      the live form whose records start the automation
 * @param trigger   what starts it
 * @param condition the yes/no formula a run must meet, or null
 * @param actions   the actions in order
 * @param zone      the time zone of its schedule and of {@code today()}
 */
public record AutomationSpec(
        String form, Trigger trigger, @Nullable Formula condition, List<Step> actions, ZoneId zone) {

    public static final String CREATED = "created";
    public static final String UPDATED = "updated";
    public static final String SAVED = "saved";
    public static final String TRANSITION = "transition";
    public static final String DELETED = "deleted";
    public static final String SCHEDULED = "scheduled";

    /** The trigger kinds of a change of a record, which read the values before it. */
    public static final Set<String> CHANGES = Set.of(UPDATED, SAVED, TRANSITION);

    public AutomationSpec {
        actions = List.copyOf(actions);
    }

    /**
     * What starts an automation.
     *
     * @param kind     {@code created}, {@code updated}, {@code saved}, {@code transition}, {@code deleted} or
     *                 {@code scheduled}
     * @param fields   updated or saved: only a change of one of these fields, empty for any change
     * @param from     transition: only from one of these statuses, empty for any
     * @param to       transition: only to one of these statuses, empty for any
     * @param schedule scheduled: when
     */
    public record Trigger(
            String kind,
            Set<String> fields,
            Set<String> from,
            Set<String> to,
            @Nullable CalendarSchedule schedule) {
        public Trigger {
            fields = Set.copyOf(fields);
            from = Set.copyOf(from);
            to = Set.copyOf(to);
        }
    }

    /** A field an action writes and the formula of its value. */
    public record Assign(String field, String type, Formula formula) {}

    /** One action of an automation. */
    public sealed interface Step permits SetFields, CreateRecord, RunTransition, Notify {
        /** The type as the API names it. */
        String type();
    }

    /** Writes fields of the record. */
    public record SetFields(List<Assign> values) implements Step {
        public SetFields {
            values = List.copyOf(values);
        }

        @Override
        public String type() {
            return "set_field";
        }
    }

    /** Creates a record of the live form {@code form}. */
    public record CreateRecord(String form, List<Assign> values) implements Step {
        public CreateRecord {
            values = List.copyOf(values);
        }

        @Override
        public String type() {
            return "create_record";
        }
    }

    /** Runs the transition {@code code} of the record's statuses, with a comment template or none. */
    public record RunTransition(String code, @Nullable String comment) implements Step {
        @Override
        public String type() {
            return "transition";
        }
    }

    /**
     * Notifies people.
     *
     * @param channels {@code in_app}, {@code email}, {@code telegram}
     * @param fields   the user fields of the record whose users are told
     * @param author   the record's author is told
     * @param users    fixed users
     * @param rights   the holders of these rights are told
     */
    public record Notify(
            List<String> channels,
            List<String> fields,
            boolean author,
            List<Long> users,
            List<Right> rights,
            Texts title,
            Texts body)
            implements Step {
        public Notify {
            channels = List.copyOf(channels);
            fields = List.copyOf(fields);
            users = List.copyOf(users);
            rights = List.copyOf(rights);
        }

        @Override
        public String type() {
            return "notify";
        }
    }
}
