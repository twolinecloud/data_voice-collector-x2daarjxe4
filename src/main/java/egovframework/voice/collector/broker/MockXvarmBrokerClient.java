package egovframework.voice.collector.broker;

import egovframework.voice.collector.config.MockDatasetState;
import egovframework.voice.collector.config.VoiceDirState;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.VoiceTarget;
import egovframework.voice.collector.sync.EsbFileNamingPolicy;
import egovframework.voice.collector.util.SilentWav;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * XVARM 브로커 Mock — 실제 추출 대신 <b>재생 가능한 더미 WAV 파일</b>을 수신 디렉터리에 만든다.
 *
 * <p>ESB 파일 동기화까지 한꺼번에 흉내 낸다. 브로커가 보라미 임시 폴더에 떨구고 ESB 가 우리
 * 스토리지로 옮겨 주는 두 단계를, Mock 에서는 "수신 디렉터리에 바로 생성"으로 줄인다 —
 * 그래야 {@code FileArrivalWatcher} 가 실제와 같은 방식으로 파일을 집어 갈 수 있다.</p>
 *
 * <p><b>파일명을 표준 A 규약대로 만든다.</b> 수신 파일은
 * {@code 'R'_인터페이스ID_송신도메인_수신도메인_송신일시+난수.DAT} 형식이라
 * <b>원본 확장자가 사라진다</b>. 개발 중에 이 함정을 못 보고 지나가지 않도록 Mock 도 {@code .DAT} 로 만든다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class MockXvarmBrokerClient implements XvarmBrokerClient {

    private final VoiceProperties props;
    private final VoiceDirState dirs;
    private final EsbFileNamingPolicy namingPolicy;
    private final MockDatasetState dataset;

    @Override
    public ExtractResult extract(VoiceTarget target) {
        String requestId = "MOCKREQ-" + target.idempotencyKey();
        Path dir = dirs.receiveDir(target.kind());
        Path file = dir.resolve(namingPolicy.expectedFileName(target, props.sync().namingPolicy()));
        try {
            Files.createDirectories(dir);
            byte[] wav = SilentWav.of(dataset.wavSeconds());
            Files.write(file, wav, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("[Broker:MOCK] 더미 파일 생성 — {} ({} bytes)", file.getFileName(), wav.length);
            return new ExtractResult(requestId, file.toString(), wav.length);
        } catch (IOException e) {
            throw new UncheckedIOException("Mock 브로커 파일 생성 실패: " + file, e);
        }
    }

    @Override
    public String mode() {
        return "MOCK";
    }
}
