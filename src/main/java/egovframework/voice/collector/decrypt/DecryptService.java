package egovframework.voice.collector.decrypt;

import egovframework.voice.collector.config.VoiceModeState;
import egovframework.voice.collector.config.VoiceProperties.DecryptMode;
import egovframework.voice.collector.model.VoiceFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 복호화 라우터 — 파일 하나에 어떤 복호화기를 쓸지 정한다.
 *
 * <p><b>판정 순서</b></p>
 * <ol>
 *   <li>{@code mode = SKIP} → 무조건 통과. 복호화 주체가 확정되기 전의 기본값이다(Q13).</li>
 *   <li>{@code encrypted = false} → 통과. {@code CMMN_FILE_ENC_YN='N'} 인 파일을
 *       복호화하면 멀쩡한 원본이 깨진다.</li>
 *   <li>그 외 → {@code supports()} 가 참인 첫 구현. 없으면 통과.</li>
 * </ol>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class DecryptService {

    private final VoiceModeState state;
    private final List<AudioDecryptor> decryptors;
    private final NoopDecryptor noop;

    public VoiceFile decrypt(VoiceFile file) {
        if (state.decrypt() == DecryptMode.SKIP) {
            return noop.decrypt(file);
        }
        if (!file.target().encrypted()) {
            log.debug("[Decrypt] 암호화되지 않은 파일 — 통과 ({})", file.path().getFileName());
            return noop.decrypt(file);
        }
        AudioDecryptor chosen = decryptors.stream()
                .filter(d -> !(d instanceof NoopDecryptor))
                .filter(d -> d.supports(file))
                .findFirst()
                .orElse(noop);
        log.info("[Decrypt] {} 적용 — {}", chosen.name(), file.path().getFileName());
        return chosen.decrypt(file);
    }

    /** 현재 모드(진단 표기용). */
    public String mode() {
        return state.decrypt().name();
    }
}
