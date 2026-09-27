package egovframework.voice.collector.perf;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 건별 단계 시간 합계 — <b>성능 테스트가 도는 동안에만</b> 센다.
 *
 * <p>배치 코드에 넣는 것은 두 줄뿐이다 — {@code long m = meter.start();} 와 {@code meter.add(단계, m);}.
 * 꺼져 있으면 {@link #start()} 가 0 을 돌려주고 {@link #add} 는 아무것도 하지 않는다. 운영 배치에서는
 * 시계를 읽지도 않는다.</p>
 *
 * <p>워커가 여럿이어도 된다 — {@link LongAdder} 로 더한다. 한 번에 한 성능 테스트만 돌므로
 * (배치도 한 번에 하나) 전역 하나로 충분하다.</p>
 */
@Component
public class PerfStageMeter {

    private volatile boolean active;
    private final Map<PerfStage, LongAdder> nanos = new EnumMap<>(PerfStage.class);
    private final Map<PerfStage, LongAdder> counts = new EnumMap<>(PerfStage.class);

    public PerfStageMeter() {
        for (PerfStage s : PerfStage.values()) {
            nanos.put(s, new LongAdder());
            counts.put(s, new LongAdder());
        }
    }

    /** 새로 센다. */
    public void begin() {
        for (PerfStage s : PerfStage.values()) {
            nanos.get(s).reset();
            counts.get(s).reset();
        }
        active = true;
    }

    public void end() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    /** 단계 시작 시각 — 꺼져 있으면 0(시계를 읽지 않는다). */
    public long start() {
        return active ? System.nanoTime() : 0L;
    }

    /** {@link #start()} 로 받은 시각부터 지금까지를 그 단계에 더한다. */
    public void add(PerfStage stage, long startedNanos) {
        if (!active || startedNanos == 0L) {
            return;
        }
        nanos.get(stage).add(System.nanoTime() - startedNanos);
        counts.get(stage).increment();
    }

    /** 단계별 평균(ms)·건수 — 화면 막대 그래프 순서대로. */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>(PerfStage.values().length);
        for (PerfStage s : PerfStage.values()) {
            long n = counts.get(s).sum();
            long total = nanos.get(s).sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.name());
            m.put("label", s.label());
            m.put("count", n);
            m.put("avgMs", n == 0 ? 0d : round1(total / 1_000_000d / n));
            m.put("totalMs", Math.round(total / 1_000_000d));
            out.add(m);
        }
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
