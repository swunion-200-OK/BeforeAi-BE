package site.siyoung.beforeaibe.support;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogCsvWriterTest {

    private final AuditLogCsvWriter writer = new AuditLogCsvWriter();

    @Test
    void 파일_앞에_UTF8_BOM을_붙이고_머리글과_행을_줄마다_쓴다() {
        byte[] csv = writer.write(List.of("시각(KST)", "동작"), List.of(List.of("2026-11-04 12:12:09", "LOGIN")));

        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
        assertThat(new String(csv, StandardCharsets.UTF_8))
                .isEqualTo("﻿시각(KST),동작\r\n2026-11-04 12:12:09,LOGIN\r\n");
    }

    @Test
    void 쉼표_따옴표_줄바꿈이_있는_값은_큰따옴표로_감싼다() {
        byte[] csv = writer.write(List.of("상세"), List.of(List.of("{\"a\":1,\"b\":\"x\"}"), List.of("줄\n바꿈")));

        assertThat(new String(csv, StandardCharsets.UTF_8))
                .isEqualTo("﻿상세\r\n\"{\"\"a\"\":1,\"\"b\"\":\"\"x\"\"}\"\r\n\"줄\n바꿈\"\r\n");
    }

    @Test
    void 수식으로_시작하는_값에는_작은따옴표를_붙인다() {
        assertThat(AuditLogCsvWriter.neutralizeFormula("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(AuditLogCsvWriter.neutralizeFormula("+1")).isEqualTo("'+1");
        assertThat(AuditLogCsvWriter.neutralizeFormula("-1")).isEqualTo("'-1");
        assertThat(AuditLogCsvWriter.neutralizeFormula("@cmd")).isEqualTo("'@cmd");
        assertThat(AuditLogCsvWriter.neutralizeFormula("김담당")).isEqualTo("김담당");
        assertThat(AuditLogCsvWriter.neutralizeFormula(null)).isEmpty();
    }

    @Test
    void CSV_셀에도_수식_방지가_적용된다() {
        byte[] csv = writer.write(List.of("행위자"), List.of(List.of("=HYPERLINK(\"x\")")));

        assertThat(new String(csv, StandardCharsets.UTF_8))
                .isEqualTo("﻿행위자\r\n\"'=HYPERLINK(\"\"x\"\")\"\r\n");
    }
}
