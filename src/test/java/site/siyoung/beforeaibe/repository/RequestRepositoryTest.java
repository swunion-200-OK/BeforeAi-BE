package site.siyoung.beforeaibe.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import site.siyoung.beforeaibe.TestcontainersConfiguration;
import site.siyoung.beforeaibe.config.JpaAuditingConfig;
import site.siyoung.beforeaibe.entity.Plan;
import site.siyoung.beforeaibe.entity.Request;
import site.siyoung.beforeaibe.entity.enums.RequestSource;
import site.siyoung.beforeaibe.entity.enums.RequestStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class RequestRepositoryTest {

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void 요청을_저장하면_ID와_생성_수정_시각이_채워진다() {
        Request saved = requestRepository.saveAndFlush(RepositoryTestSupport.newRequest(em));
        em.clear();

        Request found = requestRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getId()).isNotNull();
        assertThat(found.getSource()).isEqualTo(RequestSource.PROMPT);
        assertThat(found.getStatus()).isEqualTo(RequestStatus.REVIEWING);
        assertThat(found.getAnalysisAttempts()).isEqualTo(1);
        assertThat(found.getCurrentPlanId()).isNull();
        assertThat(found.getClarification()).isNull();
        assertThat(found.getProgressVersion()).isZero();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void 현재_Plan_ID를_가진_요청을_저장할_수_있다() {
        Request request = RepositoryTestSupport.persistRequest(em);
        Plan plan = em.persist(RepositoryTestSupport.plan(request));

        Request withPlan = requestRepository.saveAndFlush(Request.builder()
                .projectId(request.getProjectId())
                .requesterId(request.getRequesterId())
                .prompt(request.getPrompt())
                .source(request.getSource())
                .status(RequestStatus.PENDING_APPROVAL)
                .analysisAttempts(1)
                .currentPlanId(plan.getId())
                .build());
        em.clear();

        assertThat(requestRepository.findById(withPlan.getId()).orElseThrow().getCurrentPlanId())
                .isEqualTo(plan.getId());
    }

    @Test
    void 존재하지_않는_Plan_ID를_현재_Plan으로_가리키면_저장되지_않는다() {
        Request valid = RepositoryTestSupport.newRequest(em);
        Request request = Request.builder()
                .projectId(valid.getProjectId())
                .requesterId(valid.getRequesterId())
                .prompt("프롬프트")
                .source(RequestSource.PROMPT)
                .status(RequestStatus.REVIEWING)
                .currentPlanId(UUID.randomUUID())
                .build();

        assertThatThrownBy(() -> requestRepository.saveAndFlush(request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 존재하지_않는_프로젝트를_가리키면_저장되지_않는다() {
        Request valid = RepositoryTestSupport.newRequest(em);

        assertThatThrownBy(() -> requestRepository.saveAndFlush(
                RepositoryTestSupport.request(UUID.randomUUID(), valid.getRequesterId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 존재하지_않는_요청자를_가리키면_저장되지_않는다() {
        Request valid = RepositoryTestSupport.newRequest(em);

        assertThatThrownBy(() -> requestRepository.saveAndFlush(
                RepositoryTestSupport.request(valid.getProjectId(), UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 확인_질문과_진행_트리_버전을_저장한다() {
        Request request = RepositoryTestSupport.persistRequest(em);
        request.askClarification("스테이징과 운영 중 어디에 배포할까요?");
        assertThat(request.increaseProgressVersion()).isEqualTo(1);
        assertThat(request.increaseProgressVersion()).isEqualTo(2);
        em.flush();
        em.clear();

        Request found = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(found.getClarification()).isEqualTo("스테이징과 운영 중 어디에 배포할까요?");
        assertThat(found.getProgressVersion()).isEqualTo(2);

        found.clearClarification();
        em.flush();
        em.clear();
        assertThat(requestRepository.findById(request.getId()).orElseThrow().getClarification()).isNull();
    }
}
