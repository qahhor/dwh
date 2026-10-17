package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.common.entity.EntityEnumSource;
import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.studio.api.StudioListDtos;
import com.smartup24.cms.instance.studio.repository.StudioListRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The reference lists as enumerations (ADR-0041): a live form field that refers to a list is an enumeration of the
 * reference {@code studio.list.<code>}, so the runtime checks its value, offers and names its items, filters and
 * exports it like every enumeration (ADR-0032, 4.5). An inactive item is archived: still named, no longer offered.
 */
@Component
public class StudioListEnums implements EntityEnumSource {
    /** The reference of a list: the prefix and the list's code. */
    public static final String PREFIX = "studio.list.";

    private static final Pattern REFERENCE = Pattern.compile(Pattern.quote(PREFIX) + StudioListDtos.CODE.substring(1));

    private final StudioListRepository lists;

    public StudioListEnums(StudioListRepository lists) {
        this.lists = lists;
    }

    /** The enumeration reference of the list {@code code}. */
    public static String reference(String code) {
        return PREFIX + code;
    }

    @Override
    public boolean owns(String reference) {
        return REFERENCE.matcher(reference).matches();
    }

    @Override
    public EntityEnums.Items items(String reference, String language) {
        if (!owns(reference)) return EntityEnums.Items.NONE;
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> inactive = new LinkedHashSet<>();
        for (StudioListDtos.Item item : lists.itemsOf(reference.substring(PREFIX.length()))) {
            names.put(item.code(), name(item.name(), language));
            if (!item.active()) inactive.add(item.code());
        }
        return new EntityEnums.Items(names, inactive);
    }

    private static String name(Labels name, String language) {
        return switch (language) {
            case "uz" -> name.uz();
            case "en" -> name.en();
            default -> name.ru();
        };
    }
}
