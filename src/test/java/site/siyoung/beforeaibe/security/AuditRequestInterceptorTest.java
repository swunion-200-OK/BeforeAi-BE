package site.siyoung.beforeaibe.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerMapping;
import site.siyoung.beforeaibe.service.AuditWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AuditRequestInterceptorTest {

    private final AuditWriter auditWriter = mock(AuditWriter.class);
    private final NetworkResolver networkResolver = mock(NetworkResolver.class);
    private final AuditRequestInterceptor interceptor =
            new AuditRequestInterceptor(auditWriter, networkResolver);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(interceptor, "enabled", true);
    }

    private void complete(String method, int status, Exception ex) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/requests");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/requests");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        interceptor.afterCompletion(request, response, new Object(), ex);
    }

    private String recordedAction() {
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(auditWriter).record(any(), action.capture(), any(), any(), any(), any());
        return action.getValue();
    }

    @Test
    void 성공한_GET은_기록하지_않는다() {
        complete("GET", 200, null);

        verifyNoInteractions(auditWriter);
    }

    @Test
    void 성공한_HEAD는_기록하지_않는다() {
        complete("HEAD", 200, null);

        verifyNoInteractions(auditWriter);
    }

    @Test
    void 실패한_GET은_FAILED로_기록한다() {
        complete("GET", 403, null);

        assertThat(recordedAction()).isEqualTo("GET /api/v1/requests_FAILED");
    }

    @Test
    void 처리되지_않은_예외로_끝난_GET은_기록한다() {
        complete("GET", 200, new IllegalStateException("실패"));

        assertThat(recordedAction()).isEqualTo("GET /api/v1/requests");
    }

    @Test
    void 성공한_변경_요청은_기록한다() {
        complete("POST", 201, null);

        assertThat(recordedAction()).isEqualTo("POST /api/v1/requests");
    }

    @Test
    void 실패한_변경_요청은_FAILED로_기록한다() {
        complete("POST", 409, null);

        assertThat(recordedAction()).isEqualTo("POST /api/v1/requests_FAILED");
    }
}
