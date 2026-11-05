package site.siyoung.beforeaibe.dto.admin;

import java.util.List;
import java.util.UUID;

// POST /admin/kill-all 응답 (CORE-11). executionIds는 중단된 웹쉘 실행 ID 목록
public record KillAllResponse(String status, List<UUID> executionIds) {
}
