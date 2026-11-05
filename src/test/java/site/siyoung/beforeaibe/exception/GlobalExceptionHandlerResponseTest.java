package site.siyoung.beforeaibe.exception;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// SSE·PDF 처리 중 난 예외: 이전에는 HttpMessageNotWritableException(No converter for ErrorResponse)으로 실패함
class GlobalExceptionHandlerResponseTest {

    @RestController
    static class StreamingController {

        @GetMapping("/sse-error")
        void sseError(HttpServletResponse response) {
            response.setContentType("text/event-stream");
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }

        @GetMapping("/pdf-error")
        void pdfError(HttpServletResponse response) {
            response.setContentType("application/pdf");
            throw new IllegalStateException("렌더링 실패");
        }

        @GetMapping("/committed-error")
        void committedError(HttpServletResponse response) throws IOException {
            response.setContentType("application/pdf");
            response.getOutputStream().write(1);
            response.flushBuffer();
            throw new IOException("Broken pipe");
        }

        @GetMapping("/client-gone")
        void clientGone(HttpServletResponse response) throws AsyncRequestNotUsableException {
            response.setContentType("text/event-stream");
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to flush");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new StreamingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void SSE_응답으로_지정된_뒤_난_예외도_JSON_오류로_응답한다() throws Exception {
        mockMvc.perform(get("/sse-error"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void PDF_응답으로_지정된_뒤_난_예외도_JSON_오류로_응답한다() throws Exception {
        mockMvc.perform(get("/pdf-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }

    @Test
    void 이미_보낸_응답에는_오류_본문을_덧붙이지_않는다() throws Exception {
        MvcResult result = mockMvc.perform(get("/committed-error")).andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).containsExactly(1);
    }

    @Test
    void 클라이언트가_끊은_SSE에는_오류_응답을_쓰지_않는다() throws Exception {
        MvcResult result = mockMvc.perform(get("/client-gone")).andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(result.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class);
    }
}
