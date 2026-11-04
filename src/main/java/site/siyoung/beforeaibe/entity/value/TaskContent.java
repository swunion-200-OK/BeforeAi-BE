package site.siyoung.beforeaibe.entity.value;

import com.fasterxml.jackson.annotation.JsonInclude;

// 작업 유형별로 diff(코드) · command(배포) · query(DB·데이터) · body(문서) 중 하나만 채워짐
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskContent(
        String diff,
        String command,
        String query,
        String body
) {
}
