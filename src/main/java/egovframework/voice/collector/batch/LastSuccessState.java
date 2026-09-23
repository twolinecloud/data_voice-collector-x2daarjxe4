package egovframework.voice.collector.batch;

import egovframework.voice.collector.config.VoiceDirState;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 마지막으로 성공한 배치의 시각 — <b>[바로 실행]의 시작점</b>.
 *
 * <p><b>왜 필요한가</b>: 온디맨드 수집은 "마지막 성공 이후 지금까지" 를 훑는다. 그 기준점이
 * 없으면 매번 전날 하루치를 다시 돌거나(중복) 최근 10분만 보게 된다(누락).</p>
 *
 * <p><b>파일에 남긴다</b>: 메모리에만 두면 재기동할 때마다 기준점을 잃고, 그러면 첫 [바로 실행]이
 * 기본 창으로 떨어져 사람이 눈치채기 어려운 구멍이 생긴다. DB 테이블을 새로 만들 일이 아니고,
 * 로그 컬렉터의 T1 은 이 서비스가 읽는 API 가 아직 없다.</p>
 *
 * <p><b>ROOT 아래 {@code state/} 에 둔다 — 작업 폴더가 아니다.</b> 작업 폴더
 * ({@code {ROOT}/xvram/decoding})는 복호화 산출물이 오가는 곳이라 "배치가 끝나면 비어 있어야 한다"
 * 가 불변식이고, 실제로 그것을 검사하는 테스트가 있다. 거기에 상태 파일을 두면 그 불변식이 깨진다.</p>
 *
 * <p><b>성공했을 때만 갱신한다</b>: 실패한 배치로 기준점을 밀면 그 구간이 영영 수집되지 않는다.
 * 전건 실패(FAIL)는 물론이고 한 건도 처리하지 못한 배치도 기준점을 옮기지 않는다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class LastSuccessState {

    private static final String FILE = "last_success.txt";

    private final VoiceDirState dirs;

    private volatile LocalDateTime lastSuccessAt;
    private volatile String lastExecId;
    private volatile boolean loaded;

    /** 마지막 성공 시각. 기록이 없으면 {@code null}. */
    public synchronized LocalDateTime lastSuccessAt() {
        load();
        return lastSuccessAt;
    }

    /**
     * 배치가 끝난 뒤 호출한다 — <b>성공 건이 하나라도 있을 때만</b> 기준점을 민다.
     *
     * <p><b>{@code at} 은 '배치가 끝난 시각' 이 아니라 '훑은 창의 끝' 이다.</b> 기준점의 뜻은
     * "이 시각까지는 다 수집했다" 이므로, 일배치가 {@code [어제 00:00, 오늘 00:00)} 를 돌고
     * 지금 시각을 적으면 <b>보지도 않은 [오늘 00:00, 지금] 을 수집했다고 거짓말</b>하게 된다.
     * 실제로 그랬고, 그래서 [바로 실행] 의 창이 {@code [지금, 지금]} 으로 비어 아무것도
     * 처리하지 못했다.</p>
     *
     * <p><b>뒤로는 가지 않는다.</b> 주기배치가 11:00 까지 밀어 둔 뒤에 일배치가 돌면
     * 창의 끝(오늘 00:00)이 더 이르다. 그대로 적으면 이미 끝난 구간을 다시 훑게 된다 —
     * 멱등 표식이 막아 주긴 하지만 그만큼 헛돈다.</p>
     *
     * @param at      이 배치가 <b>훑은 창의 끝</b>
     * @param execId  그 배치의 실행 ID(추적용)
     * @param success 성공 건수
     */
    public synchronized void record(LocalDateTime at, String execId, int success) {
        if (success <= 0) {
            log.debug("[LastSuccess] 성공 0건 — 기준점을 옮기지 않는다 (execId={})", execId);
            return;
        }
        load();
        if (at == null || (lastSuccessAt != null && !at.isAfter(lastSuccessAt))) {
            log.debug("[LastSuccess] 기준점이 앞으로 가지 않는다 — 현재 {} · 이번 창 끝 {} (execId={})",
                    lastSuccessAt, at, execId);
            return;
        }
        this.lastSuccessAt = at;
        this.lastExecId = execId;
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, at + "\n" + (execId == null ? "" : execId) + "\n", StandardCharsets.UTF_8);
            log.info("[LastSuccess] 기준점 갱신 — {} (execId={}, 성공 {}건)", at, execId, success);
        } catch (IOException e) {
            // 기록에 실패해도 배치는 성공이다. 다음 [바로 실행]이 기본 창으로 떨어질 뿐이다.
            log.warn("[LastSuccess] 기준점 기록 실패 — {} ({})", f, e.getMessage());
        }
    }

    /** 화면·API 용 한 장. */
    public synchronized Map<String, Object> snapshot() {
        load();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastSuccessAt", lastSuccessAt == null ? null : lastSuccessAt.toString());
        m.put("lastExecId", lastExecId);
        m.put("file", file().toString().replace('\\', '/'));
        return m;
    }

    private Path file() {
        return Path.of(dirs.baseDir(), "state", FILE);
    }

    /** 처음 읽을 때 한 번만 파일에서 복원한다. 깨진 값이면 없는 것으로 본다. */
    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            String[] lines = Files.readString(f, StandardCharsets.UTF_8).split("\\R");
            if (lines.length > 0 && !lines[0].isBlank()) {
                lastSuccessAt = LocalDateTime.parse(lines[0].trim());
            }
            if (lines.length > 1 && !lines[1].isBlank()) {
                lastExecId = lines[1].trim();
            }
            log.info("[LastSuccess] 기준점 복원 — {} (execId={})", lastSuccessAt, lastExecId);
        } catch (Exception e) {
            log.warn("[LastSuccess] 기준점을 읽지 못했다 — {} ({}). 없는 것으로 본다", f, e.getMessage());
            lastSuccessAt = null;
            lastExecId = null;
        }
    }
}
