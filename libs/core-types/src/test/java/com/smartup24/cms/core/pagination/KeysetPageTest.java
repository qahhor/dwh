package com.smartup24.cms.core.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Plan 10/10, item 3.5: a page turned into the wire type keeps what it says about its total. */
class KeysetPageTest {

    @Test
    @DisplayName("3.5: map keeps the cursor, the total and whether the total is a count")
    void mapKeepsTheTotalAndItsExactness() {
        KeysetPage<Integer> estimated = KeysetPage.estimated(List.of(1, 2), "next", true, 1_000_000);

        KeysetPage<String> mapped = estimated.map(i -> "#" + i);

        assertThat(mapped.items()).containsExactly("#1", "#2");
        assertThat(mapped.nextCursor()).isEqualTo("next");
        assertThat(mapped.hasMore()).isTrue();
        assertThat(mapped.totalEstimated()).isEqualTo(1_000_000);
        assertThat(mapped.totalExact()).isFalse();
        assertThat(KeysetPage.of(List.of(1), null, false, 1)
                        .map(String::valueOf)
                        .totalExact())
                .isTrue();
    }
}
