package egovframework.voice.collector.perf;

import egovframework.voice.collector.stt.MockSttLatency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 성능 테스트 조건 — 화면 기본값과 범위(일일 한도 300건 · 지연 0~5,000ms · 동시성 1/2/4/8/16). */
class PerfRequestTest {

    private static PerfRequest req(Integer count, Integer conc, String mode, Long ms, Long max, Long timeout) {
        return new PerfRequest("T", count, conc, mode, ms, max, timeout).withDefaults();
    }

    @Test
    @DisplayName("비우면 화면 기본값 — 50건 · 동시성 4 · 고정 500ms · 타임아웃 없음")
    void defaults() {
        PerfRequest r = new PerfRequest(null, null, null, null, null, null, null).withDefaults();

        assertThat(r.count()).isEqualTo(50);
        assertThat(r.concurrency()).isEqualTo(4);
        assertThat(r.mode()).isEqualTo(MockSttLatency.Mode.FIXED);
        assertThat(r.latencyMs()).isEqualTo(500L);
        assertThat(r.sttTimeoutMs()).isNull();
        assertThat(r.meetCount() + r.phoneCount()).isEqualTo(50);
        r.validate(500);
    }

    @Test
    @DisplayName("건수 10~300 — 벗어나면 400, 배치 1회 상한보다 많아도 400")
    void countRange() {
        assertThatThrownBy(() -> req(9, 4, null, null, null, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> req(301, 4, null, null, null, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> req(300, 4, null, null, null, null).validate(200))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-files-per-run");
        req(300, 4, null, null, null, null).validate(500);
    }

    @Test
    @DisplayName("동시성은 1·2·4·8·16 만")
    void concurrencyOptions() {
        assertThatThrownBy(() -> req(50, 3, null, null, null, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        for (int c : new int[] {1, 2, 4, 8, 16}) {
            req(50, c, null, null, null, null).validate(500);
        }
    }

    @Test
    @DisplayName("지연 — 0~5,000ms, 범위는 최댓값이 최솟값 이상 · 최댓값을 비우면 최솟값과 같다")
    void latencyRange() {
        assertThatThrownBy(() -> req(50, 4, "FIXED", 5_001L, null, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> req(50, 4, "RANGE", 3_000L, 1_000L, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> req(50, 4, "SLOW", 100L, null, null).validate(500)).isInstanceOf(IllegalArgumentException.class);
        PerfRequest r = req(50, 4, "range", 1_000L, 5_000L, 3_000L);
        r.validate(500);
        assertThat(r.mode()).isEqualTo(MockSttLatency.Mode.RANGE);
        assertThat(req(50, 4, "RANGE", 1_000L, null, null).latencyMaxMs()).isEqualTo(1_000L);
        assertThat(req(50, 4, "FIXED", 1_000L, 5_000L, null).latencyMaxMs()).as("고정이면 최댓값을 쓰지 않는다").isNull();
    }

    @Test
    @DisplayName("타임아웃 — 0 이나 비우면 적용 안 함, 적용하면 100~600,000ms")
    void timeoutRange() {
        assertThat(req(50, 4, null, null, null, 0L).sttTimeoutMs()).isNull();
        assertThatThrownBy(() -> req(50, 4, null, null, null, 50L).validate(500)).isInstanceOf(IllegalArgumentException.class);
        req(50, 4, null, null, null, 3_000L).validate(500);
    }
}
