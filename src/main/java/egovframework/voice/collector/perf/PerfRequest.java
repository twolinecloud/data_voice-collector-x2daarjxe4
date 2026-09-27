package egovframework.voice.collector.perf;

import egovframework.voice.collector.source.SimulationDataService;
import egovframework.voice.collector.stt.MockSttLatency;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Locale;

/**
 * 성능 테스트 한 회차의 조건.
 *
 * <p>비운 값은 {@link #withDefaults()} 가 화면 기본값(50건 · 동시성 4 · 고정 500ms · 타임아웃 없음)으로 채운다.</p>
 */
@Schema(description = "성능 테스트 조건")
public record PerfRequest(
        @Schema(description = "시나리오 이름 — 이력에 그대로 남는다(A 순수 처리량 · B NPU 지연 · C 동시성 비교 · D 최대 부하 · 직접 입력)",
                example = "A") String scenario,
        @Schema(description = "처리 건수 — 10~300 (운영 일일 한도 300). 접견·전화 반반", example = "50") Integer count,
        @Schema(description = "동시에 처리할 워커 수 — 1 · 2 · 4 · 8 · 16", example = "4") Integer concurrency,
        @Schema(description = "STT 가상 지연 방식 — FIXED(고정) · RANGE(범위 균등 난수)", example = "FIXED") String latencyMode,
        @Schema(description = "고정 지연(ms), 범위면 최솟값 — 0~5,000", example = "500") Long latencyMs,
        @Schema(description = "범위 지연의 최댓값(ms) — 0~5,000, 최솟값 이상", example = "5000") Long latencyMaxMs,
        @Schema(description = "STT 타임아웃(ms) — 가상 지연이 이 값을 넘으면 타임아웃 실패. 비우거나 0 이면 적용하지 않는다",
                example = "3000") Long sttTimeoutMs
) {

    public static final int MIN_COUNT = 10;
    public static final int MAX_COUNT = SimulationDataService.PERF_MAX_TOTAL;
    public static final long MAX_LATENCY_MS = 5_000L;
    public static final long MIN_TIMEOUT_MS = 100L;
    public static final long MAX_TIMEOUT_MS = 600_000L;
    public static final List<Integer> CONCURRENCY_OPTIONS = List.of(1, 2, 4, 8, 16);

    /** 비운 값을 기본값으로 채운다. */
    public PerfRequest withDefaults() {
        String mode = latencyMode == null || latencyMode.isBlank() ? "FIXED" : latencyMode.trim().toUpperCase(Locale.ROOT);
        return new PerfRequest(
                scenario == null || scenario.isBlank() ? "직접 입력" : scenario.trim(),
                count == null ? 50 : count,
                concurrency == null ? 4 : concurrency,
                mode,
                latencyMs == null ? 500L : latencyMs,
                "RANGE".equals(mode) ? (latencyMaxMs == null ? latencyMs : latencyMaxMs) : null,
                sttTimeoutMs == null || sttTimeoutMs <= 0 ? null : sttTimeoutMs);
    }

    /**
     * 범위를 확인한다 — 어긋나면 {@link IllegalArgumentException}(화면에는 400 과 이 문구).
     *
     * @param maxFilesPerRun 배치 1회 처리 상한({@code voice.batch.max-files-per-run}) — 넘으면 일부만 처리된다
     */
    public void validate(int maxFilesPerRun) {
        if (count < MIN_COUNT || count > MAX_COUNT) {
            throw new IllegalArgumentException("처리 건수는 %d~%d 이어야 합니다: %d".formatted(MIN_COUNT, MAX_COUNT, count));
        }
        if (count > maxFilesPerRun) {
            throw new IllegalArgumentException("처리 건수 %d 가 배치 1회 상한(voice.batch.max-files-per-run=%d)보다 많습니다"
                    .formatted(count, maxFilesPerRun));
        }
        if (!CONCURRENCY_OPTIONS.contains(concurrency)) {
            throw new IllegalArgumentException("동시성은 " + CONCURRENCY_OPTIONS + " 중 하나여야 합니다: " + concurrency);
        }
        MockSttLatency.Mode mode;
        try {
            mode = MockSttLatency.Mode.valueOf(latencyMode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지연 방식은 FIXED(고정) 또는 RANGE(범위)여야 합니다: " + latencyMode);
        }
        checkLatency("지연", latencyMs);
        if (mode == MockSttLatency.Mode.RANGE) {
            checkLatency("범위 최댓값", latencyMaxMs);
            if (latencyMaxMs < latencyMs) {
                throw new IllegalArgumentException("범위 최댓값(%dms)이 최솟값(%dms)보다 작습니다".formatted(latencyMaxMs, latencyMs));
            }
        }
        if (sttTimeoutMs != null && (sttTimeoutMs < MIN_TIMEOUT_MS || sttTimeoutMs > MAX_TIMEOUT_MS)) {
            throw new IllegalArgumentException("STT 타임아웃은 %d~%dms 이어야 합니다(비우면 적용 안 함): %d"
                    .formatted(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS, sttTimeoutMs));
        }
    }

    public MockSttLatency.Mode mode() {
        return MockSttLatency.Mode.valueOf(latencyMode);
    }

    /** 접견 건수 — 반반, 홀수면 전화가 하나 더. */
    public int meetCount() {
        return count / 2;
    }

    public int phoneCount() {
        return count - meetCount();
    }

    private static void checkLatency(String what, Long ms) {
        if (ms == null || ms < 0 || ms > MAX_LATENCY_MS) {
            throw new IllegalArgumentException("STT %s은(는) 0~%dms 이어야 합니다: %s".formatted(what, MAX_LATENCY_MS, ms));
        }
    }
}
