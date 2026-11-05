package site.siyoung.beforeaibe.support;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

// 감사 로그 XLSX 파일 (CORE-13). 모든 셀을 문자열로 쓰고, CSV와 같은 수식 방지 규칙을 적용함
@Component
public class AuditLogXlsxWriter {

    private static final String APPLICATION_NAME = "BeforeAI";
    private static final String APPLICATION_VERSION = "1.0";
    private static final String SHEET_NAME = "audit-logs";

    public byte[] write(List<String> header, List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Workbook workbook = new Workbook(out, APPLICATION_NAME, APPLICATION_VERSION);
            Worksheet sheet = workbook.newWorksheet(SHEET_NAME);
            writeRow(sheet, 0, header);
            for (int i = 0; i < rows.size(); i++) {
                writeRow(sheet, i + 1, rows.get(i));
            }
            workbook.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private void writeRow(Worksheet sheet, int rowIndex, List<String> cells) {
        for (int col = 0; col < cells.size(); col++) {
            sheet.value(rowIndex, col, AuditLogCsvWriter.neutralizeFormula(cells.get(col)));
        }
    }
}
