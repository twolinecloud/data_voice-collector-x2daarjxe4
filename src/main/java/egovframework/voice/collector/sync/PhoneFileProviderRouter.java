package egovframework.voice.collector.sync;

import egovframework.voice.collector.config.VoiceModeState;
import egovframework.voice.collector.config.VoiceProperties.BrokerMode;
import egovframework.voice.collector.model.VoiceTarget;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 전화 파일 공급 라우터.
 *
 * <p>전화는 별도 스위치를 두지 않고 <b>브로커 모드를 따라간다</b>. "전 구간 Mock" 이 스위치
 * 하나로 움직이게 하려는 것이다 — 전화만 따로 두면 조합만 늘고 실제로 쓰이지는 않는다.</p>
 */
@Log4j2
@Primary
@Component
public class PhoneFileProviderRouter implements PhoneFileProvider {

    private final VoiceModeState state;
    private final Map<String, PhoneFileProvider> byMode = new HashMap<>();

    public PhoneFileProviderRouter(VoiceModeState state, List<PhoneFileProvider> impls) {
        this.state = state;
        for (PhoneFileProvider impl : impls) {
            if (impl instanceof PhoneFileProviderRouter) {
                continue;
            }
            byMode.put(impl.mode(), impl);
        }
        log.info("[PhoneFile] 사용 가능한 구현 — {}", byMode.keySet());
    }

    @Override
    public void request(VoiceTarget target) {
        PhoneFileProvider impl = byMode.get(mode());
        if (impl == null) {
            throw new IllegalStateException("전화 파일 공급 구현이 없다: " + mode());
        }
        impl.request(target);
    }

    @Override
    public String mode() {
        return state.broker() == BrokerMode.MOCK ? "MOCK" : "ESB";
    }
}
