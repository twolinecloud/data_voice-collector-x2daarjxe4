package egovframework.voice.collector.batch;

import egovframework.voice.collector.config.VoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 배치가 끝난 뒤 <b>원본 음성이 남아 있지 않은지</b> 확인한다.
 *
 * <p><b>왜 확인까지 하는가</b>: 계획서 5.3-(4) 의 정책은 "복호화된 원본 음성은 STT 완료 즉시
 * 삭제" 다. 그런데 삭제는 {@code Files.deleteIfExists} 한 줄이라 실패해도 경고 로그만 남고
 * 지나간다 — 파일 잠금, 권한, 디스크 오류 어느 것이든 조용히 넘어간다.
 * 정책이 지켜졌다고 <b>말하는 것</b>과 지켜졌음을 <b>보이는 것</b>은 다르므로, 배치 결과에
 * 잔여 건수를 실어 화면에서 눈으로 확인할 수 있게 한다.</p>
 *
 * <p><b>세는 범위</b>: 수신 디렉터리(접견·전화)와 작업 디렉터리의 <b>바로 아래 파일</b>이다.
 * 하위 디렉터리는 세지 않는다 — 거기에는 멱등 표식({@code .processed})처럼 PII 가 아닌 것이
 * 의도적으로 남기 때문이다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class PiiResidueAuditor {

    private final VoiceProperties props;

    /**
     * 잔여 현황.
     *
     * @param meetFiles  접견 수신 디렉터리 잔여
     * @param phoneFiles 전화 수신 디렉터리 잔여
     * @param workFiles  작업 디렉터리 잔여(복호화 산출물)
     * @param total      합계
     * @param clean      전부 비어 있는가
     */
    public record Residue(int meetFiles, int phoneFiles, int workFiles, int total, boolean clean) {

        /**
         * 화면·로그에 그대로 쓸 한 줄.
         *
         * <p>{@code @JsonProperty} 가 필요하다 — record 는 <b>컴포넌트만</b> 자동 직렬화되고,
         * 이런 파생 메서드는 {@code get} 접두어도 없어 Jackson 이 건너뛴다. 빠지면 화면에
         * {@code undefined} 가 찍힌다(실제로 그랬다).</p>
         */
        @com.fasterxml.jackson.annotation.JsonProperty("message")
        public String message() {
            return clean
                    ? "작업 폴더 내 잔여 파일: 0건 (삭제 완료)"
                    : "작업 폴더 내 잔여 파일: %d건 (접견 %d · 전화 %d · 작업 %d) — 삭제되지 않았습니다"
                            .formatted(total, meetFiles, phoneFiles, workFiles);
        }
    }

    public Residue audit() {
        int meet = countFiles(props.sync().meetDir());
        int phone = countFiles(props.sync().phoneDir());
        int work = countFiles(props.sync().workDir());
        int total = meet + phone + work;
        Residue r = new Residue(meet, phone, work, total, total == 0);

        if (r.clean()) {
            log.info("[PII] {}", r.message());
        } else {
            // 정책 위반이므로 경고로 남긴다. 배치를 실패시키지는 않는다 —
            // 데이터는 이미 정상 처리됐고, 이건 뒤처리 문제다.
            log.warn("[PII] {}", r.message());
        }
        return r;
    }

    /** 디렉터리 <b>바로 아래</b> 일반 파일 수. 하위 디렉터리는 세지 않는다. */
    private int countFiles(String dir) {
        Path p = Path.of(dir);
        if (!Files.isDirectory(p)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(p)) {
            return (int) s.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            log.warn("[PII] 잔여 확인 실패 — {} ({})", dir, e.getMessage());
            return 0;
        }
    }
}
