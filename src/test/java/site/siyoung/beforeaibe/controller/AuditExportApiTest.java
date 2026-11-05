package site.siyoung.beforeaibe.controller;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.dto.audit.AuditLogFilterRequest;
import site.siyoung.beforeaibe.entity.AuditExport;
import site.siyoung.beforeaibe.entity.AuditLog;
import site.siyoung.beforeaibe.entity.Department;
import site.siyoung.beforeaibe.entity.Project;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.User;
import site.siyoung.beforeaibe.entity.enums.ExportFormat;
import site.siyoung.beforeaibe.entity.enums.ExportStatus;
import site.siyoung.beforeaibe.entity.enums.Network;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;
import site.siyoung.beforeaibe.entity.enums.Role;
import site.siyoung.beforeaibe.entity.value.AuditExportFilters;
import site.siyoung.beforeaibe.repository.AuditExportRepository;
import site.siyoung.beforeaibe.repository.AuditLogRepository;
import site.siyoung.beforeaibe.repository.DepartmentRepository;
import site.siyoung.beforeaibe.repository.ProjectRepository;
import site.siyoung.beforeaibe.repository.RequestRepository;
import site.siyoung.beforeaibe.repository.UserRepository;
import site.siyoung.beforeaibe.service.AuditExportFileService;
import site.siyoung.beforeaibe.service.AuditLogService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.INTERNAL_IP;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.bearer;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.external;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.internal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.json;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.loginInternal;
import static site.siyoung.beforeaibe.controller.ApiTestSupport.saveUser;

// 10만 건 제한을 실제 10만 행 없이 검증하도록 최대 건수를 2로 낮춤
@SpringBootTest(properties = "audit.export.max-rows=2")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AuditExportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AuditExportRepository auditExportRepository;

    @Autowired
    private AuditExportFileService auditExportFileService;

    @Autowired
    private EntityManager em;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Department dev;
    private User me;
    private Request myRequest;

    private final Map<String, String> tokens = new HashMap<>();

    @BeforeEach
    void setUp() {
        dev = departmentRepository.saveAndFlush(Department.builder().name("개발팀").build());
        me = saveUser(userRepository, passwordEncoder, "EMP001", Role.EMPLOYEE, dev.getId());
        saveUser(userRepository, passwordEncoder, "EMP002", Role.EMPLOYEE, dev.getId());
        saveUser(userRepository, passwordEncoder, "MGR001", Role.DEPT_MANAGER, dev.getId());
        saveUser(userRepository, passwordEncoder, "ADMIN001", Role.ADMIN, null);
        Project project = projectRepository.saveAndFlush(Project.builder()
                .name("인증 서비스").department(dev).repository("git@internal:auth-service.git").build());
        myRequest = requestRepository.saveAndFlush(Request.builder()
                .projectId(project.getId()).requesterId(me.getId()).prompt("작업 요청")
                .source(RequestSource.PROMPT).status(RequestStatus.REVIEWING).analysisAttempts(1).build());

        AuditLog log = auditLogRepository.saveAndFlush(AuditLog.builder()
                .actor(me).action("VERIFICATION_FAILED").department(dev).project(project).request(myRequest)
                .taskIds(List.of(UUID.fromString("261514d6-cf26-5489-a9a8-7cbc6bd60839")))
                .network(Network.INTERNAL).ip("100.64.0.10")
                .detail(Map.of("failedChecks", List.of("NONCE_ALREADY_USED")))
                .build());
        em.createNativeQuery("update audit_logs set created_at = :at where id = :id")
                .setParameter("at", Instant.parse("2026-11-04T03:12:09Z"))
                .setParameter("id", log.getId())
                .executeUpdate();
        em.clear();
    }

    private String token(String employeeNumber) throws Exception {
        String token = tokens.get(employeeNumber);
        if (token == null) {
            token = json(loginInternal(mockMvc, employeeNumber), "$.accessToken");
            tokens.put(employeeNumber, token);
        }
        return bearer(token);
    }

    private UUID startExport(String employeeNumber, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/audit-logs/exports").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token(employeeNumber))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        return UUID.fromString(json(result, "$.exportId"));
    }

    // 커밋 뒤 백그라운드에서 하는 파일 생성을 테스트 트랜잭션 안에서 바로 실행함
    private void generate(UUID exportId, AuditLogService.Scope scope) {
        AuditExport export = auditExportRepository.findById(exportId).orElseThrow();
        auditExportFileService.generate(exportId, scope, AuditLogFilterRequest.from(export.getFilters()));
        em.flush();
        em.clear();
    }

    @Test
    void 내보내기를_시작하면_202와_PROCESSING을_주고_조건과_건수를_감사_기록으로_남긴다() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/audit-logs/exports").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("""
                                {"format": "CSV", "filters": {"action": "VERIFICATION_FAILED"}}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.format").value("CSV"))
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.fileName").doesNotExist())
                .andExpect(jsonPath("$.downloadUrl").doesNotExist())
                .andReturn();

        AuditExport export = auditExportRepository.findById(UUID.fromString(json(result, "$.exportId"))).orElseThrow();
        assertThat(export.getRequester().getId()).isEqualTo(me.getId());
        assertThat(export.getFilters().action()).isEqualTo("VERIFICATION_FAILED");
        // 끝 시각을 주지 않으면 시작 시각으로 고정함
        assertThat(export.getFilters().to()).isNotNull();

        List<AuditLog> exported = auditLogRepository.findAll().stream()
                .filter(log -> log.getAction().equals("AUDIT_EXPORTED"))
                .toList();
        assertThat(exported).hasSize(1);
        assertThat(exported.getFirst().getActor().getId()).isEqualTo(me.getId());
        assertThat(exported.getFirst().getDepartment().getId()).isEqualTo(dev.getId());
        assertThat(exported.getFirst().getNetwork()).isEqualTo(Network.INTERNAL);
        assertThat(exported.getFirst().getIp()).isEqualTo(INTERNAL_IP);
        assertThat(exported.getFirst().getDetail())
                .containsEntry("exportId", export.getId().toString())
                .containsEntry("format", "CSV")
                .containsKey("filters");
        assertThat(((Number) exported.getFirst().getDetail().get("rowCount")).longValue()).isEqualTo(1L);
    }

    @Test
    void 외부망에서는_내보내기_API를_쓸_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/audit-logs/exports").with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"format\": \"CSV\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
        mockMvc.perform(get("/api/v1/audit-logs/exports").with(external())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_NETWORK_ONLY"));
    }

    @Test
    void 최대_건수를_넘으면_422_EXPORT_TOO_LARGE다() throws Exception {
        for (int i = 0; i < 2; i++) {
            auditLogRepository.saveAndFlush(AuditLog.builder()
                    .action("LOGIN").taskIds(List.of()).detail(Map.of()).build());
        }

        mockMvc.perform(post("/api/v1/audit-logs/exports").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN001"))
                        .contentType("application/json")
                        .content("{\"format\": \"XLSX\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("EXPORT_TOO_LARGE"));
        assertThat(auditExportRepository.count()).isZero();
    }

    @Test
    void 형식이_없으면_400이다() throws Exception {
        mockMvc.perform(post("/api/v1/audit-logs/exports").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001"))
                        .contentType("application/json")
                        .content("{\"filters\": {}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 준비_중인_내보내기는_내려받을_수_없다() throws Exception {
        UUID exportId = startExport("EMP001", "{\"format\": \"CSV\"}");

        mockMvc.perform(get("/api/v1/audit-logs/exports/{id}", exportId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        mockMvc.perform(get("/api/v1/audit-logs/exports/{id}/file", exportId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 준비된_CSV는_BOM과_명세의_열로_내려받는다() throws Exception {
        UUID exportId = startExport("EMP001", """
                {"format": "CSV", "filters": {"from": "2026-10-01T00:00:00Z", "to": "2026-11-05T00:00:00Z"}}
                """);
        generate(exportId, new AuditLogService.Scope(null, me.getId()));

        mockMvc.perform(get("/api/v1/audit-logs/exports/{id}", exportId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.fileName").value("audit-logs_20261001-20261105.csv"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.downloadUrl").value("/api/v1/audit-logs/exports/" + exportId + "/file"));

        MvcResult file = mockMvc.perform(get("/api/v1/audit-logs/exports/{id}/file", exportId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=utf-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment;")))
                .andReturn();

        String csv = new String(file.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿시각(KST),행위자,부서,동작,프로젝트,요청 ID,작업 ID,접속 환경,IP,결과,상세\r\n");
        assertThat(csv).contains("2026-11-04 12:12:09,홍길동,개발팀,VERIFICATION_FAILED,인증 서비스,"
                + myRequest.getId() + ",261514d6-cf26-5489-a9a8-7cbc6bd60839,INTERNAL,100.64.0.10,실패,"
                + "\"{\"\"failedChecks\"\":[\"\"NONCE_ALREADY_USED\"\"]}\"\r\n");
    }

    @Test
    void 준비된_XLSX는_엑셀_형식으로_내려받는다() throws Exception {
        UUID exportId = startExport("EMP001", "{\"format\": \"XLSX\"}");
        generate(exportId, new AuditLogService.Scope(null, me.getId()));

        MvcResult file = mockMvc.perform(get("/api/v1/audit-logs/exports/{id}/file", exportId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn();

        // XLSX는 ZIP 파일이므로 PK 서명으로 시작함
        byte[] content = file.getResponse().getContentAsByteArray();
        assertThat(content[0]).isEqualTo((byte) 'P');
        assertThat(content[1]).isEqualTo((byte) 'K');
    }

    @Test
    void 다른_사람의_내보내기는_403이고_목록에도_나오지_않는다() throws Exception {
        UUID exportId = startExport("EMP001", "{\"format\": \"CSV\"}");

        mockMvc.perform(get("/api/v1/audit-logs/exports/{id}", exportId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        mockMvc.perform(get("/api/v1/audit-logs/exports/{id}/file", exportId).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/audit-logs/exports").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/v1/audit-logs/exports").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].exportId").value(exportId.toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void 만료_시각이_지나면_조회할_때_EXPIRED로_바꾸고_파일을_지운다() throws Exception {
        AuditExport export = auditExportRepository.saveAndFlush(AuditExport.builder()
                .requester(me).format(ExportFormat.CSV).status(ExportStatus.PROCESSING)
                .filters(new AuditExportFilters(null, null, null, null, null, null, null, null))
                .rowCount(1).build());
        export.markReady("audit-logs.csv", "a".getBytes(StandardCharsets.UTF_8), Instant.now().minusSeconds(1));
        auditExportRepository.saveAndFlush(export);
        em.clear();

        mockMvc.perform(get("/api/v1/audit-logs/exports/{id}", export.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.downloadUrl").doesNotExist());
        mockMvc.perform(get("/api/v1/audit-logs/exports/{id}/file", export.getId()).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        em.flush();
        em.clear();
        AuditExport expired = auditExportRepository.findById(export.getId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(ExportStatus.EXPIRED);
        assertThat(expired.getFileContent()).isNull();
    }

    @Test
    void 부서_관리자가_부서를_정하지_않으면_소속_부서로_좁혀_저장한다() throws Exception {
        UUID exportId = startExport("MGR001", "{\"format\": \"CSV\"}");

        AuditExport export = auditExportRepository.findById(exportId).orElseThrow();
        assertThat(export.getFilters().departmentId()).isEqualTo(dev.getId());
        assertThat(export.getRowCount()).isEqualTo(1);
    }

    @Test
    void 목록을_조회할_때도_만료된_내보내기를_EXPIRED로_바꾼다() throws Exception {
        AuditExport export = auditExportRepository.saveAndFlush(AuditExport.builder()
                .requester(me).format(ExportFormat.CSV).status(ExportStatus.PROCESSING)
                .filters(new AuditExportFilters(null, null, null, null, null, null, null, null))
                .rowCount(1).build());
        export.markReady("audit-logs.csv", "a".getBytes(StandardCharsets.UTF_8), Instant.now().minusSeconds(1));
        auditExportRepository.saveAndFlush(export);
        em.clear();

        mockMvc.perform(get("/api/v1/audit-logs/exports").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].exportId").value(export.getId().toString()))
                .andExpect(jsonPath("$.items[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.items[0].downloadUrl").doesNotExist());

        em.flush();
        em.clear();
        assertThat(auditExportRepository.findById(export.getId()).orElseThrow().getFileContent()).isNull();
    }

    @Test
    void 내보내기_목록은_커서로_다음_페이지를_조회한다() throws Exception {
        UUID older = startExport("EMP001", "{\"format\": \"CSV\"}");
        UUID newer = startExport("EMP001", "{\"format\": \"XLSX\"}");

        MvcResult first = mockMvc.perform(get("/api/v1/audit-logs/exports").param("limit", "1").with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].exportId").value(newer.toString()))
                .andReturn();

        mockMvc.perform(get("/api/v1/audit-logs/exports").param("limit", "1")
                        .param("cursor", json(first, "$.nextCursor")).with(internal())
                        .header(HttpHeaders.AUTHORIZATION, token("EMP001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].exportId").value(older.toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }
}
