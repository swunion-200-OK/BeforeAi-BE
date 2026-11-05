package site.siyoung.beforeaibe.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

// 상태 이력에 남길 행위자. AI 분석 스레드·웹쉘 콜백처럼 로그인 사용자가 없으면 null
public final class CurrentActor {

    private CurrentActor() {
    }

    public static UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthUser user ? user.id() : null;
    }
}
