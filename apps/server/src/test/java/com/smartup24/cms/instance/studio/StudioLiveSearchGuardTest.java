package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Entity;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Module;
import com.smartup24.cms.instance.search.service.SearchEntities;
import com.smartup24.cms.instance.studio.service.StudioEntityCompiler;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The global search takes its entities once at the start (SearchEntities), which is safe for live forms only while
 * the compiler never grants them SEARCH: a quarantined live form must not stay searchable (ADR-0038).
 */
class StudioLiveSearchGuardTest {

    @Test
    void aLiveFormIsNeverSearchableSoTheStartupSearchListHoldsNoneOfThem() {
        Labels title = new Labels("Визиты", "Tashriflar", "Visits");
        Labels label = new Labels("Комментарий", "Izoh", "Comment");
        var field = new Field("note", "text", label, false, true, 200, null);
        EntityDefinition form = new StudioEntityCompiler()
                .compile(
                        3,
                        new Declaration(
                                1,
                                "simple-form",
                                new Module("studio", title),
                                new Entity("visits", title),
                                List.of(field)))
                .entity();
        assertThat(form.capabilities()).doesNotContain(EntityCapability.SEARCH);
        assertThat(new SearchEntities(List.of(form), null).codes()).isEmpty();
    }
}
