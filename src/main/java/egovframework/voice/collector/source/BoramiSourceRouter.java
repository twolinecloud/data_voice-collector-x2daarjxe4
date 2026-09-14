package egovframework.voice.collector.source;

import egovframework.voice.collector.config.VoiceModeState;
import egovframework.voice.collector.model.BatchWindow;
import egovframework.voice.collector.model.VoiceTarget;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 보라미 조회 라우터 — 현재 모드에 맞는 구현으로 <b>호출 시점에</b> 위임한다.
 *
 * <p>세 구현이 전부 빈으로 떠 있고, 어느 것을 쓸지는 {@link VoiceModeState} 가 정한다.
 * 그래서 재시작 없이 MOCK → DIRECT_JDBC → ESB_HTTP2DB 로 옮겨 갈 수 있다.</p>
 */
@Log4j2
@Primary
@Component
public class BoramiSourceRouter implements BoramiSourceClient {

    private final VoiceModeState state;
    private final Map<String, BoramiSourceClient> byMode = new HashMap<>();

    public BoramiSourceRouter(VoiceModeState state, List<BoramiSourceClient> impls) {
        this.state = state;
        for (BoramiSourceClient impl : impls) {
            if (impl instanceof BoramiSourceRouter) {
                continue;   // 자기 자신은 제외 — 무한 위임을 막는다
            }
            byMode.put(impl.mode(), impl);
        }
        log.info("[Source] 사용 가능한 구현 — {}", byMode.keySet());
    }

    private BoramiSourceClient current() {
        String mode = state.source().name();
        BoramiSourceClient impl = byMode.get(mode);
        if (impl == null) {
            throw new IllegalStateException("보라미 조회 구현이 없다: " + mode + " (등록: " + byMode.keySet() + ")");
        }
        return impl;
    }

    @Override
    public List<VoiceTarget> findMeetTargets(BatchWindow window, List<String> speclCodes, int limit) {
        return current().findMeetTargets(window, speclCodes, limit);
    }

    @Override
    public List<VoiceTarget> findPhoneTargets(BatchWindow window, List<String> speclCodes, int limit) {
        return current().findPhoneTargets(window, speclCodes, limit);
    }

    @Override
    public String mode() {
        return state.source().name();
    }
}
