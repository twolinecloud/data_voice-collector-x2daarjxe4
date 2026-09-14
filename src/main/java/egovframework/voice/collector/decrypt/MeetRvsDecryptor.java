package egovframework.voice.collector.decrypt;

import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.VoiceFile;
import egovframework.voice.collector.model.VoiceKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 접견 녹음 복호화 — R플레이어(HTML/JS)의 복호화 로직을 백엔드로 포팅해야 한다.
 *
 * <p>XVARM 은 파일을 임시 폴더로 <b>내려받기만</b> 하고, 실제 복호화는 화면에서 R플레이어가
 * 한다(2026-09-11 회의 {@code [00:01:43]}~{@code [00:02:09]}). 그래서 배치로 처리하려면
 * 그 JS 로직을 Java 로 옮겨야 한다.</p>
 *
 * <p><b>선행 자료가 아직 없다</b>(계획서 Q8). 회의에서 공유하기로 한 두 파일을 받지 못했다.</p>
 * <ul>
 *   <li>{@code rvs_key.txt} — 복호화 키</li>
 *   <li>{@code rvs-media-decryptor.html} — 복호화 알고리즘이 담긴 JS</li>
 * </ul>
 *
 * <p>키 파일 경로는 {@code voice.decrypt.rvs-key-path} 로 주입받도록 자리를 만들어 두었다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class MeetRvsDecryptor implements AudioDecryptor {

    private final VoiceProperties props;

    @Override
    public boolean supports(VoiceFile file) {
        return file.target().kind() == VoiceKind.MEET && file.target().encrypted();
    }

    @Override
    public VoiceFile decrypt(VoiceFile file) {
        String keyPath = props.decrypt().rvsKeyPath();
        throw new UnsupportedOperationException(
                "접견 복호화 미구현 — R플레이어 로직(rvs-media-decryptor.html)과 키(rvs_key.txt)를 아직 받지 못했다. "
                        + "계획서 11장 Q8 참조. 현재 설정된 키 경로: "
                        + (StringUtils.hasText(keyPath) ? keyPath : "(미설정)"));
    }

    @Override
    public String name() {
        return "MEET_RVS";
    }
}
