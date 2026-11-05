package site.siyoung.beforeaibe.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;

// 목록 커서: (createdAt, id)를 Base64URL로 감싼 값. 클라이언트는 내용을 해석하지 않고 그대로 돌려보냄.
// createdAt desc, id desc 정렬을 쓰는 목록(계정·서명키 등)에서 공통으로 사용
public record CreatedAtCursor(Instant createdAt, UUID id) {

    public static CreatedAtCursor decode(String value) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            return new CreatedAtCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "cursor 값이 올바르지 않습니다");
        }
    }

    public String encode() {
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
