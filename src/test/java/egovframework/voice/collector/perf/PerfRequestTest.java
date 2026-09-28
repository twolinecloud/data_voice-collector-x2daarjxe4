package egovframework.voice.collector.perf;

import egovframework.voice.collector.stt.MockSttLatency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 성능 시험 조건 — 기본 부하(접견·전화 건수 · 기 STT 비율 · 트랙별 지연)와 임계 시험(워커 램프업).
 */
class PerfRequestTest {

    private static PerfRequest req(Integer meet, Integer phone, Integer conc, String mode, Long meetMs, Long phoneMs,
                                   Integer jitter, Long timeout) {
        return new PerfRequest("T", meet, phone, 3, conc, mode, meetMs, phoneMs, jitter, timeout).withDefaults();
    }

    private static RampRequest ramp(int start, String mode, int value, int max) {
        return new RampRequest(4, 4, 0, null, 0L, 0L, null, null, start, mode, value, max, 3, 60, true).withDefaults();
    }

    @Test
    @DisplayName("비우면 화면 기본값 — 접견 150 · 전화 150 · 기 STT 3% · 동시성 4 · 접견 180초 / 전화 120초 고정")
    void defaults() {
        PerfRequest r = new PerfRequest(null, null, null, null, null, null, null, null, null, null).withDefaults();

        assertThat(r.meetCount()).isEqualTo(150);
        assertThat(r.phoneCount()).isEqualTo(150);
        assertThat(r.count()).isEqualTo(300);
        assertThat(r.sttPercent()).isEqualTo(3);
        assertThat(r.concurrency()).isEqualTo(4);
        assertThat(r.mode()).isEqualTo(MockSttLatency.Mode.FIXED);
        assertThat(r.meetLatencyMs()).isEqualTo(180_000L);
        assertThat(r.phoneLatencyMs()).isEqualTo(120_000L);
        assertThat(r.jitterPercent()).isZero();
        assertThat(r.sttTimeoutMs()).isNull();
        r.validate(500);
    }

    @Test
    @DisplayName("접견 + 전화 2~300 — 벗어나면 400, 배치 1회 상한보다 많아도 400, 한쪽만 0 은 된다")
    void countRange() {
        assertThatThrownBy(() -> req(1, 0, 4, null, null, null, null, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> req(151, 150, 4, null, null, null, null, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> req(-1, 10, 4, null, null, null, null, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> req(150, 150, 4, null, null, null, null, null).validate(200))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-files-per-run");
        req(0, 20, 4, null, null, null, null, null).validate(500);
    }

    @Test
    @DisplayName("기 STT 비율 0~100%")
    void sttPercentRange() {
        assertThatThrownBy(() -> new PerfRequest("T", 5, 5, 101, 4, null, null, null, null, null).withDefaults().validate(500))
                .isInstanceOf(IllegalArgumentException.class);
        new PerfRequest("T", 5, 5, 100, 4, null, null, null, null, null).withDefaults().validate(500);
    }

    @Test
    @DisplayName("동시성은 1·2·4·8·16 만")
    void concurrencyOptions() {
        assertThatThrownBy(() -> req(5, 5, 3, null, null, null, null, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        for (int c : new int[] {1, 2, 4, 8, 16}) {
            req(5, 5, c, null, null, null, null, null).validate(500);
        }
    }

    @Test
    @DisplayName("지연 — 트랙별 0~600,000ms, 범위는 ±변동 폭(기본 50%), 고정이면 변동 폭 0")
    void latencyRange() {
        assertThatThrownBy(() -> req(5, 5, 4, "FIXED", 600_001L, 0L, null, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> req(5, 5, 4, "RANGE", 1_000L, 1_000L, 101, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> req(5, 5, 4, "SLOW", 100L, 100L, null, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        PerfRequest r = req(5, 5, 4, "range", 180_000L, 120_000L, null, 150_000L);
        r.validate(500);
        assertThat(r.mode()).isEqualTo(MockSttLatency.Mode.RANGE);
        assertThat(r.jitterPercent()).isEqualTo(50);
        assertThat(req(5, 5, 4, "FIXED", 1_000L, 1_000L, 40, null).jitterPercent()).as("고정이면 변동 폭을 쓰지 않는다").isZero();
    }

    @Test
    @DisplayName("타임아웃 — 0 이나 비우면 적용 안 함, 적용하면 100~1,800,000ms")
    void timeoutRange() {
        assertThat(req(5, 5, 4, null, null, null, null, 0L).sttTimeoutMs()).isNull();
        assertThatThrownBy(() -> req(5, 5, 4, null, null, null, null, 50L).validate(500)).isInstanceOf(IllegalArgumentException.class);
        req(5, 5, 4, null, null, null, null, 1_800_000L).validate(500);
    }

    @Test
    @DisplayName("램프업 계획 — ×2 는 넘치면 최대에서 끝내고, +N 도 마지막은 최대 워커")
    void rampPlan() {
        assertThat(ramp(4, "MULTIPLY", 2, 32).plan()).containsExactly(4, 8, 16, 32);
        assertThat(ramp(4, "MULTIPLY", 2, 20).plan()).containsExactly(4, 8, 16, 20);
        assertThat(ramp(3, "ADD", 5, 20).plan()).containsExactly(3, 8, 13, 18, 20);
        assertThat(ramp(8, "ADD", 1, 8).plan()).containsExactly(8);
        ramp(4, "MULTIPLY", 2, 32).validate(500);
    }

    @Test
    @DisplayName("램프업 범위 — 최대 < 시작 · ×N 은 2~4 · 단계 20개 초과는 400")
    void rampValidation() {
        assertThatThrownBy(() -> ramp(8, "MULTIPLY", 2, 4).validate(500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ramp(1, "MULTIPLY", 5, 64).validate(500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ramp(1, "ADD", 1, 64).validate(500))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20");
        assertThat(List.of(ramp(1, "ADD", 4, 64).plan().size())).allMatch(n -> n <= 20);
    }
}
