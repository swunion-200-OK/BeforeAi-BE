package site.siyoung.beforeaibe.support;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

// 감사 로그 CSV 파일 (CORE-13). 엑셀에서 한글이 깨지지 않도록 UTF-8 BOM을 붙임
@Component
public class AuditLogCsvWriter {

    private static final String BOM = "﻿";
    private static final String LINE_SEPARATOR = "\r\n";

    public byte[] write(List<String> header, List<List<String>> rows) {
        StringBuilder csv = new StringBuilder(BOM);
        appendLine(csv, header);
        rows.forEach(row -> appendLine(csv, row));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // =, +, -, @로 시작하는 값은 스프레드시트가 수식으로 실행하지 않도록 작은따옴표를 붙임 (XLSX에서도 사용)
    public static String neutralizeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }

    private void appendLine(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escape(neutralizeFormula(cells.get(i))));
        }
        csv.append(LINE_SEPARATOR);
    }

    // RFC 4180: 쉼표·따옴표·줄바꿈이 있으면 큰따옴표로 감싸고 안의 큰따옴표는 두 번 씀
    private String escape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
