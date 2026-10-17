package com.smartup24.cms.instance.studio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityEnums;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.studio.api.StudioListDtos;
import com.smartup24.cms.instance.studio.repository.StudioListRepository;
import com.smartup24.cms.instance.studio.repository.StudioListRepository.ListRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The limits and refusals of the reference lists (ADR-0041) that the HTTP tests do not reach. */
class StudioListServiceTest {
    private static final Labels NAME = new Labels("Типы", "Turlar", "Kinds");

    private final StudioListRepository lists = mock(StudioListRepository.class);
    private final EntityEnums enums = mock(EntityEnums.class);
    private final StudioListService service = new StudioListService(lists, mock(AuditLogService.class), enums);

    @BeforeEach
    void signIn() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "lists", null, null, false, Set.of("studio.lists.manage"), 1L, false, 1L, null));
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void theNumberOfListsAndOfItemsIsBounded() {
        when(lists.find("kinds")).thenReturn(Optional.empty());
        when(lists.count()).thenReturn((long) StudioListService.MAX_LISTS);
        assertThatThrownBy(() -> service.create(new StudioListDtos.CreateList("kinds", NAME)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.studio.list_quota"));

        ListRow full = new ListRow(7, "full", NAME, 1, Instant.now(), Instant.now(), StudioListDtos.MAX_ITEMS, 1);
        when(lists.find("full")).thenReturn(Optional.of(full));
        when(lists.item(anyLong(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addItem("full", new StudioListDtos.CreateItem("x", NAME, 1)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.studio.list_item_quota"));
        verify(lists, never()).insertItem(anyLong(), any(), anyLong());
    }

    @Test
    void namesAreCheckedInEveryLanguageAndUnknownCodesAreNotRepeated() {
        assertThatThrownBy(() -> service.create(new StudioListDtos.CreateList("kinds", new Labels(" a", "b", ""))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getFieldErrors())
                                .extracting(error -> error.field())
                                .containsExactly("name.ru", "name.en"));
        assertThatThrownBy(() -> service.get("Bad Code"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.studio.list_not_found"));
        when(lists.find("kinds"))
                .thenReturn(Optional.of(new ListRow(1, "kinds", NAME, 1, Instant.now(), Instant.now(), 0, 0)));
        assertThatThrownBy(() ->
                        service.updateItem("kinds", "Bad Item", "\"1\"", new StudioListDtos.UpdateItem(NAME, 1, true)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.studio.list_item_not_found"));
        assertThat(service.exists("Bad Code")).isFalse();
        assertThat(service.exists("kinds")).isTrue();
        when(lists.items(1)).thenReturn(List.of());
        assertThat(service.get("kinds").items()).isEmpty();
    }
}
