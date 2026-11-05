package site.siyoung.beforeaibe.service;

import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import site.siyoung.beforeaibe.dto.approvaldocument.ApprovalDocumentResponse;
import site.siyoung.beforeaibe.dto.approvaldocument.ApprovalSlotResponse;
import site.siyoung.beforeaibe.dto.approvaldocument.StampResponse;
import site.siyoung.beforeaibe.dto.approvaldocument.TaskResultResponse;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;
import site.siyoung.beforeaibe.support.ApprovalDocumentPdfRenderer;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Spring 없이 PDF를 만들고 다시 읽어 한글 폰트가 실제로 내장됐는지(텍스트로 추출되는지) 확인함
class ApprovalDocumentPdfRendererTest {

    private static final String PLAN_HASH = "sha256:4f1c2b9e0a";
    private static final Instant NOW = Instant.parse("2026-11-05T05:30:00Z");

    private final ApprovalDocumentPdfRenderer renderer = new ApprovalDocumentPdfRenderer();

    private ApprovalSlotResponse slot(int order, String title, Risk risk, List<StampResponse> stamps) {
        return new ApprovalSlotResponse(UUID.randomUUID(), order, title, TaskType.CODE_FEATURE, risk,
                "src/auth/login.ts", List.of(new RequiredApprover(ApproverRule.DEPT_MANAGER, 2, null)), 2, stamps);
    }

    private ApprovalDocumentResponse document(ApprovalDocumentResponse.Status status, List<ApprovalSlotResponse> slots,
                                              List<TaskResultResponse> results) {
        return new ApprovalDocumentResponse(UUID.randomUUID(), UUID.randomUUID(), NOW, 1, 1, status, "인증 서비스",
                "김기안", "개발팀", NOW, "로그인 실패 시 에러 메시지를 바꿔줘", "git@internal:auth-service.git",
                "9b2f4e1", PLAN_HASH, slots, results);
    }

    private String text(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page));
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    @Test
    void 결재_완료_문서에는_한글_내용과_도장_승인자와_Plan_해시가_담긴다() throws IOException {
        StampResponse stamp = new StampResponse(UUID.randomUUID(), "박부장", "운영팀", NOW);
        byte[] pdf = renderer.render(document(ApprovalDocumentResponse.Status.APPROVED,
                List.of(slot(1, "로그인 실패 메시지 변경", Risk.MEDIUM, List.of(stamp, stamp))), List.of()));

        String text = text(pdf);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(text).contains("결재란", "로그인 실패 메시지 변경", "박부장", "김기안", "결재 완료", PLAN_HASH);
        assertThat(text).doesNotContain("미결", "실행 결과");
    }

    @Test
    void 승인이_남은_자리는_미결로_표시하고_금지_작업은_결재_대상이_아니라고_표시한다() throws IOException {
        byte[] pdf = renderer.render(document(ApprovalDocumentResponse.Status.IN_PROGRESS,
                List.of(slot(1, "로그인 실패 메시지 변경", Risk.MEDIUM, List.of()),
                        slot(2, "운영 DB 테이블 삭제", Risk.FORBIDDEN, List.of())), List.of()));

        assertThat(text(pdf)).contains("미결", "운영 DB 테이블 삭제", "결재·실행 대상이 아닙니다", "결재 진행 중");
    }

    @Test
    void 실행_결과가_있으면_작업별_최종_상태를_담는다() throws IOException {
        StampResponse stamp = new StampResponse(UUID.randomUUID(), "박부장", "운영팀", NOW);
        byte[] pdf = renderer.render(document(ApprovalDocumentResponse.Status.APPROVED,
                List.of(slot(1, "로그인 실패 메시지 변경", Risk.MEDIUM, List.of(stamp, stamp))),
                List.of(new TaskResultResponse(UUID.randomUUID(), 1, "로그인 실패 메시지 변경", TaskStatus.SUCCEEDED),
                        new TaskResultResponse(UUID.randomUUID(), 2, "스테이징 배포", TaskStatus.FAILED))));

        assertThat(text(pdf)).contains("실행 결과", "성공", "실패", "스테이징 배포");
    }
}
