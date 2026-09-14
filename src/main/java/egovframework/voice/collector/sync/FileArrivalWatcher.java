package egovframework.voice.collector.sync;

import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.VoiceFile;
import egovframework.voice.collector.model.VoiceKind;
import egovframework.voice.collector.model.VoiceTarget;
import egovframework.voice.collector.util.AudioFormatDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ESB 가 동기화해 준 파일이 도착했는지 보고, <b>쓰기가 끝났는지</b>까지 확인한 뒤 넘긴다.
 *
 * <p><b>왜 크기 안정성까지 보는가</b>: ESB Agent 가 파일을 쓰는 도중에 우리가 읽으면 잘린
 * 오디오를 STT 에 태우게 된다. 표준 A 는 {@code rcvtmp}(작업용) → {@code rcv}(완료) 로 옮기는
 * 디렉터리 규약을 두고 있어 원칙적으로는 {@code rcv} 에 있는 것만 보면 되지만, 그 규약이
 * 실제로 적용되는지 확정되지 않았고(Q7·Q14) 로컬 Mock 에는 적용되지 않는다.
 * 그래서 <b>크기가 일정 시간 그대로인지</b>를 한 번 더 본다 — 규약과 무관하게 성립하는 안전장치다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class FileArrivalWatcher {

    private final VoiceProperties props;
    private final EsbFileNamingPolicy namingPolicy;

    /** 파일명을 정책으로 추측해 기다린다. 공급자가 이름을 알려주지 않는 경우(전화)에 쓴다. */
    public VoiceFile await(VoiceTarget target) {
        return await(target, null);
    }

    /**
     * 대상에 해당하는 파일이 수신 디렉터리에 나타날 때까지 기다린다.
     *
     * @param hintFileName 파일을 만든 쪽이 알려준 실제 파일명. 있으면 <b>이것을 우선</b>한다.
     *                     <p>브로커는 자기가 정한 이름으로 파일을 만든다. 우리가 정책으로
     *                     추측한 이름과 다르면 영영 찾지 못하고 타임아웃이 난다 — Mock 브로커는
     *                     같은 정책을 써서 우연히 일치했을 뿐이다. 실제 XVARM 이 원본명을
     *                     어떻게 정하는지 모르므로(계획서 Q3), 알려준 이름이 있으면 그걸 믿는다.</p>
     * @return 도착·안정 확인된 파일
     * @throws IllegalStateException 타임아웃
     */
    public VoiceFile await(VoiceTarget target, String hintFileName) {
        Path dir = dirFor(target.kind());
        String name = StringUtils.hasText(hintFileName)
                ? hintFileName
                : namingPolicy.expectedFileName(target, props.sync().namingPolicy());
        Path file = dir.resolve(name);

        long deadline = System.currentTimeMillis() + props.sync().waitTimeoutSec() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file) && isStable(file)) {
                return describe(target, file);
            }
            sleep(Math.max(props.sync().stableCheckMs() / 2, 200));
        }
        throw new IllegalStateException("수신 파일 대기 타임아웃 — %s (%d초, dir=%s)"
                .formatted(name, props.sync().waitTimeoutSec(), dir));
    }

    /** 이미 와 있는지만 즉시 확인한다(대기 없음). */
    public boolean isArrived(VoiceTarget target) {
        Path file = dirFor(target.kind())
                .resolve(namingPolicy.expectedFileName(target, props.sync().namingPolicy()));
        return Files.exists(file);
    }

    private Path dirFor(VoiceKind kind) {
        return Path.of(kind == VoiceKind.MEET ? props.sync().meetDir() : props.sync().phoneDir());
    }

    /** 크기가 {@code stableCheckMs} 동안 변하지 않으면 쓰기가 끝난 것으로 본다. */
    private boolean isStable(Path file) {
        try {
            long first = Files.size(file);
            if (first <= 0) {
                return false;
            }
            sleep(props.sync().stableCheckMs());
            return Files.size(file) == first;
        } catch (IOException e) {
            // 아직 쓰는 중이면 예외가 날 수 있다 — 실패가 아니라 '아직'이다.
            return false;
        }
    }

    private VoiceFile describe(VoiceTarget target, Path file) {
        try {
            long size = Files.size(file);
            // 파일명이 .DAT 일 수 있으므로 매직 넘버를 먼저 본다.
            String format = AudioFormatDetector.detect(file, target.srcFileName());
            log.info("[Sync] 파일 확인 — {} ({} bytes, format={})", file.getFileName(), size, format);
            return new VoiceFile(target, file, size, format, false);
        } catch (IOException e) {
            throw new IllegalStateException("수신 파일 확인 실패: " + file, e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("파일 대기 중 인터럽트", e);
        }
    }
}
