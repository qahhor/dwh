package com.smartup24.cms.instance.ms.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.ms.notify.controller.MsNotificationController;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

class MsNotificationControllerTest {

    private final MsNotificationController controller =
            new MsNotificationController(Mockito.mock(MsNotificationService.class));

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    /** Four of these texts were stored double-encoded in the source and reached the client as mojibake. */
    @Test
    void withoutUserEveryEndpointAnswersUnauthorizedInReadableRussian() {
        SecurityContext.clear();
        List<Runnable> calls = List.of(
                () -> controller.getInbox(50, null),
                controller::getUnreadCount,
                () -> controller.markAsRead(1L),
                controller::markAllAsRead,
                controller::getPreferences,
                () -> controller.updatePreferences(List.of()));

        for (Runnable call : calls) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                assertThat(error.getMessageKey()).isEqualTo("error.notify.not_authenticated");
                assertThat(PackagedProblemMessages.russian()
                                .render(new MockHttpServletRequest(), error.getMessageKey(), error.getParams()))
                        .isEqualTo("Пользователь не авторизован");
            });
        }
    }
}
