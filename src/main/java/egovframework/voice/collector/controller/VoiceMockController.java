package egovframework.voice.collector.controller;

import egovframework.voice.collector.batch.IdempotencyGuard;
import egovframework.voice.collector.batch.PiiResidueAuditor;
import egovframework.voice.collector.config.FaultInjector;
import egovframework.voice.collector.config.MockDatasetState;
import egovframework.voice.collector.config.VoiceModeState;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.BatchWindow;
import egovframework.voice.collector.model.VoiceKind;
import egovframework.voice.collector.model.VoiceTarget;
import egovframework.voice.collector.source.BoramiSourceClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 시뮬레이터·시연 보조 API — <b>운영에서는 차단한다</b>.
 *
 * <p>커넥터도 같은 방식으로 시연용 API 를 한 애플리케이션에 담고 운영 배포 시 경로를 막는다.
 * 배포 시 {@code /api/v1/mock/**} 를 인그레스·게이트웨이에서 차단하면 된다.</p>
 *
 * <p>여기 있는 것은 <b>파괴적 초기화</b>다. 멱등 표식과 수신 디렉터리의 파일을 지운다.
 * Mock 모드에서만 의미가 있으므로, 실물 모드에서는 호출해도 실제 데이터를 건드리지 않도록
 * 삭제 범위를 <b>우리가 만든 작업 디렉터리 안</b>으로 한정했다.</p>
 */
@Tag(name = "9. 시뮬레이터 (Mock 전용)",
        description = "시연 반복을 위한 초기화·미리보기. 운영에서는 /api/v1/mock/** 를 차단한다")
@Log4j2
@RestController
@RequestMapping(value = "/api/v1/mock", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class VoiceMockController {

    private final VoiceProperties props;
    private final BoramiSourceClient source;
    private final IdempotencyGuard idempotency;
    private final VoiceModeState modeState;
    private final MockDatasetState dataset;
    private final FaultInjector faultInjector;
    private final PiiResidueAuditor residueAuditor;
    private final egovframework.voice.collector.logging.LogCollectorClient logCollector;

    @Operation(summary = "Mock 데이터 초기화",
            description = """
                    시연을 처음부터 다시 하기 위한 초기화입니다. 세 가지를 지웁니다.

                    1. **멱등 표식** — 이걸 지워야 같은 대상을 다시 처리할 수 있습니다
                    2. **수신 디렉터리의 파일** — 접견·전화 수신 폴더
                    3. **작업 디렉터리의 산출물** — 복호화 결과·Mock 기존 STT 텍스트

                    지운 뒤 현재 조회되는 대상 수를 함께 돌려줍니다.
                    """)
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        int markers = idempotency.clearAll();
        int meetFiles = deleteFilesIn(props.sync().meetDir());
        int phoneFiles = deleteFilesIn(props.sync().phoneDir());
        int workFiles = deleteFilesIn(Path.of(props.sync().workDir(), "mock_source_stt").toString());

        List<VoiceTarget> targets = previewTargets();
        log.info("[Mock] 초기화 — 표식 {}건, 접견파일 {}건, 전화파일 {}건, 작업파일 {}건",
                markers, meetFiles, phoneFiles, workFiles);

        Map<String, Object> cleared = new LinkedHashMap<>();
        cleared.put("idempotencyMarkers", markers);
        cleared.put("meetFiles", meetFiles);
        cleared.put("phoneFiles", phoneFiles);
        cleared.put("workFiles", workFiles);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", cleared);
        out.put("targetCount", targets.size());
        out.put("message", "초기화 완료 — 대상 %d건이 다시 처리 가능한 상태입니다".formatted(targets.size()));
        return out;
    }

    @Operation(summary = "테스트 데이터 초기화 (TST 연쇄 삭제)",
            description = """
                    **시뮬레이터에서 돌린 시험 기록을 통째로 지웁니다.**

                    시뮬레이터에서 실행한 배치는 `JOB_ID=TEST_BATCH` 로 열려 EXEC_ID 의
                    작업코드 자리가 **`TST`** 가 됩니다 (예: `20260914TST001`).
                    운영 배치는 `VOC`(음성)·`STR`(정형)·`EXT`(외부)라 **섞이지 않습니다.**

                    두 가지를 지웁니다.

                    1. **로그 컬렉터 이력** — `DELETE /api/v1/logs/test-data` 를 호출합니다.
                       컬렉터가 `JOB_ID='TEST_BATCH'` 인 T1 과 하위 T2~T8 을 FK 안전 순서로
                       연쇄 삭제합니다. 삭제 SQL 에 작업코드 조건이 박혀 있어 운영 배치는
                       어떤 경우에도 걸리지 않습니다.
                    2. **로컬 산출물** — 멱등 표식·수신 파일·작업 파일 (Mock 초기화와 동일)

                    커넥터가 하류(PPP)로 이미 보낸 건은 여기서 지울 수 없습니다 — 우리 소관이 아닙니다.
                    """)
    @DeleteMapping("/test-data")
    public Map<String, Object> deleteTestData() {
        Map<String, Object> out = new LinkedHashMap<>();

        // ① 로그 컬렉터 이력 (TST)
        com.fasterxml.jackson.databind.JsonNode logs = logCollector.deleteTestData();
        if (logs == null) {
            out.put("logCollector", logCollector.isEnabled()
                    ? "삭제 호출 실패 — 컬렉터 응답 없음"
                    : "미연동 — 지울 원격 이력이 없다");
        } else {
            out.put("logCollector", logs);
        }

        // ② 로컬 산출물
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("idempotencyMarkers", idempotency.clearAll());
        local.put("meetFiles", deleteFilesIn(props.sync().meetDir()));
        local.put("phoneFiles", deleteFilesIn(props.sync().phoneDir()));
        local.put("workFiles", deleteFilesIn(Path.of(props.sync().workDir(), "mock_source_stt").toString()));
        out.put("local", local);

        out.put("testJobId", props.batch().testJobId());
        out.put("message", "테스트(TST) 데이터 초기화 완료 — 운영 배치(VOC/STR/EXT)는 건드리지 않았습니다");
        log.info("[Mock] 테스트 데이터 초기화 — 컬렉터={} 로컬={}", out.get("logCollector"), local);
        return out;
    }

    @Operation(summary = "처리 대상 미리보기",
            description = """
                    배치를 실행하지 않고 **지금 조회되는 대상**만 확인합니다.
                    시연에서 "이번에 이만큼 처리됩니다"를 먼저 보여줄 때 씁니다.

                    이미 처리된 건(`processed=true`)은 배치를 돌려도 건너뜁니다.
                    """)
    @GetMapping("/targets")
    public Map<String, Object> targets(
            @RequestParam(required = false) List<VoiceKind> kinds) {
        List<VoiceTarget> found = previewTargets(kinds);
        List<Map<String, Object>> rows = new ArrayList<>(found.size());
        for (VoiceTarget t : found) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", t.kind().name());
            r.put("kindLabel", t.kind().label());
            r.put("key", t.idempotencyKey());
            r.put("fileName", t.srcFileName());
            r.put("encrypted", t.encrypted());
            r.put("hasSourceStt", t.hasSourceStt());
            r.put("processed", idempotency.isProcessed(t));
            rows.add(r);
        }
        long pending = rows.stream().filter(r -> !((Boolean) r.get("processed"))).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceMode", source.mode());
        out.put("total", rows.size());
        out.put("pending", pending);
        out.put("alreadyProcessed", rows.size() - pending);
        out.put("targets", rows);
        return out;
    }

    @Operation(summary = "수신 디렉터리 현황",
            description = "ESB(또는 Mock)가 떨궈 놓은 파일이 실제로 있는지 확인합니다.")
    @GetMapping("/files")
    public Map<String, Object> files() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meet", listFiles(props.sync().meetDir()));
        out.put("phone", listFiles(props.sync().phoneDir()));
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  동적 모드 전환 — 서버 재시작 없이 MOCK ↔ REAL
    // ══════════════════════════════════════════════════════════════════════

    @Operation(summary = "현재 모드 조회",
            description = "4개 스위치의 현재 값과 기동 시 설정값을 함께 돌려줍니다.")
    @GetMapping("/modes")
    public Map<String, Object> getModes() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", modeState.snapshot());
        out.put("configured", modeState.configured());
        out.put("allowed", Map.of(
                "source", List.of("MOCK", "DIRECT_JDBC", "ESB_HTTP2DB"),
                "broker", List.of("MOCK", "REST"),
                "decrypt", List.of("SKIP", "REAL"),
                "stt", List.of("MOCK", "NPU")));
        return out;
    }

    @Operation(summary = "모드 변경 (단건)",
            description = """
                    스위치 하나를 **즉시** 바꿉니다. 재시작이 필요 없습니다.

                    - `source` : MOCK / DIRECT_JDBC / ESB_HTTP2DB
                    - `broker` : MOCK / REST — 전화 파일 공급도 이 값을 따라갑니다
                    - `decrypt`: SKIP / REAL
                    - `stt`    : MOCK / NPU

                    변경은 **이 프로세스에만** 남습니다. 재기동하면 설정값으로 돌아갑니다 —
                    운영에서 실수로 바꾼 모드가 영구히 남지 않게 하려는 것입니다.
                    """)
    @PutMapping("/modes/{name}")
    public Map<String, Object> setMode(@PathVariable String name, @RequestParam String value) {
        String before = modeState.set(name, value);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("switch", name);
        out.put("before", before);
        out.put("after", value.toUpperCase());
        out.put("current", modeState.snapshot());
        return out;
    }

    @Operation(summary = "모드 초기화", description = "기동 시 설정값(application.yml / 환경변수)으로 되돌립니다.")
    @PostMapping("/modes/reset")
    public Map<String, Object> resetModes() {
        modeState.resetToConfigured();
        return Map.of("current", modeState.snapshot());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  대용량 부하 — 스트리밍·청크 처리가 메모리를 지키는지 본다
    // ══════════════════════════════════════════════════════════════════════

    @Operation(summary = "Mock 데이터 규모 설정",
            description = """
                    생성할 Mock 대상 건수를 바꿉니다. **OOM 방어 검증용**입니다.

                    실데이터 규모는 접견 약 1,300건(6.5GB) · 전화 약 1,300건입니다.
                    건수를 올려도 힙이 늘지 않아야 합니다 — 파일은 스트리밍으로 다루고,
                    커넥터 전송은 청크로 나눠 보내기 때문입니다.

                    **처리 시간 주의**: 건당 파일 안정성 검사(`voice.sync.stable-check-ms`)가
                    그대로 곱해집니다. local 기준 1,000건 ≈ 2분, 10,000건 ≈ 20분입니다.
                    200건을 넘으면 Mock WAV 를 1초짜리로 줄여 디스크를 아낍니다.
                    """)
    @PutMapping("/dataset")
    public Map<String, Object> setDataset(
            @RequestParam(defaultValue = "5") int meet,
            @RequestParam(defaultValue = "5") int phone) {
        dataset.set(meet, phone);
        Map<String, Object> out = new LinkedHashMap<>(dataset.snapshot());
        out.put("message", "대상 %d건으로 설정했습니다. [Mock 데이터 초기화] 후 배치를 실행하세요"
                .formatted(dataset.total()));
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  장애 주입 — 한 건이 실패해도 배치가 끝까지 도는지
    // ══════════════════════════════════════════════════════════════════════

    @Operation(summary = "장애 시뮬레이션 설정",
            description = """
                    STT·커넥터 전송 구간에 **의도적으로** 실패와 지연을 섞습니다.

                    확인하려는 것: 파일 1건이 터져도 배치가 죽지 않고 `failCnt` 만 올리며
                    끝까지 도는가(Fault Tolerance). 1,300건 배치에서 한 건 때문에 전체가 멈추면
                    나머지를 전부 다시 처리해야 합니다.

                    실패와 지연은 **독립 판정**입니다 — 지연되면서 실패할 수도 있습니다.
                    """)
    @PutMapping("/chaos")
    public Map<String, Object> setChaos(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer failPercent,
            @RequestParam(required = false) Integer delayPercent,
            @RequestParam(required = false) Long delayMs) {
        faultInjector.configure(enabled, failPercent, delayPercent, delayMs);
        return faultInjector.snapshot();
    }

    @Operation(summary = "PII 잔여 파일 확인",
            description = """
                    수신·작업 디렉터리에 원본 음성이 남아 있는지 셉니다.
                    배치 후 **0건**이어야 정책(계획서 5.3-(4): STT 완료 즉시 삭제)이 지켜진 것입니다.
                    """)
    @GetMapping("/pii-residue")
    public Map<String, Object> piiResidue() {
        PiiResidueAuditor.Residue r = residueAuditor.audit();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meetFiles", r.meetFiles());
        out.put("phoneFiles", r.phoneFiles());
        out.put("workFiles", r.workFiles());
        out.put("total", r.total());
        out.put("clean", r.clean());
        out.put("message", r.message());
        return out;
    }

    /**
     * 잘못된 입력은 <b>400</b> 으로 돌려준다.
     *
     * <p>모드 이름·값, Mock 건수, 장애 확률은 전부 호출자가 준 값이다. 그대로 두면
     * {@code IllegalArgumentException} 이 500(서버 오류)으로 나가 "우리 서버가 고장났다"는
     * 잘못된 신호를 준다. 무엇이 잘못됐고 무엇이 허용되는지도 메시지에 이미 담겨 있다.</p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException e) {
        log.warn("[Mock] 잘못된 요청 — {}", e.getMessage());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", "BAD_REQUEST");
        out.put("message", e.getMessage());
        return out;
    }

    /**
     * 설정이 실제 환경과 맞지 않아 생긴 실패는 <b>사유를 그대로 보여 준다</b>.
     *
     * <p>기본 처리에 맡기면 화면에 "Internal Server Error" 만 남아, 스키마 설정이 틀린 것인지
     * 테이블이 없는 것인지 로그를 뒤져야 알 수 있다. 시연 중에는 그럴 여유가 없다.</p>
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleIllegalState(IllegalStateException e) {
        log.error("[Mock] 설정·환경 오류 — {}", e.getMessage());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", "CONFIGURATION_ERROR");
        out.put("message", e.getMessage());
        return out;
    }

    // ── 내부 ───────────────────────────────────────────────────────────────

    private List<VoiceTarget> previewTargets() {
        return previewTargets(null);
    }

    private List<VoiceTarget> previewTargets(List<VoiceKind> kinds) {
        List<VoiceKind> want = (kinds == null || kinds.isEmpty())
                ? List.of(VoiceKind.MEET, VoiceKind.PHONE) : kinds;
        LocalDateTime now = LocalDateTime.now();
        BatchWindow window = BatchWindow.manual(now.minusDays(2), now.plusDays(1));

        List<VoiceTarget> all = new ArrayList<>();
        int limit = props.batch().maxFilesPerRun();
        List<String> codes = props.batch().speclMngSeCd();
        if (want.contains(VoiceKind.MEET)) {
            all.addAll(source.findMeetTargets(window, codes, limit));
        }
        if (want.contains(VoiceKind.PHONE)) {
            all.addAll(source.findPhoneTargets(window, codes, limit));
        }
        return all;
    }

    /**
     * 디렉터리 안의 <b>파일만</b> 지운다(하위 디렉터리는 건드리지 않는다).
     *
     * <p>재귀 삭제를 쓰지 않는 이유: 설정이 잘못돼 엉뚱한 경로가 들어오면 피해가 걷잡을 수 없다.
     * 초기화에 필요한 것은 평평한 파일 목록뿐이다.</p>
     */
    private int deleteFilesIn(String dir) {
        Path p = Path.of(dir);
        if (!Files.isDirectory(p)) {
            return 0;
        }
        int[] count = {0};
        try (Stream<Path> s = Files.list(p)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                try {
                    Files.delete(f);
                    count[0]++;
                } catch (IOException e) {
                    log.warn("[Mock] 파일 삭제 실패 — {} ({})", f.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[Mock] 디렉터리 조회 실패 — {} ({})", dir, e.getMessage());
        }
        return count[0];
    }

    private List<Map<String, Object>> listFiles(String dir) {
        Path p = Path.of(dir);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!Files.isDirectory(p)) {
            return rows;
        }
        try (Stream<Path> s = Files.list(p)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", f.getFileName().toString());
                try {
                    r.put("size", Files.size(f));
                } catch (IOException e) {
                    r.put("size", -1);
                }
                rows.add(r);
            });
        } catch (IOException e) {
            log.warn("[Mock] 디렉터리 조회 실패 — {} ({})", dir, e.getMessage());
        }
        return rows;
    }
}
