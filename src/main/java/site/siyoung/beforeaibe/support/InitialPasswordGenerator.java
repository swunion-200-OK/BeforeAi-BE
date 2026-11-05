package site.siyoung.beforeaibe.support;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

// 관리자 계정 생성/초기화 시 1회용 초기 비밀번호를 생성 (ADM-01). 사용자는 최초 로그인 때 내부망에서 바꿔야 함
@Component
public class InitialPasswordGenerator {

    // 혼동하기 쉬운 문자(0/O, 1/l/I)를 뺀 집합
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
