package site.siyoung.beforeaibe.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.entity.AuditExport;
import site.siyoung.beforeaibe.entity.AuditLog;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ExportStatus;
import site.siyoung.beforeaibe.repository.AuditExportRepository;
import site.siyoung.beforeaibe.repository.AuditLogRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.service.AuditExportFileService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.internal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveAdmin;

// 내보내기 파일은 커밋 뒤 백그라운드 스레드에서 만들어지므로 @Transactional 없이 실제로 커밋해 검증함.
// 커밋한 데이터는 다른 테스트에 영향을 주지 않도록 @AfterEach에서 지움
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditExportBackgroundApiTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AuditExportRepository auditExportRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private AuditExportFileService auditExportFileService;

    private User admin;
    private AuditLog log;

    @BeforeEach
    void setUp() {
        admin = saveAdmin(userRepository, passwordEncoder, "BGADMIN001");
        log = auditLogRepository.saveAndFlush(AuditLog.builder()
                .actor(admin).action("LOGIN").taskIds(List.of()).detail(Map.of()).build());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from audit_logs where actor_id = ? or id = ?", admin.getId(), log.getId());
        jdbcTemplate.update("delete from audit_exports where requester_id = ?", admin.getId());
        jdbcTemplate.update("delete from refresh_tokens where user_id = ?", admin.getId());
        jdbcTemplate.update("delete from users where id = ?", admin.getId());
    }

    private UUID startExport() throws Exception {
        String token = json(loginInternal(mockMvc, "BGADMIN001"), "$.accessToken");
        MvcResult result = mockMvc.perform(post("/api/v1/audit-logs/exports").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, ApiTestSupport.bearer(token))
                        .contentType("application/json")
                        .content("{\"format\": \"CSV\", \"filters\": {\"actorId\": \"%s\", \"action\": \"LOGIN\"}}"
                                .formatted(admin.getId())))
                .andExpect(status().isAccepted())
                .andReturn();
        return UUID.fromString(json(result, "$.exportId"));
    }

    // 백그라운드 작업이 끝날 때까지 상태를 다시 읽음
    private AuditExport awaitStatus(UUID exportId, ExportStatus expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        AuditExport export = auditExportRepository.findById(exportId).orElseThrow();
        while (export.getStatus() != expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
            export = auditExportRepository.findById(exportId).orElseThrow();
        }
        return export;
    }

    @Test
    void 커밋_뒤_백그라운드에서_파일을_만들어_READY가_된다() throws Exception {
        UUID exportId = startExport();

        AuditExport export = awaitStatus(exportId, ExportStatus.READY);

        assertThat(export.getStatus()).isEqualTo(ExportStatus.READY);
        assertThat(export.getRowCount()).isEqualTo(1);
        assertThat(export.getFileContent()).isNotEmpty();
        assertThat(export.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(59)));
    }

    @Test
    void 파일_생성이_실패하면_FAILED가_된다() throws Exception {
        doThrow(new IllegalStateException("생성 실패")).when(auditExportFileService).generate(any(), any(), any());

        UUID exportId = startExport();

        assertThat(awaitStatus(exportId, ExportStatus.FAILED).getStatus()).isEqualTo(ExportStatus.FAILED);
    }

    @Test
    void 실패_표시마저_실패해도_예외를_넘기지_않고_PROCESSING에_남는다() throws Exception {
        doThrow(new IllegalStateException("생성 실패")).when(auditExportFileService).generate(any(), any(), any());
        doThrow(new IllegalStateException("저장 실패")).when(auditExportFileService).markFailed(any());

        UUID exportId = startExport();

        verify(auditExportFileService, timeout(WAIT.toMillis())).markFailed(eq(exportId));
        assertThat(auditExportRepository.findById(exportId).orElseThrow().getStatus())
                .isEqualTo(ExportStatus.PROCESSING);
    }
}
