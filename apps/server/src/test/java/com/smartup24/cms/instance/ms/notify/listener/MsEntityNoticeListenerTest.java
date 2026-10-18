package com.smartup24.cms.instance.ms.notify.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityNotice;
import com.smartup24.cms.instance.md.service.MdUserTexts;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The notice of a document's step becomes an in-app notification in each recipient's language (ADR-0043). */
class MsEntityNoticeListenerTest {

    @Test
    void eachRecipientButTheActorAndOneWhoSwitchedItOffIsToldInTheirLanguage() {
        MsNotificationService notifications = mock(MsNotificationService.class);
        MdUserTexts texts = mock(MdUserTexts.class);
        when(notifications.isNotificationEnabled(anyLong(), eq("entity_step"), eq("in_app")))
                .thenReturn(true);
        when(notifications.isNotificationEnabled(eq(4L), eq("entity_step"), eq("in_app")))
                .thenReturn(false);
        when(texts.language(2L)).thenReturn("uz");
        when(texts.language(3L)).thenReturn("de");
        when(texts.text(eq(2L), eq("title"), eq(Map.of("form", "Buyurtma")))).thenReturn("Sarlavha");
        when(texts.text(eq(3L), eq("title"), eq(Map.of("form", "Заказ")))).thenReturn("Заголовок");
        when(texts.text(anyLong(), eq("body"), any())).thenReturn("Текст");
        EntityNotice notice = new EntityNotice(
                "studio.orders",
                7,
                List.of(1L, 2L, 3L, 4L),
                1,
                "title",
                "body",
                Map.of("ru", Map.of("form", "Заказ"), "uz", Map.of("form", "Buyurtma")),
                "/e/studio.orders/7",
                "studio-step-studio.orders-7-pending");
        new MsEntityNoticeListener(notifications, texts).onNotice(notice);
        verify(notifications)
                .sendInAppNotification(
                        2L,
                        MsNotifyPref.TYPE_INFO,
                        "Sarlavha",
                        "Текст",
                        "/e/studio.orders/7",
                        "studio-step-studio.orders-7-pending");
        verify(notifications)
                .sendInAppNotification(eq(3L), eq(MsNotifyPref.TYPE_INFO), eq("Заголовок"), eq("Текст"), any(), any());
        verify(notifications, never()).sendInAppNotification(eq(1L), any(), any(), any(), any(), any());
        verify(notifications, never()).sendInAppNotification(eq(4L), any(), any(), any(), any(), any());
    }
}
