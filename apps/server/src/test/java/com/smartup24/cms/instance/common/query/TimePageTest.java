package com.smartup24.cms.instance.common.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.pagination.CursorUtils;
import com.smartup24.cms.instance.common.error.ApiException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Plan 10/10, item 3.5: pages of collections outside the field registry. */
class TimePageTest {

    private static final Instant AT = Instant.parse("2026-09-30T10:15:30.123456Z");

    @Test
    @DisplayName("3.5: the limit defaults, and outside 1..max it is 422 naming the field")
    void limitIsChecked() {
        assertThat(TimePage.of(null, null, 50, 100).limit()).isEqualTo(50);
        assertThat(TimePage.of(100, "", 50, 100).after()).isNull();
        for (int bad : new int[] {0, 101}) {
            assertThatThrownBy(() -> TimePage.of(bad, null, 50, 100))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.getMessageKey()).isEqualTo("error.common.query_limit_invalid");
                        assertThat(error.getFieldErrors()).extracting("field").containsExactly("limit");
                    });
        }
    }

    @Test
    @DisplayName("3.5: the cursor of a page's last row brings back its time to the microsecond and its id")
    void cursorRoundTrips() {
        TimePage first = TimePage.of(2, null, 50, 100);
        var page = first.page(List.of(1L, 2L, 3L), id -> new TimePage.Position(AT, id));

        assertThat(page.items()).containsExactly(1L, 2L);
        assertThat(page.hasMore()).isTrue();
        assertThat(TimePage.of(2, page.nextCursor(), 50, 100).after()).isEqualTo(new TimePage.Position(AT, 2L));

        var last = first.page(List.of(4L), id -> new TimePage.Position(AT, id));
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    @DisplayName("3.5: a cursor that is not one of ours is 422 naming the field")
    void foreignCursorIsRefused() {
        for (String bad : new String[] {
            "not-a-cursor", CursorUtils.encode("2026-09-30T10:15:30Z|x"), CursorUtils.encode("yesterday|1"), "fA"
        }) {
            assertThatThrownBy(() -> TimePage.of(10, bad, 50, 100))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            error -> assertThat(error.getMessageKey()).isEqualTo("error.common.query_cursor_invalid"));
        }
    }
}
