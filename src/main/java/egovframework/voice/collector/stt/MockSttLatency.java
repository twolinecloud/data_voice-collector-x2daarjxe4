package egovframework.voice.collector.stt;

import egovframework.voice.collector.model.VoiceKind;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * STT MOCK 의 <b>가상 지연</b>과 <b>타임아웃</b> — NPU 변환 시간을 흉내 낸다.
 *
 * <p><b>접견·전화를 따로 둔다.</b> 음성 길이가 다르다 — 전화는 평균 통화 5분(변환 약 120초),
 * 접견은 평균 15분(변환 약 180초). 같은 지연을 주면 워커가 어느 트랙에 묶이는지가 보이지 않는다.</p>
 *
 * <p>기본은 {@code voice.stt.mock-latency-ms}(기본 0 — 지금까지처럼 즉시 응답, 두 트랙 같은 값)다.
 * 성능 시험이 도는 동안만 {@link #apply} 로 덮어쓰고 끝나면 {@link #clear} 로 되돌린다.</p>
 *
 * <ul>
 *   <li><b>고정</b> — 트랙마다 매 건 같은 지연</li>
 *   <li><b>범위</b> — 트랙 기준값의 {@code ±jitter%} 안에서 건마다 균등 난수(예: 180초 ±50% → 90~270초)</li>
 *   <li><b>타임아웃</b> — 뽑힌 지연이 이 값을 넘으면 <b>타임아웃 시간만큼만 기다린 뒤</b>
 *       {@link SttTimeoutException}. 실제 NPU 호출의 읽기 타임아웃({@code RestTemplateConfig})과 따로 논다</li>
 * </ul>
 *
 * <p>NPU 모드에서는 쓰지 않는다 — 실제 응답 시간이 곧 지연이다.</p>
 */
@Log4j2
@Component
public class MockSttLatency {

    public enum Mode { FIXED, RANGE }

    /** 한 번에 적용되는 설정 — 여러 워커가 동시에 읽으므로 통째로 바꾼다. */
    private record Setting(Mode mode, long meetMs, long phoneMs, int jitterPercent, long timeoutMs, String source) {}

    private final Setting base;
    private volatile Setting current;

    public MockSttLatency(@Value("${voice.stt.mock-latency-ms:0}") long baseLatencyMs) {
        long ms = Math.max(0L, baseLatencyMs);
        this.base = new Setting(Mode.FIXED, ms, ms, 0, 0L, "설정(voice.stt.mock-latency-ms)");
        this.current = base;
    }

    /**
     * 성능 시험 설정을 건다.
     *
     * @param jitterPercent 범위일 때 기준값 대비 ± 폭(0~100). 고정이면 무시한다
     * @param timeoutMs     0 이하면 타임아웃을 흉내 내지 않는다
     */
    public void apply(Mode mode, long meetMs, long phoneMs, int jitterPercent, long timeoutMs) {
        int j = mode == Mode.RANGE ? Math.max(0, Math.min(100, jitterPercent)) : 0;
        this.current = new Setting(mode, Math.max(0L, meetMs), Math.max(0L, phoneMs), j, Math.max(0L, timeoutMs), "성능 시험");
        log.info("[STT:MOCK] 가상 지연 — 접견 {}ms · 전화 {}ms{}{}", meetMs, phoneMs,
                j > 0 ? " (±" + j + "%)" : " (고정)", timeoutMs > 0 ? " · 타임아웃 " + timeoutMs + "ms" : "");
    }

    /** 설정값으로 되돌린다. */
    public void clear() {
        this.current = base;
    }

    /** 이번 건의 지연(ms) — 트랙 기준값, 범위면 ±폭 안의 난수. */
    public long draw(VoiceKind kind) {
        Setting s = current;
        long b = kind == VoiceKind.MEET ? s.meetMs() : s.phoneMs();
        if (s.jitterPercent() <= 0 || b <= 0) {
            return b;
        }
        long lo = Math.max(0L, Math.round(b * (100 - s.jitterPercent()) / 100d));
        long hi = Math.round(b * (100 + s.jitterPercent()) / 100d);
        return hi <= lo ? lo : ThreadLocalRandom.current().nextLong(lo, hi + 1);
    }

    /** 타임아웃(ms). 0 이면 흉내 내지 않는다. */
    public long timeoutMs() {
        return current.timeoutMs();
    }

    public Map<String, Object> snapshot() {
        Setting s = current;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", s.mode().name());
        m.put("meetMs", s.meetMs());
        m.put("phoneMs", s.phoneMs());
        m.put("jitterPercent", s.jitterPercent());
        m.put("timeoutMs", s.timeoutMs());
        m.put("source", s.source());
        return m;
    }
}
