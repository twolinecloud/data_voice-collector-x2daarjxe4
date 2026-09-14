package egovframework.voice.collector.sync;

import egovframework.voice.collector.model.VoiceTarget;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 * 전화 파일 — 운영 경로. <b>우리가 할 일이 없다.</b>
 *
 * <p>전화는 별도 서버에 있고, ESB 가 전화 전용 연계 프로바이더를 구성해 우리 스토리지로
 * 떨궈 준다(2026-09-11 회의). 요청·지시할 대상이 우리 쪽에 없으므로 기다리기만 한다.</p>
 *
 * <p><b>빈 구현이지만 클래스를 만드는 이유</b>: 이 자리를 비워 두면 "전화는 어떻게 오는가"라는
 * 질문이 코드 어디에도 남지 않는다. 나중에 ESB 구성이 바뀌어 우리가 뭔가 호출해야 하게 되면
 * 여기가 그 자리다.</p>
 */
@Log4j2
@Component
public class EsbPhoneFileProvider implements PhoneFileProvider {

    @Override
    public void request(VoiceTarget target) {
        log.debug("[PhoneFile:ESB] 대기 — ESB 프로바이더가 떨궈 준다 ({})", target.shortId());
    }

    @Override
    public String mode() {
        return "ESB";
    }
}
