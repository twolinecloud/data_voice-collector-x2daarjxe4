package egovframework.voice.collector.sync;

import egovframework.voice.collector.config.MockDatasetState;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.VoiceTarget;
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
 * 전화 파일 Mock — ESB 대신 더미 WAV 를 수신 디렉터리에 떨군다.
 *
 * <p>브로커 모드가 MOCK 이면 함께 켜진다. "전 구간 Mock" 이 하나의 스위치로 움직이게 하려는 것이다
 * — 전화만 따로 스위치를 두면 조합이 늘어나기만 하고 쓰이지는 않는다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class MockPhoneFileProvider implements PhoneFileProvider {

    private final VoiceProperties props;
    private final EsbFileNamingPolicy namingPolicy;
    private final MockDatasetState dataset;

    @Override
    public void request(VoiceTarget target) {
        Path dir = Path.of(props.sync().phoneDir());
        Path file = dir.resolve(namingPolicy.expectedFileName(target, props.sync().namingPolicy()));
        try {
            Files.createDirectories(dir);
            byte[] wav = SilentWav.of(dataset.wavSeconds());
            Files.write(file, wav, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("[PhoneFile:MOCK] 더미 파일 생성 — {} ({} bytes)", file.getFileName(), wav.length);
        } catch (IOException e) {
            throw new UncheckedIOException("Mock 전화 파일 생성 실패: " + file, e);
        }
    }

    @Override
    public String mode() {
        return "MOCK";
    }
}
