package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import site.siyoung.beforeaibe.entity.Approval;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.value.ApprovalEnvelope;
import site.siyoung.beforeaibe.entity.enums.Network;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class ApprovalRepositoryTest {

    @Autowired
    private ApprovalRepository approvalRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void 승인_기록과_암호화된_envelope를_저장하고_조회한다() {
        Request request = RepositoryTestSupport.persistRequest(em);
        Plan plan = RepositoryTestSupport.plan(request);
        plan.addTask(RepositoryTestSupport.codeTask(1));
        em.persist(plan);
        UUID taskId = plan.getTasks().getFirst().getId();
        UUID approverId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();

        Approval saved = approvalRepository.saveAndFlush(Approval.builder()
                .request(request)
                .plan(plan)
                .approverId(approverId)
                .keyId(keyId)
                .planHash("sha256:4be1")
                .taskIds(List.of(taskId))
                .network(Network.EXTERNAL)
                .envelope(ApprovalEnvelope.builder()
                        .alg("HPKE-X25519-AES256GCM")
                        .encapsulatedKey("ZW5jYXA=")
                        .ciphertext("Y2lwaGVy")
                        .build())
                .build());
        em.clear();

        Approval found = approvalRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getRequest().getId()).isEqualTo(request.getId());
        assertThat(found.getPlan().getId()).isEqualTo(plan.getId());
        assertThat(found.getApproverId()).isEqualTo(approverId);
        assertThat(found.getKeyId()).isEqualTo(keyId);
        assertThat(found.getPlanHash()).isEqualTo("sha256:4be1");
        assertThat(found.getTaskIds()).containsExactly(taskId);
        assertThat(found.getNetwork()).isEqualTo(Network.EXTERNAL);
        assertThat(found.getEnvelope().getAlg()).isEqualTo("HPKE-X25519-AES256GCM");
        assertThat(found.getEnvelope().getEncapsulatedKey()).isEqualTo("ZW5jYXA=");
        assertThat(found.getEnvelope().getCiphertext()).isEqualTo("Y2lwaGVy");
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
