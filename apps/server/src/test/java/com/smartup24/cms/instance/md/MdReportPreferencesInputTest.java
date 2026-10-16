package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdReportDashboardDtos.DashboardWidgetRequest;
import com.smartup24.cms.instance.md.service.MdReportPreferencesInput;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class MdReportPreferencesInputTest {
    private final JsonMapper json = JsonMapper.shared();
    private final MdReportPreferencesInput input = new MdReportPreferencesInput(json);

    @Test
    void aValidLayoutAndItsOptionalFieldsRoundTrip() throws Exception {
        var parsed = input.dashboard(request("""
                {"name":"  Panel  ","widgets":[{"viewId":1,"width":2,"periodField":"createdAt"},{"viewId":2,"width":1}]}
                """));
        assertThat(parsed.name()).isEqualTo("Panel");
        assertThat(parsed.widgets())
                .containsExactly(new DashboardWidgetRequest(1, "createdAt", 2), new DashboardWidgetRequest(2, null, 1));
        String encoded = json.writeValueAsString(parsed);
        assertThat(encoded).doesNotContain("\"periodField\":null");
        assertThat(input.dashboard(request(encoded))).isEqualTo(parsed);
    }

    @Test
    void emptyLayoutsAndEmptySharingAreExplicitlyValid() throws Exception {
        assertThat(input.dashboard(request("{\"name\":\"Empty\",\"widgets\":[]}"))
                        .widgets())
                .isEmpty();
        assertThat(input.sharing(request("{\"roleIds\":[]}")).roleIds()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidLayouts")
    void invalidLayoutsAreRejectedWithoutEchoingArbitraryPropertyNames(String body) {
        assertThatThrownBy(() -> input.dashboard(request(body))).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(error.getFieldErrors())
                    .allSatisfy(field -> assertThat(field.field()).doesNotContain("hostile"));
        });
    }

    static Stream<String> invalidLayouts() {
        return Stream.of(
                "null",
                "[]",
                "{}",
                "{\"name\":null,\"widgets\":[]}",
                "{\"name\":\"X\",\"widgets\":[],\"hostile\":1}",
                "{\"name\":\"A\",\"name\":\"B\",\"widgets\":[]}",
                "{\"name\":\"X\",\"widgets\":[]} {}",
                "{\"name\":\"X\",\"widgets\":[{\"viewId\":1,\"width\":0}]}",
                "{\"name\":\"X\",\"widgets\":[{\"viewId\":1,\"width\":3}]}",
                "{\"name\":\"X\",\"widgets\":[{\"viewId\":\"1\",\"width\":1}]}",
                "{\"name\":\"X\",\"widgets\":[{\"viewId\":1.0,\"width\":1}]}",
                "{\"name\":\"X\",\"widgets\":[{\"viewId\":1,\"width\":1,\"periodField\":null}]}",
                "{\"name\":\"X\",\"widgets\":[{\"viewId\":1,\"width\":1,\"periodField\":\"x;drop\"}]}",
                "{\"name\":\"X\",\"widgets\":[{\"viewId\":1,\"width\":1},{\"viewId\":1,\"width\":2}]}",
                "{\"name\":\"X\",\"widgets\":[{\"viewId\":9007199254740992,\"width\":1}]}",
                "{\"name\":\"X\\u202E\",\"widgets\":[]}",
                "{\"name\":\"X\\u0000\",\"widgets\":[]}");
    }

    @ParameterizedTest
    @MethodSource("invalidRoles")
    void sharingIsClosedAndRequiresDistinctPositiveIntegerIds(String body) {
        assertThatThrownBy(() -> input.sharing(request(body))).isInstanceOf(ApiException.class);
    }

    static Stream<String> invalidRoles() {
        return Stream.of(
                "{}",
                "{\"roleIds\":null}",
                "{\"roleIds\":[0]}",
                "{\"roleIds\":[-1]}",
                "{\"roleIds\":[1,1]}",
                "{\"roleIds\":[\"1\"]}",
                "{\"roleIds\":[1.2]}",
                "{\"roleIds\":[],\"roleIds\":[1]}",
                "{\"roleIds\":[],\"hostile\":true}");
    }

    @Test
    void limitsApplyBeforeDeepParsingAndCountUnicodeCodePoints() throws Exception {
        var deep = request("{\"name\":\"X\",\"widgets\":" + "[".repeat(10) + "0" + "]".repeat(10) + "}");
        assertThatThrownBy(() -> input.dashboard(deep)).isInstanceOf(ApiException.class);
        var large = request(" ".repeat(MdReportPreferencesInput.MAX_BODY_BYTES + 1));
        assertThatThrownBy(() -> input.dashboard(large))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        assertThat(input.dashboard(request(json.writeValueAsString(
                                java.util.Map.of("name", "😀".repeat(80), "widgets", List.of()))))
                        .name())
                .hasSize(160);
        assertThatThrownBy(() -> input.dashboard(request(
                        json.writeValueAsString(java.util.Map.of("name", "😀".repeat(81), "widgets", List.of())))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void invalidUtf8IsNotReplacedSilently() {
        var request = new MockHttpServletRequest();
        request.setContent(new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThatThrownBy(() -> input.dashboard(request)).isInstanceOf(ApiException.class);
    }

    private static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
