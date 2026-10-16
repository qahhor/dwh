package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.controller.MdFormStudioController;
import com.smartup24.cms.instance.md.service.MdFormStudioService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MdFormStudioBodyTest {
    @ParameterizedTest
    @CsvSource({"create,32768", "update,32768", "verify,65536"})
    void chunkedBodiesAreBoundedBeforeServiceAndParsing(String operation, int limit) throws Exception {
        var service = mock(MdFormStudioService.class);
        var controller = new MdFormStudioController(service);
        var request = mock(HttpServletRequest.class);
        var stream = new EndlessBody();
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getInputStream()).thenReturn(stream);
        assertThatThrownBy(() -> {
                    switch (operation) {
                        case "create" -> controller.create("example.products", request);
                        case "update" -> controller.update("example.products", "1", request);
                        default -> controller.verify("example.products", 1, request);
                    }
                })
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        assertThat(stream.count).isEqualTo(limit + 1);
        verifyNoInteractions(service);
    }

    private static class EndlessBody extends ServletInputStream {
        private int count;

        @Override
        public int read() {
            count++;
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
