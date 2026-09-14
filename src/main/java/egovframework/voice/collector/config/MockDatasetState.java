package egovframework.voice.collector.config;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mock 데이터셋 규모 — 대용량 부하 시험용.
 *
 * <p><b>왜 필요한가</b>: 실데이터 규모는 접견 약 1,300건(6.5GB) · 전화 약 1,300건이다
 * (2026-08-07 메타빌드 협의). 10건짜리 Mock 으로는 스트리밍·청크 처리가 실제로 메모리를
 * 지키는지 알 수 없다. 건수를 올려 놓고 힙이 버티는지 보려고 런타임 조절을 연다.</p>
 *
 * <p><b>파일 길이를 함께 줄인다</b>: 10,000건 × 2초 WAV 는 640MB 다. 디스크와 시간을 잡아먹는데
 * OOM 검증에 기여하지 않는다 — 우리가 보려는 것은 <b>건수에 비례해 메모리가 늘지 않는가</b>이지
 * 파일 크기가 아니다. 그래서 대량 모드에서는 0.5초짜리(16KB)로 만든다.</p>
 *
 * <p><b>처리 시간 주의</b>: 건당 파일 안정성 검사(<code>voice.sync.stable-check-ms</code>)가
 * 그대로 곱해진다. local 프로파일은 100ms 라 1,000건 ≈ 2분, 10,000건 ≈ 20분이 든다.
 * 시연에서는 1,000건을 권한다.</p>
 */
@Log4j2
@Component
public class MockDatasetState {

    /** 이 건수를 넘으면 대량 모드로 보고 파일을 짧게 만든다. */
    private static final int BULK_THRESHOLD = 200;

    private static final int DEFAULT_MEET = 5;
    private static final int DEFAULT_PHONE = 5;
    private static final int MAX_PER_KIND = 50_000;

    private volatile int meetCount = DEFAULT_MEET;
    private volatile int phoneCount = DEFAULT_PHONE;

    public int meetCount() {
        return meetCount;
    }

    public int phoneCount() {
        return phoneCount;
    }

    public int total() {
        return meetCount + phoneCount;
    }

    /** 대량 모드인가 — Mock 파일 길이를 줄일지 판단한다. */
    public boolean isBulk() {
        return total() > BULK_THRESHOLD;
    }

    /** Mock 이 만들 WAV 길이(초). */
    public int wavSeconds() {
        return isBulk() ? 1 : 2;
    }

    /**
     * 규모를 바꾼다.
     *
     * @throws IllegalArgumentException 음수이거나 상한을 넘는 경우
     */
    public void set(int meet, int phone) {
        validate("meet", meet);
        validate("phone", phone);
        this.meetCount = meet;
        this.phoneCount = phone;
        log.info("[MockDataset] 규모 변경 — 접견 {}건 · 전화 {}건 (대량모드={}, wav={}초)",
                meet, phone, isBulk(), wavSeconds());
    }

    public void reset() {
        set(DEFAULT_MEET, DEFAULT_PHONE);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("meetCount", meetCount);
        m.put("phoneCount", phoneCount);
        m.put("total", total());
        m.put("bulk", isBulk());
        m.put("wavSeconds", wavSeconds());
        return m;
    }

    private void validate(String name, int n) {
        if (n < 0) {
            throw new IllegalArgumentException(name + " 건수는 음수일 수 없다: " + n);
        }
        if (n > MAX_PER_KIND) {
            throw new IllegalArgumentException(
                    "%s 건수가 상한을 넘는다: %d (최대 %d)".formatted(name, n, MAX_PER_KIND));
        }
    }
}
