package com.smartup24.cms.instance.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class FormStudioBodyTest {
    @Test
    void chunkedBodyIsBoundedBeforeParsingOrCallingTheService() throws Exception {
        FormStudioService service = mock(FormStudioService.class);
        var controller = new FormStudioController(service);
        var request = mock(HttpServletRequest.class);
        var stream = new NeverEndingBody();
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getInputStream()).thenReturn(stream);
        assertThatThrownBy(() -> controller.preview(request))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        assertThat(stream.read).isEqualTo(FormLayoutDeclarations.MAX_BODY_BYTES + 1);
        verifyNoInteractions(service);
    }

    private static class NeverEndingBody extends ServletInputStream {
        private int read;

        @Override
        public int read() {
            read++;
            return ' ';
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {}
    }
}
