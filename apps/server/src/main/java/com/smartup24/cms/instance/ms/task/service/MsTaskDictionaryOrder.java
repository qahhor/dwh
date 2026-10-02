package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.common.entity.hook.EntityActionCall;
import com.smartup24.cms.instance.common.entity.hook.EntityActionHandler;
import com.smartup24.cms.instance.ms.task.repository.MsTaskDictionaryRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskDictionaryRepository.Dictionary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The order of the task types and statuses as a record action (ADR-0032, 6.7): {@code POST
 * /api/v1/entities/{code}/{id}/actions/move {"position": 2}} with If-Match puts the item at that place, counted from 1
 * over the items in use, as a screen lists them; the other items move apart by
 * {@link MsTaskDictionaryRepository#STEP}. The item itself
 * is written by the runtime with its revision, audit and event; the others get their places and new revisions.
 */
@Configuration
public class MsTaskDictionaryOrder {

    /** The action's code. */
    public static final String MOVE = "move";

    /** The parameter: the place counted from 1. */
    public static final String POSITION = "position";

    @Bean
    public EntityActionHandler msTaskTypeMove(MsTaskDictionaryRepository dictionaries) {
        return handler(MsTaskTypeEntity.CODE, Dictionary.TYPES, dictionaries);
    }

    @Bean
    public EntityActionHandler msTaskStatusMove(MsTaskDictionaryRepository dictionaries) {
        return handler(MsTaskStatusEntity.CODE, Dictionary.STATUSES, dictionaries);
    }

    private static EntityActionHandler handler(
            String entity, Dictionary dictionary, MsTaskDictionaryRepository dictionaries) {
        return new EntityActionHandler() {
            @Override
            public String entity() {
                return entity;
            }

            @Override
            public String action() {
                return MOVE;
            }

            @Override
            public void run(EntityActionCall call) {
                long id = Objects.requireNonNull(call.id());
                List<Long> order = new ArrayList<>(dictionaries.orderedIds(dictionary, id));
                Integer position = position(call.params().get(POSITION), order.size());
                if (position == null) {
                    call.reject("params." + POSITION, "out_of_range", "error.field.number_out_of_range", Map.of());
                    return;
                }
                order.remove(Long.valueOf(id));
                order.add(position - 1, id);
                Map<Long, Integer> others = new LinkedHashMap<>();
                for (int index = 0; index < order.size(); index++) {
                    others.put(order.get(index), (index + 1) * MsTaskDictionaryRepository.STEP);
                }
                Integer own = others.remove(id);
                dictionaries.place(dictionary, others, call.actor().userId());
                call.values().set("sortOrder", own);
            }
        };
    }

    /** The place the request names, if it is a whole number from 1 to the number of items. */
    static @Nullable Integer position(@Nullable Object value, int items) {
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
            return null;
        }
        long place = number.longValue();
        return place >= 1 && place <= items ? (int) place : null;
    }
}
