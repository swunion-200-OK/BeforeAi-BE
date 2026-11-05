package site.siyoung.beforeaibe.support;

import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPCellEvent;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import site.siyoung.beforeaibe.dto.approvaldocument.ApprovalDocumentResponse;
import site.siyoung.beforeaibe.dto.approvaldocument.ApprovalSlotResponse;
import site.siyoung.beforeaibe.dto.approvaldocument.StampResponse;
import site.siyoung.beforeaibe.dto.approvaldocument.TaskResultResponse;
import site.siyoung.beforeaibe.entity.enums.ApproverRule;
import site.siyoung.beforeaibe.entity.enums.Risk;
import site.siyoung.beforeaibe.entity.enums.TaskStatus;
import site.siyoung.beforeaibe.entity.enums.TaskType;
import site.siyoung.beforeaibe.entity.value.RequiredApprover;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

// 결재 문서 내용(ApprovalDocumentResponse)을 PDF로 그리기만 함. DB·권한·발급 조건은 서비스가 맡음.
// PDF 기본 폰트에는 한글이 없어 Noto Sans KR(OFL, 한글·기본 기호만 남긴 서브셋)을 내장함
@Component
public class ApprovalDocumentPdfRenderer {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZONE);
    private static final DateTimeFormatter STAMP_DATE = DateTimeFormatter.ofPattern("yy.MM.dd").withZone(ZONE);
    private static final DateTimeFormatter STAMP_TIME = DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(ZONE);
    private static final DateTimeFormatter NUMBER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZONE);

    private static final Color INK = new Color(0x1F, 0x29, 0x37);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color LINE = new Color(0xD1, 0xD5, 0xDB);
    private static final Color HEAD_BG = new Color(0xF3, 0xF4, 0xF6);
    private static final Color ACCENT = new Color(0x1E, 0x3A, 0x8A);
    private static final Color STAMP_RED = new Color(0xC6, 0x28, 0x28);
    private static final Color SUCCESS = new Color(0x15, 0x80, 0x3D);
    private static final Color FORBIDDEN_BG = new Color(0xFD, 0xEC, 0xEC);
    private static final Color WATERMARK = new Color(0xEE, 0xEE, 0xEE);

    // 한 줄에 놓을 도장 자리 수. 넘으면 다음 줄로 이어짐
    private static final int STAMPS_PER_ROW = 4;
    private static final float STAMP_BOX_HEIGHT = 84f;

    private final BaseFont regular;
    private final BaseFont bold;

    public ApprovalDocumentPdfRenderer() {
        this.regular = loadFont("fonts/NotoSansKR-Regular.ttf");
        this.bold = loadFont("fonts/NotoSansKR-Bold.ttf");
    }

    public byte[] render(ApprovalDocumentResponse doc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 40, 40, 48, 60);
        try {
            PdfWriter writer = PdfWriter.getInstance(pdf, out);
            writer.setPageEvent(new PageDecoration(doc));
            pdf.addTitle("결재 문서 " + documentNumber(doc) + " 제" + doc.version() + "판");
            pdf.addCreator("BeforeAI");
            pdf.open();
            addHeader(pdf, doc);
            addInfo(pdf, doc);
            addPrompt(pdf, doc);
            addSlots(pdf, doc);
            if (!doc.results().isEmpty()) {
                addResults(pdf, doc);
            }
            addHash(pdf, doc);
            pdf.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("결재 문서 PDF를 만들 수 없습니다", e);
        }
        return out.toByteArray();
    }

    private void addHeader(Document pdf, ApprovalDocumentResponse doc) {
        Paragraph title = new Paragraph("결 재 문 서", font(bold, 22, INK));
        title.setAlignment(Element.ALIGN_CENTER);
        pdf.add(title);

        Paragraph subtitle = new Paragraph("BeforeAI · AI 작업 계획 승인 결재", font(regular, 9, MUTED));
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(6);
        pdf.add(subtitle);

        Phrase status = new Phrase();
        status.add(new Chunk("제 " + doc.version() + " 판  ·  ", font(bold, 10, INK)));
        status.add(new Chunk(statusLabel(doc.status()), font(bold, 10, statusColor(doc.status()))));
        if (doc.status() == ApprovalDocumentResponse.Status.SUPERSEDED) {
            status.add(new Chunk("  (최신은 제 " + doc.currentVersion() + " 판)", font(regular, 9, MUTED)));
        }
        if (doc.status() == ApprovalDocumentResponse.Status.REVISING) {
            status.add(new Chunk("  (다시 제출되면 제 " + (doc.version() + 1) + " 판이 됩니다)", font(regular, 9, MUTED)));
        }
        Paragraph statusLine = new Paragraph(status);
        statusLine.setAlignment(Element.ALIGN_CENTER);
        statusLine.setSpacingAfter(14);
        pdf.add(statusLine);
    }

    private void addInfo(Document pdf, ApprovalDocumentResponse doc) {
        PdfPTable table = table(new float[]{1.1f, 3f, 1.1f, 3f});
        labelCell(table, "문서번호");
        valueCell(table, documentNumber(doc));
        labelCell(table, "판");
        valueCell(table, "제 " + doc.version() + " 판 / 전체 " + doc.currentVersion() + " 판");
        labelCell(table, "기안자");
        valueCell(table, withDepartment(doc.requesterName(), doc.requesterDepartmentName()));
        labelCell(table, "기안일");
        valueCell(table, DATE_TIME.format(doc.draftedAt()));
        labelCell(table, "프로젝트");
        valueCell(table, doc.projectName());
        labelCell(table, "저장소");
        valueCell(table, doc.repository() + " @ " + doc.baseCommit());
        pdf.add(table);
    }

    private void addPrompt(Document pdf, ApprovalDocumentResponse doc) {
        pdf.add(sectionTitle("요청 내용"));
        PdfPTable table = table(new float[]{1f});
        PdfPCell cell = new PdfPCell(new Phrase(doc.prompt(), font(regular, 10, INK)));
        cell.setPadding(10);
        cell.setBorderColor(LINE);
        cell.setLeading(0, 1.4f);
        table.addCell(cell);
        pdf.add(table);
    }

    private void addSlots(Document pdf, ApprovalDocumentResponse doc) {
        pdf.add(sectionTitle("결재란"));
        Paragraph note = new Paragraph("작업마다 칸이 하나이며, 칸 안의 도장 자리 수는 그 작업에 필요한 승인 수입니다.",
                font(regular, 8.5f, MUTED));
        note.setSpacingAfter(6);
        pdf.add(note);

        for (ApprovalSlotResponse slot : doc.slots()) {
            PdfPTable row = table(new float[]{38f, 62f});
            row.setKeepTogether(true);
            row.setSpacingBefore(0);
            row.setSpacingAfter(6);
            row.addCell(slotInfoCell(slot));
            row.addCell(slot.forbidden() ? forbiddenCell() : stampsCell(slot));
            pdf.add(row);
        }
    }

    private PdfPCell slotInfoCell(ApprovalSlotResponse slot) {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk("#" + slot.order() + "  " + slot.title() + "\n", font(bold, 10, INK)));
        phrase.add(new Chunk(typeLabel(slot.type()) + " · 위험도 " + riskLabel(slot.risk()) + "\n",
                font(regular, 8.5f, MUTED)));
        phrase.add(new Chunk("대상 " + slot.target() + "\n", font(regular, 8.5f, MUTED)));
        if (!slot.forbidden()) {
            phrase.add(new Chunk("필요 승인: " + approverLabel(slot.requiredApprovers()), font(regular, 8.5f, INK)));
        }
        PdfPCell cell = new PdfPCell(phrase);
        cell.setPadding(8);
        cell.setLeading(0, 1.35f);
        cell.setBorderColor(LINE);
        cell.setBackgroundColor(HEAD_BG);
        return cell;
    }

    private PdfPCell forbiddenCell() {
        PdfPCell cell = new PdfPCell(new Phrase("금지 작업 — 결재·실행 대상이 아닙니다", font(bold, 9, STAMP_RED)));
        cell.setBorderColor(LINE);
        cell.setBackgroundColor(FORBIDDEN_BG);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    // 필요 승인 수만큼 도장 자리를 두고, 찍힌 순서대로 채움. 필요 수보다 많이 찍혔으면 자리를 늘림
    private PdfPCell stampsCell(ApprovalSlotResponse slot) {
        int boxes = Math.max(1, Math.max(slot.requiredCount(), slot.stamps().size()));
        int columns = Math.min(boxes, STAMPS_PER_ROW);
        PdfPTable stamps = new PdfPTable(columns);
        stamps.setWidthPercentage(100);
        int filled = ((boxes + columns - 1) / columns) * columns;
        for (int i = 0; i < filled; i++) {
            if (i >= boxes) {
                PdfPCell empty = new PdfPCell();
                empty.setBorder(Rectangle.NO_BORDER);
                stamps.addCell(empty);
                continue;
            }
            StampResponse stamp = i < slot.stamps().size() ? slot.stamps().get(i) : null;
            stamps.addCell(stampBox(i + 1, stamp));
        }
        PdfPCell cell = new PdfPCell(stamps);
        cell.setPadding(4);
        cell.setBorderColor(LINE);
        return cell;
    }

    // 결재란 한 자리: 위에 "승인 n" 머리칸, 아래에 도장(또는 미결)
    private PdfPCell stampBox(int index, StampResponse stamp) {
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);
        PdfPCell head = new PdfPCell(new Phrase("승인 " + index, font(bold, 8, MUTED)));
        head.setHorizontalAlignment(Element.ALIGN_CENTER);
        head.setBackgroundColor(HEAD_BG);
        head.setBorderColor(LINE);
        head.setPadding(3);
        box.addCell(head);

        PdfPCell body = new PdfPCell();
        body.setFixedHeight(STAMP_BOX_HEIGHT);
        body.setBorderColor(LINE);
        body.setCellEvent(new StampEvent(stamp));
        box.addCell(body);

        PdfPCell holder = new PdfPCell(box);
        holder.setPadding(2);
        holder.setBorder(Rectangle.NO_BORDER);
        return holder;
    }

    private void addResults(Document pdf, ApprovalDocumentResponse doc) {
        pdf.add(sectionTitle("실행 결과"));
        PdfPTable table = table(new float[]{0.7f, 5f, 1.6f});
        labelCell(table, "순서");
        labelCell(table, "작업");
        labelCell(table, "최종 상태");
        for (TaskResultResponse result : doc.results()) {
            valueCell(table, "#" + result.order());
            valueCell(table, result.title());
            PdfPCell status = new PdfPCell(new Phrase(resultLabel(result.status()),
                    font(bold, 9, resultColor(result.status()))));
            status.setPadding(6);
            status.setBorderColor(LINE);
            status.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(status);
        }
        pdf.add(table);
    }

    // 제목과 내용이 다른 쪽으로 갈라지지 않도록 제목도 표의 첫 줄로 넣고 표를 한 덩어리로 둠
    private void addHash(Document pdf, ApprovalDocumentResponse doc) {
        PdfPTable table = table(new float[]{1f});
        table.setKeepTogether(true);
        table.setSpacingBefore(14);
        PdfPCell title = new PdfPCell(new Phrase("■ 서명 대상", font(bold, 11.5f, ACCENT)));
        title.setBorder(Rectangle.NO_BORDER);
        title.setPaddingLeft(0);
        title.setPaddingBottom(8);
        table.addCell(title);
        Phrase phrase = new Phrase();
        phrase.add(new Chunk("Plan 해시 (RFC 8785 JCS + SHA-256)\n", font(bold, 8.5f, MUTED)));
        phrase.add(new Chunk((doc.planHash() == null ? "-" : doc.planHash()) + "\n", font(regular, 9, INK)));
        phrase.add(new Chunk("승인 서명은 이 PDF가 아니라 위 Plan 해시에 대해 이루어집니다. 이 문서는 확인용 사본입니다.",
                font(regular, 8, MUTED)));
        PdfPCell cell = new PdfPCell(phrase);
        cell.setPadding(10);
        cell.setLeading(0, 1.4f);
        cell.setBorderColor(LINE);
        cell.setBackgroundColor(HEAD_BG);
        table.addCell(cell);
        pdf.add(table);
    }

    private Paragraph sectionTitle(String text) {
        Paragraph paragraph = new Paragraph("■ " + text, font(bold, 11.5f, ACCENT));
        paragraph.setSpacingBefore(14);
        paragraph.setSpacingAfter(6);
        return paragraph;
    }

    private PdfPTable table(float[] widths) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        return table;
    }

    private void labelCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(bold, 9, INK)));
        cell.setPadding(6);
        cell.setBorderColor(LINE);
        cell.setBackgroundColor(HEAD_BG);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(cell);
    }

    private void valueCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "-" : text, font(regular, 9, INK)));
        cell.setPadding(6);
        cell.setBorderColor(LINE);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(cell);
    }

    private Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }

    // 문서번호: 문서 생성일 + 요청 ID 앞 8자리
    private static String documentNumber(ApprovalDocumentResponse doc) {
        return "BA-" + NUMBER_DATE.format(doc.documentCreatedAt()) + "-"
                + doc.requestId().toString().substring(0, 8).toUpperCase();
    }

    private static String withDepartment(String name, String department) {
        if (name == null) {
            return "-";
        }
        return department == null ? name : name + " (" + department + ")";
    }

    private static String approverLabel(List<RequiredApprover> approvers) {
        return approvers.stream()
                .map(approver -> ruleLabel(approver.rule()) + " " + Math.max(1, approver.count()) + "명")
                .collect(Collectors.joining(" · "));
    }

    private static String ruleLabel(ApproverRule rule) {
        return switch (rule) {
            case ANYONE -> "승인권자";
            case SAME_DEPT_OTHER -> "같은 부서 동료";
            case PROJECT_OWNER -> "프로젝트 담당자";
            case DEPT_MANAGER -> "부서장";
            case ADMIN -> "관리자";
        };
    }

    private static String typeLabel(TaskType type) {
        return switch (type) {
            case DOC -> "문서";
            case CODE_MINOR -> "코드(경미)";
            case CODE_FEATURE -> "코드(기능)";
            case DATA_READ -> "데이터 조회";
            case DATA_WRITE -> "데이터 변경";
            case DB -> "DB 변경";
            case DEPLOY -> "배포";
        };
    }

    private static String riskLabel(Risk risk) {
        return switch (risk) {
            case LOW -> "낮음";
            case MEDIUM -> "보통";
            case HIGH -> "높음";
            case FORBIDDEN -> "금지";
        };
    }

    private static String statusLabel(ApprovalDocumentResponse.Status status) {
        return switch (status) {
            case IN_PROGRESS -> "결재 진행 중";
            case APPROVED -> "결재 완료";
            case REVISING -> "Plan 수정 중";
            case SUPERSEDED -> "이전 판 (기록)";
            case REJECTED -> "반려";
            case CANCELED -> "취소";
            case KILLED -> "실행 중단";
        };
    }

    private static Color statusColor(ApprovalDocumentResponse.Status status) {
        return switch (status) {
            case APPROVED -> SUCCESS;
            case IN_PROGRESS -> ACCENT;
            case REVISING, SUPERSEDED -> MUTED;
            case REJECTED, CANCELED, KILLED -> STAMP_RED;
        };
    }

    private static String resultLabel(TaskStatus status) {
        return switch (status) {
            case PENDING -> "대기";
            case APPROVED -> "미실행";
            case REJECTED -> "거부";
            case BLOCKED -> "차단";
            case RUNNING -> "실행 중";
            case SUCCEEDED -> "성공";
            case FAILED -> "실패";
            case SKIPPED -> "건너뜀";
            case ROLLED_BACK -> "롤백됨";
        };
    }

    private static Color resultColor(TaskStatus status) {
        return switch (status) {
            case SUCCEEDED -> SUCCESS;
            case FAILED, REJECTED, BLOCKED -> STAMP_RED;
            default -> MUTED;
        };
    }

    private static BaseFont loadFont(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            return BaseFont.createFont(fileName, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, in.readAllBytes(), null);
        } catch (IOException | DocumentException e) {
            throw new IllegalStateException("결재 문서 폰트를 읽을 수 없습니다: " + path, e);
        }
    }

    // 텍스트 도장: 붉은 이중 원 안에 "승인"·이름·날짜, 원 아래에 부서·시각. 승인 전이면 "미결"
    private final class StampEvent implements PdfPCellEvent {

        private static final float RADIUS = 25f;

        private final StampResponse stamp;

        private StampEvent(StampResponse stamp) {
            this.stamp = stamp;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte canvas = canvases[PdfPTable.TEXTCANVAS];
            float cx = (position.getLeft() + position.getRight()) / 2;
            float cy = position.getBottom() + position.getHeight() / 2 + 7;
            canvas.saveState();
            if (stamp == null) {
                text(canvas, regular, 9, MUTED, "미결", cx, cy - 3);
                canvas.restoreState();
                return;
            }
            canvas.setColorStroke(STAMP_RED);
            canvas.setLineWidth(1.6f);
            canvas.circle(cx, cy, RADIUS);
            canvas.stroke();
            canvas.setLineWidth(0.6f);
            canvas.circle(cx, cy, RADIUS - 3);
            canvas.stroke();

            text(canvas, bold, 7, STAMP_RED, "승 인", cx, cy + 10);
            // 이름이 길면 원 안에 들어가도록 글자 크기를 줄임
            float nameSize = 10;
            float maxWidth = (RADIUS - 5) * 2;
            float width = bold.getWidthPoint(stamp.approverName(), nameSize);
            if (width > maxWidth) {
                nameSize = Math.max(5, nameSize * maxWidth / width);
            }
            text(canvas, bold, nameSize, STAMP_RED, stamp.approverName(), cx, cy - 3);
            text(canvas, regular, 6.5f, STAMP_RED, STAMP_DATE.format(stamp.approvedAt()), cx, cy - 14);

            String caption = stamp.departmentName() == null
                    ? STAMP_TIME.format(stamp.approvedAt())
                    : stamp.departmentName() + " · " + STAMP_TIME.format(stamp.approvedAt());
            text(canvas, regular, 6.5f, MUTED, caption, cx, position.getBottom() + 5);
            canvas.restoreState();
        }

        private void text(PdfContentByte canvas, BaseFont font, float size, Color color, String value,
                          float x, float y) {
            canvas.beginText();
            canvas.setColorFill(color);
            canvas.setFontAndSize(font, size);
            canvas.showTextAligned(Element.ALIGN_CENTER, value, x, y, 0);
            canvas.endText();
        }
    }

    // 쪽마다 아래에 문서번호·쪽 번호, 결재 완료가 아니면 뒤쪽에 상태 워터마크
    private final class PageDecoration extends PdfPageEventHelper {

        private final ApprovalDocumentResponse doc;

        private PageDecoration(ApprovalDocumentResponse doc) {
            this.doc = doc;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte over = writer.getDirectContent();
            Rectangle page = document.getPageSize();
            float y = document.bottom() - 24;
            over.saveState();
            over.setColorStroke(LINE);
            over.setLineWidth(0.5f);
            over.moveTo(document.left(), y + 12);
            over.lineTo(document.right(), y + 12);
            over.stroke();
            over.beginText();
            over.setColorFill(MUTED);
            over.setFontAndSize(regular, 7.5f);
            over.showTextAligned(Element.ALIGN_LEFT,
                    "BeforeAI 결재 문서 " + documentNumber(doc) + " · 제 " + doc.version() + " 판",
                    document.left(), y, 0);
            over.showTextAligned(Element.ALIGN_RIGHT, writer.getPageNumber() + " 쪽", document.right(), y, 0);
            over.endText();
            over.restoreState();

            if (doc.downloadable()) {
                return;
            }
            PdfContentByte under = writer.getDirectContentUnder();
            under.saveState();
            under.beginText();
            under.setColorFill(WATERMARK);
            under.setFontAndSize(bold, 64);
            under.showTextAligned(Element.ALIGN_CENTER, statusLabel(doc.status()),
                    page.getWidth() / 2, page.getHeight() / 2, 35);
            under.endText();
            under.restoreState();
        }
    }
}
