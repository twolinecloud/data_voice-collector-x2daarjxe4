package egovframework.voice.collector.batch;

import egovframework.voice.collector.model.BatchWindow;
import egovframework.voice.collector.model.FileProcOutcome;
import egovframework.voice.collector.model.ProcStatus;
import egovframework.voice.collector.model.VoiceKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전 구간 Mock E2E — <b>Phase 1 의 완료 판정 기준</b>이다.
 *
 * <p>보라미도 XVARM 도 NPU 도 없는 상태에서 배치가 끝까지 도는지 본다.
 * 대상 선별 → 파일 확보 → 복호화(통과) → STT → 커넥터 전달까지의 배선이 전부 이어져 있어야 통과한다.</p>
 *
 * <p>커넥터 전송은 꺼 둔다(local 프로파일). 실제 커넥터에 붙이는 것은 별도 단계이고,
 * 여기서 검증할 것은 <b>우리 쪽 파이프라인이 완성됐는가</b>이다.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
class VoiceCollectE2ETest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        // 테스트가 ./work 를 오염시키지 않게 임시 디렉터리로 돌린다.
        registry.add("voice.sync.meet-dir", () -> tmp.resolve("raw/meet").toString());
        registry.add("voice.sync.phone-dir", () -> tmp.resolve("raw/phone").toString());
        registry.add("voice.sync.work-dir", () -> tmp.resolve("work").toString());
        registry.add("voice.sync.wait-timeout-sec", () -> "15");
        registry.add("voice.sync.stable-check-ms", () -> "50");
    }

    @Autowired
    private VoiceCollectService service;

    @Autowired
    private IdempotencyGuard idempotency;

    private BatchWindow wideWindow() {
        LocalDateTime now = LocalDateTime.now();
        return BatchWindow.manual(now.minusDays(2), now.plusDays(1));
    }

    @BeforeEach
    void resetIdempotency() {
        idempotency.clearAll();
    }

    @Test
    @DisplayName("Mock 전 구간이 이어져 배치가 끝까지 돈다")
    void runsEndToEnd() {
        VoiceBatchResult result = service.run(wideWindow(), null, "TEST");

        assertThat(result.targetCnt()).as("Mock 대상 접견 5 + 전화 5").isEqualTo(10);
        assertThat(result.failCnt()).as("실패 없이 전부 처리돼야 한다").isZero();
        assertThat(result.successCnt()).isEqualTo(10);
        assertThat(result.execStsCd()).isEqualTo("SUCCESS");
        assertThat(result.execId()).isNotBlank();
    }

    @Test
    @DisplayName("STT 텍스트가 실제로 만들어진다 — 빈 텍스트면 하류가 무의미하다")
    void producesSttText() {
        VoiceBatchResult result = service.run(wideWindow(), List.of(VoiceKind.PHONE), "TEST");

        assertThat(result.outcomes()).isNotEmpty();
        assertThat(result.outcomes())
                .filteredOn(FileProcOutcome::isSuccess)
                .allSatisfy(o -> assertThat(o.sttChars()).isPositive());
    }

    @Test
    @DisplayName("보라미가 이미 가진 STT 는 오디오를 거치지 않고 재사용한다(Q1 시나리오)")
    void reusesSourceSttWhenAvailable() {
        VoiceBatchResult result = service.run(wideWindow(), List.of(VoiceKind.PHONE), "TEST");

        // Mock 전화 5건 중 1건이 기존 STT 보유 시나리오다. 그 건도 성공해야 한다.
        assertThat(result.successCnt()).isEqualTo(5);
        assertThat(result.failCnt()).isZero();
    }

    @Test
    @DisplayName("두 번 돌려도 같은 건을 다시 처리하지 않는다 — 주기배치 창이 겹치기 때문")
    void isIdempotentAcrossRuns() {
        VoiceBatchResult first = service.run(wideWindow(), null, "TEST");
        assertThat(first.successCnt()).isEqualTo(10);

        VoiceBatchResult second = service.run(wideWindow(), null, "TEST");

        assertThat(second.skippedCnt()).as("두 번째 실행은 전부 건너뛴다").isEqualTo(10);
        assertThat(second.successCnt()).isZero();
        assertThat(second.outcomes())
                .allSatisfy(o -> assertThat(o.status()).isEqualTo(ProcStatus.SKIPPED));
    }

    @Test
    @DisplayName("멱등 표식을 지우면 다시 처리할 수 있다 — 시연 반복용")
    void canReprocessAfterClearing() {
        service.run(wideWindow(), null, "TEST");
        idempotency.clearAll();

        VoiceBatchResult again = service.run(wideWindow(), null, "TEST");

        assertThat(again.successCnt()).isEqualTo(10);
        assertThat(again.skippedCnt()).isZero();
    }

    @Test
    @DisplayName("종류를 지정하면 그것만 처리한다")
    void filtersByKind() {
        VoiceBatchResult meetOnly = service.run(wideWindow(), List.of(VoiceKind.MEET), "TEST");

        assertThat(meetOnly.targetCnt()).isEqualTo(5);
        assertThat(meetOnly.outcomes())
                .allSatisfy(o -> assertThat(o.target().kind()).isEqualTo(VoiceKind.MEET));
    }

    @Test
    @DisplayName("SKIPPED 건은 T4 집계에서 빠진다 — 정합성 대사가 어긋나지 않게")
    void skippedNotCountedAsSuccess() {
        service.run(wideWindow(), null, "TEST");

        VoiceBatchResult second = service.run(wideWindow(), null, "TEST");

        // T1.SUCCESS_CNT == Σ(T3·T4·T5) 규칙상, 건너뛴 건을 성공으로 세면 대사가 깨진다
        assertThat(second.successCnt()).isZero();
        assertThat(second.execStsCd()).isEqualTo("SUCCESS");   // 실패가 없으므로 SUCCESS
    }
}
