package site.siyoung.beforeaibe.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.dto.common.CursorPageResponse;
import site.siyoung.beforeaibe.dto.me.SigningKeyCreateRequest;
import site.siyoung.beforeaibe.dto.me.SigningKeyResponse;
import site.siyoung.beforeaibe.entity.SigningKey;
import site.siyoung.beforeaibe.entity.enums.SigningKeyStatus;
import site.siyoung.beforeaibe.exception.ApiException;
import site.siyoung.beforeaibe.exception.ErrorCode;
import site.siyoung.beforeaibe.repository.SigningKeyRepository;
import site.siyoung.beforeaibe.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

// 승인자 기기 서명 공개키 (ADM-09). 제출 직후 PENDING이고, 관리자 등록(3.2) 전에는 승인에 쓸 수 없음
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SigningKeyService {

    private static final String ALGORITHM = "Ed25519";
    private static final int ED25519_PUBLIC_KEY_BYTES = 32;
    // raw Ed25519 공개키 앞에 붙여 X.509(SubjectPublicKeyInfo) 형식으로 만드는 고정 헤더
    private static final byte[] ED25519_X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final List<SigningKeyStatus> ACTIVE_STATUSES =
            List.of(SigningKeyStatus.PENDING, SigningKeyStatus.REGISTERED);

    private final SigningKeyRepository signingKeyRepository;
    private final UserRepository userRepository;

    @Transactional
    public SigningKeyResponse create(UUID userId, SigningKeyCreateRequest request) {
        if (!ALGORITHM.equals(request.algorithm())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "지원하지 않는 알고리즘입니다");
        }
        validatePublicKey(request.publicKey());
        // 사용자당 폐기되지 않은 키는 1개만 허용
        if (signingKeyRepository.existsByUserIdAndStatusIn(userId, ACTIVE_STATUSES)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "이미 등록했거나 등록 대기 중인 공개키가 있습니다");
        }
        if (signingKeyRepository.existsByPublicKey(request.publicKey())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "이미 제출된 공개키입니다");
        }

        SigningKey saved = signingKeyRepository.save(SigningKey.builder()
                .user(userRepository.getReferenceById(userId))
                .algorithm(ALGORITHM)
                .publicKey(request.publicKey())
                .deviceName(request.deviceName())
                .status(SigningKeyStatus.PENDING)
                .build());
        return SigningKeyResponse.from(saved);
    }

    public CursorPageResponse<SigningKeyResponse> list(UUID userId, String cursor, int limit) {
        // 다음 페이지가 있는지 알기 위해 1개 더 조회
        Limit fetchLimit = Limit.of(limit + 1);
        List<SigningKey> keys;
        if (cursor == null || cursor.isBlank()) {
            keys = signingKeyRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, fetchLimit);
        } else {
            Cursor decoded = Cursor.decode(cursor);
            keys = signingKeyRepository.findPageAfter(userId, decoded.createdAt(), decoded.id(), fetchLimit);
        }

        boolean hasNext = keys.size() > limit;
        List<SigningKey> page = hasNext ? keys.subList(0, limit) : keys;
        String nextCursor = hasNext ? Cursor.of(page.getLast()).encode() : null;
        return new CursorPageResponse<>(page.stream().map(SigningKeyResponse::from).toList(), nextCursor);
    }

    // Base64 raw 32바이트이고 Ed25519 공개키로 읽히는지 확인
    private void validatePublicKey(String publicKey) {
        try {
            byte[] raw = Base64.getDecoder().decode(publicKey);
            if (raw.length != ED25519_PUBLIC_KEY_BYTES) {
                throw new IllegalArgumentException();
            }
            byte[] encoded = new byte[ED25519_X509_PREFIX.length + raw.length];
            System.arraycopy(ED25519_X509_PREFIX, 0, encoded, 0, ED25519_X509_PREFIX.length);
            System.arraycopy(raw, 0, encoded, ED25519_X509_PREFIX.length, raw.length);
            KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "공개키 형식이 올바르지 않습니다");
        }
    }

    // 목록 커서: 마지막 항목의 (createdAt, id)를 Base64URL로 감싼 값. 클라이언트는 내용을 해석하지 않고 그대로 돌려보냄
    private record Cursor(Instant createdAt, UUID id) {

        static Cursor of(SigningKey key) {
            return new Cursor(key.getCreatedAt(), key.getId());
        }

        static Cursor decode(String value) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                String[] parts = decoded.split("\\|", 2);
                return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "cursor 값이 올바르지 않습니다");
            }
        }

        String encode() {
            String raw = createdAt + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
    }
}
