package egovframework.voice.collector.stt;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * STT MOCK 의 <b>가상 지연</b>과 <b>타임아웃</b> — NPU 응답 시간을 흉내 낸다.
 *
 * <p>기본은 {@code voice.stt.mock-latency-ms}(기본 0 — 지금까지처럼 즉시 응답)다. 성능 테스트가 도는 동안만
 * {@link #apply} 로 덮어쓰고 끝나면 {@link #clear} 로 되돌린다.</p>
 *
 * <ul>
 *   <li><b>고정</b> — 매 건 같은 지연(예: 500ms)</li>
 *   <li><b>범위</b> — 건마다 {@code [min, max]} 균등 난수(예: 1,000~5,000ms)</li>
 *   <li><b>타임아웃</b> — 뽑힌 지연이 이 값을 넘으면 <b>타임아웃 시간만큼만 기다린 뒤</b>
 *       {@link SttTimeoutException}. 실제 NPU 호출의 읽기 타임아웃은 최소 60초라
 *       ({@code RestTemplateConfig}) 수 초짜리 지연으로는 재현되지 않는다</li>
 * </ul>
 *
 * <p>NPU 모드에서는 쓰지 않는다 — 실제 응답 시간이 곧 지연이다.</p>
 */
@Log4j2
@Component
public class MockSttLatency {

    public enum Mode { FIXED, RANGE }

    /** 한 번에 적용되는 설정 — 여러 워커가 동시에 읽으므로 통째로 바꾼다. */
    private record Setting(Mode mode, long minMs, long maxMs, long timeoutMs, String source) {}

    private final Setting base;
    private volatile Setting current;

    public MockSttLatency(@Value("${voice.stt.mock-latency-ms:0}") long baseLatencyMs) {
        long ms = Math.max(0L, baseLatencyMs);
        this.base = new Setting(Mode.FIXED, ms, ms, 0L, "설정(voice.stt.mock-latency-ms)");
        this.current = base;
    }

    /**
     * 성능 테스트 설정을 건다.
     *
     * @param timeoutMs 0 이하면 타임아웃을 흉내 내지 않는다
     */
    public void apply(Mode mode, long minMs, long maxMs, long timeoutMs) {
        long lo = Math.max(0L, minMs);
        long hi = mode == Mode.RANGE ? Math.max(lo, maxMs) : lo;
        this.current = new Setting(mode, lo, hi, Math.max(0L, timeoutMs), "성능 테스트");
        log.info("[STT:MOCK] 가상 지연 — {} {}ms{}{}", mode == Mode.RANGE ? "범위" : "고정", lo,
                mode == Mode.RANGE ? "~" + hi + "ms" : "",
                timeoutMs > 0 ? " · 타임아웃 " + timeoutMs + "ms" : "");
    }

    /** 설정값으로 되돌린다. */
    public void clear() {
        this.current = base;
    }

    /** 이번 건의 지연(ms). */
    public long draw() {
        Setting s = current;
        if (s.maxMs() <= s.minMs()) {
            return s.minMs();
        }
        return ThreadLocalRandom.current().nextLong(s.minMs(), s.maxMs() + 1);
    }

    /** 타임아웃(ms). 0 이면 흉내 내지 않는다. */
    public long timeoutMs() {
        return current.timeoutMs();
    }

    public Map<String, Object> snapshot() {
        Setting s = current;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", s.mode().name());
        m.put("minMs", s.minMs());
        m.put("maxMs", s.maxMs());
        m.put("timeoutMs", s.timeoutMs());
        m.put("source", s.source());
        return m;
    }
}
