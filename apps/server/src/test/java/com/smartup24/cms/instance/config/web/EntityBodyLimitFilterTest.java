package com.smartup24.cms.instance.config.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.config.error.ProblemMessages;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0032, 6.2 and 12 ("denial of service"): a change under {@code /api/v1/entities/} carries at most 512 KB; a
 * larger body is refused with 413 before it is parsed, whether it states its length or not, and a smaller one reaches
 * the handler whole.
 */
class EntityBodyLimitFilterTest {

    private final EntityBodyLimitFilter filter = new EntityBodyLimitFilter(
            JsonMapper.shared(), new StaticListableBeanFactory().getBeanProvider(ProblemMessages.class));

    @Test
    @DisplayName("6.2: an entity body over 512 KB is 413, stated or chunked; a smaller one reaches the handler")
    void largeBodiesAreRefused() throws Exception {
        assertThat(send("POST", "/api/v1/entities/ms.notes", EntityBodyLimitFilter.MAX_BODY_BYTES + 1, true))
                .isEqualTo(413);
        assertThat(send("PATCH", "/api/v1/entities/ms.notes/1", EntityBodyLimitFilter.MAX_BODY_BYTES + 1, false))
                .isEqualTo(413);
        assertThat(send("POST", "/api/v1/entities/ms.notes", 1024, true)).isEqualTo(200);
        assertThat(send("POST", "/api/v1/tasks", EntityBodyLimitFilter.MAX_BODY_BYTES + 1, true))
                .as("another path keeps its own limits")
                .isEqualTo(200);
    }

    private int send(String method, String path, int size, boolean statesLength) throws Exception {
        MockHttpServletRequest request = statesLength
                ? new MockHttpServletRequest(method, path)
                : new MockHttpServletRequest(method, path) {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }

                    @Override
                    public int getContentLength() {
                        return -1;
                    }
                };
        request.setContent("x".repeat(size).getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Integer> read = new AtomicReference<>();
        filter.doFilter(
                request, response, (req, res) -> read.set(req.getInputStream().readAllBytes().length));
        if (response.getStatus() == 413) {
            assertThat(response.getContentAsString()).contains("payload_too_large");
            return 413;
        }
        assertThat(read.get()).isEqualTo(size);
        return 200;
    }
}
