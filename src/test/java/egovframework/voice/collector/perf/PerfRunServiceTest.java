package egovframework.voice.collector.perf;

import egovframework.voice.collector.source.SimulationDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 성능 테스트 한 회차 — 준비(SIM N건) → 측정(워커 N개) → 정리(SIM 삭제) → 이력 한 줄.
 *
 * <p>로컬 H2 · 브로커 MOCK · 로그 컬렉터 미연동으로 끝까지 돌린다. 정합성 점검은 컬렉터가 없어
 * '확인 불가' 로 남는 것이 맞다.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
class PerfRunServiceTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        registry.add("voice.source.mode", () -> "MOCK");
        registry.add("voice.broker.mode", () -> "MOCK");
        registry.add("log-collector.enabled", () -> "false");
        registry.add("voice.dirs.base-dir", () -> tmp.toString());
        registry.add("voice.dirs.receive-meet", () -> tmp.resolve("raw/meet").toString());
        registry.add("voice.dirs.receive-phone", () -> tmp.resolve("raw/phone").toString());
        registry.add("voice.dirs.work", () -> tmp.resolve("work").toString());
        registry.add("voice.dirs.output-meet", () -> tmp.resolve("xenon/meet").toString());
        registry.add("voice.dirs.output-phone", () -> tmp.resolve("xenon/phone").toString());
        registry.add("voice.dirs.xvarm-original", () -> tmp.resolve("xvarm_original").toString());
        registry.add("voice.sync.wait-timeout-sec", () -> "15");
        registry.add("voice.sync.stable-check-ms", () -> "50");
    }

    @Autowired
    private PerfRunService perf;
    @Autowired
    private SimulationDataService sim;

    @Test
    @DisplayName("10건 · 워커 2 · 고정 50ms — 전건 성공, 단계별 시간·힙·Hikari 가 잡히고, SIM 은 지워지고, 이력이 한 줄 남는다")
    void oneRunEndToEnd() throws Exception {
        perf.clearHistory();

        Map<String, Object> started = perf.start(new PerfRequest("A", 10, 2, "FIXED", 50L, null, null));
        assertThat(started.get("active")).isEqualTo(true);
        // 도는 동안에는 두 번째를 받지 않는다
        assertThatThrownBy(() -> perf.start(new PerfRequest("A", 10, 2, "FIXED", 50L, null, null)))
                .isInstanceOf(IllegalStateException.class);

        Map<String, Object> cur = waitDone();
        assertThat(cur.get("phase")).as("오류: %s", cur.get("error")).isEqualTo("DONE");

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) cur.get("result");
        assertThat(r.get("total")).isEqualTo(10);
        assertThat(r.get("success")).isEqualTo(10);
        assertThat(r.get("timeout")).isEqualTo(0L);
        assertThat((Double) r.get("tps")).isPositive();
        assertThat((Double) r.get("avgMs")).isPositive();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages = (List<Map<String, Object>>) r.get("stages");
        assertThat(stages).extracting(s -> s.get("key"))
                .containsExactly("ACQUIRE", "DECRYPT", "FORMAT", "STT", "TEMP", "SAVE");
        Map<String, Object> stt = stages.get(3);
        assertThat(((Number) stt.get("count")).intValue()).isEqualTo(9);   // 전화 3번은 보라미 STT 재사용
        assertThat((Double) stt.get("avgMs")).isGreaterThanOrEqualTo(50d);

        @SuppressWarnings("unchecked")
        Map<String, Object> heap = (Map<String, Object>) r.get("heap");
        assertThat((Double) heap.get("peakMb")).isPositive();
        @SuppressWarnings("unchecked")
        Map<String, Object> hikari = (Map<String, Object>) r.get("hikari");
        assertThat((Integer) hikari.get("peak")).as("대상 조회가 원천 풀에서 연결을 빌린다").isGreaterThanOrEqualTo(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> checks = (Map<String, Object>) r.get("checks");
        assertThat(checks.get("available")).as("로그 컬렉터 미연동").isEqualTo(false);

        // 정리 — SIM 행이 남지 않는다
        @SuppressWarnings("unchecked")
        Map<String, Object> rows = (Map<String, Object>) sim.status().get("rows");
        assertThat(rows.get("meet")).isEqualTo(0);
        assertThat(rows.get("phone")).isEqualTo(0);

        // 이력 — 한 줄, PV 파일에
        Map<String, Object> h = perf.history();
        assertThat(h.get("total")).isEqualTo(1);
        assertThat(Files.isRegularFile(Path.of((String) h.get("file")))).isTrue();
        assertThat(perf.clearHistory().get("deleted")).isEqualTo(1);
    }

    @Test
    @DisplayName("범위 밖이면 시작하지 않는다 — 301건")
    void rejectsOutOfRange() {
        assertThatThrownBy(() -> perf.start(new PerfRequest("A", 301, 4, "FIXED", 0L, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Map<String, Object> waitDone() throws InterruptedException {
        long until = System.currentTimeMillis() + 90_000;
        Map<String, Object> cur = perf.current();
        while (System.currentTimeMillis() < until) {
            cur = perf.current();
            if (Boolean.FALSE.equals(cur.get("active"))) {
                return cur;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("성능 테스트가 90초 안에 끝나지 않았다 — " + cur);
    }
}
