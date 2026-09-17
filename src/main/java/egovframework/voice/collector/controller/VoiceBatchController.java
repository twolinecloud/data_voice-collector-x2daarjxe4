package egovframework.voice.collector.controller;

import egovframework.voice.collector.batch.IdempotencyGuard;
import egovframework.voice.collector.batch.VoiceBatchResult;
import egovframework.voice.collector.batch.VoiceBatchScheduler;
import egovframework.voice.collector.batch.VoiceCollectService;
import egovframework.voice.collector.broker.XvarmBrokerClient;
import egovframework.voice.collector.config.VoiceDirState;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.decrypt.DecryptService;
import egovframework.voice.collector.logging.LogCollectorClient;
import egovframework.voice.collector.model.BatchWindow;
import egovframework.voice.collector.model.VoiceKind;
import egovframework.voice.collector.source.BoramiSourceClient;
import egovframework.voice.collector.stt.SttClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 음성 수집 배치 운영·시연용 API.
 *
 * <p>스케줄러를 기다리지 않고 배치를 돌려볼 수 있게 한다. 다음 주 시연에서
 * "지금 한 번 돌려 보겠습니다"가 가능해야 하기 때문이다.</p>
 */
@Tag(name = "1. 음성 수집 배치", description = "보라미 음성(접견·통화) 수집 → 복호화 → STT → 처리 이력 적재")
@RestController
@RequestMapping(value = "/api/v1/voice", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class VoiceBatchController {

    private final VoiceCollectService service;
    private final VoiceBatchScheduler scheduler;
    private final IdempotencyGuard idempotency;
    private final VoiceProperties props;
    private final VoiceDirState dirs;
    private final BoramiSourceClient source;
    private final XvarmBrokerClient broker;
    private final egovframework.voice.collector.broker.RestXvarmBrokerClient restBroker;
    private final egovframework.voice.collector.sync.PhoneFileProvider phoneFileProvider;
    private final DecryptService decryptService;
    private final SttClient sttClient;
    private final LogCollectorClient logCollector;
    private final egovframework.voice.collector.config.VoiceModeState modeState;
    private final egovframework.voice.collector.config.FaultInjector faultInjector;
    private final egovframework.voice.collector.config.MockDatasetState dataset;
    private final egovframework.voice.collector.source.DbKindDetector db;
    private final egovframework.voice.collector.source.BoramiTableNames tables;

    @Operation(summary = "일배치 실행",
            description = """
                    전날 00시 ~ 오늘 00시 구간을 처리한다. 스케줄과 무관하게 즉시 실행한다.

                    - `kinds` 를 비우면 접견·전화 둘 다. `MEET` / `PHONE` 로 한 트랙만
                    - `test=true` 면 EXEC_ID 가 `…TST…` 로 채번되어 [테스트 데이터 초기화] 로 지울 수 있다
                    - 로그 컬렉터에 T1(배치) · T2(COLLECT·ANALYZE) · T4(파일별) 를 남기고,
                      STT 텍스트는 응답의 `outputDirs` 폴더(`{output}/{execId}/`)에 남긴다
                    """)
    @PostMapping("/batches/daily")
    public VoiceBatchResult daily(@RequestParam(required = false) List<VoiceKind> kinds,
                                  @RequestParam(defaultValue = "false") boolean test) {
        return service.run(BatchWindow.daily(LocalDateTime.now()), kinds, "MANUAL", test);
    }

    @Operation(summary = "주기배치 실행",
            description = """
                    지금으로부터 periodic-lag-min 분 전까지를 처리한다(기본 20분).

                    - `kinds=MEET` 접견만 · `kinds=PHONE` 전화만 · 비우면 둘 다
                    - `test=true` 면 EXEC_ID 가 `…TST…` 로 채번된다(시뮬레이터 기본)
                    - 실패한 건은 멱등 표식이 남지 않아 **다음 실행에서 다시 처리된다**(재처리 시나리오)
                    """)
    @PostMapping("/batches/periodic")
    public VoiceBatchResult periodic(@RequestParam(required = false) List<VoiceKind> kinds,
                                     @RequestParam(defaultValue = "false") boolean test) {
        return service.run(BatchWindow.periodic(LocalDateTime.now(), props.batch().periodicLagMin()),
                kinds, "MANUAL", test);
    }

    @Operation(summary = "구간 지정 실행 (재처리)",
            description = """
                    임의 시간창을 처리한다. Mock 모드에서는 시간창과 무관하게 고정 대상이 나온다.

                    실패 이력이 있는 구간을 다시 돌릴 때 쓴다 — 앞선 배치에서 실패한 건은 멱등 표식이 없어
                    다시 처리되고, 성공했던 건은 `건너뜀` 으로 잡힌다. 새 EXEC_ID 로 T1·T2·T4 가 따로 남는다.
                    """)
    @PostMapping("/batches/manual")
    public VoiceBatchResult manual(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) List<VoiceKind> kinds,
            @RequestParam(defaultValue = "false") boolean test) {
        return service.run(BatchWindow.manual(from, to), kinds, "MANUAL", test);
    }

    @Operation(summary = "현재 구성 조회",
            description = "5개 스위치(source/broker/phone/decrypt/stt)가 각각 어느 모드인지, 로그 컬렉터 연결이 살아 있는지 본다.")
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> modes = new LinkedHashMap<>();
        modes.put("source", source.mode());
        modes.put("broker", broker.mode());
        modes.put("phone", phoneFileProvider.mode());
        modes.put("decrypt", decryptService.mode());
        modes.put("stt", sttClient.mode());
        modes.put("xvarm", modeState.xvarm().name());

        Map<String, Object> logc = new LinkedHashMap<>();
        logc.put("enabled", logCollector.isEnabled());
        logc.put("baseUrl", blankToNull(logCollector.baseUrl()));

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("scheduleEnabled", props.batch().scheduleEnabled());
        batch.put("running", scheduler.isRunning());
        batch.put("dailyCron", props.batch().dailyCron());
        batch.put("periodicCron", props.batch().periodicCron());
        batch.put("periodicLagMin", props.batch().periodicLagMin());
        batch.put("maxFilesPerRun", props.batch().maxFilesPerRun());
        batch.put("speclMngSeCd", props.batch().speclMngSeCd());
        batch.put("retainSourceFile", props.batch().retainSourceFile());

        // 디렉터리 — 런타임 상태(시뮬레이터에서 바꾼 값)와 기동 설정값, 프리셋을 함께 내려준다.
        //   receiveMeet/receivePhone: 브로커·ESB 가 떨구는 곳 · work: 복호화 산출물·멱등 표식
        //   outputMeet/outputPhone: STT 결과가 {output}/{execId}/ 로 쌓이는 곳
        Map<String, Object> dirMap = new LinkedHashMap<>(dirs.snapshot());
        dirMap.put("namingPolicy", props.sync().namingPolicy());
        dirMap.put("configured", dirs.configured());
        dirMap.put("presets", dirs.presets());
        dirMap.put("suggestedBaseDir", VoiceDirState.suggestedBaseDir());
        dirMap.put("outputPattern", "{outputMeet|outputPhone}/{execId}/{건ID}.txt (+ .json 메타)");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modes", modes);
        out.put("switchLabels", switchLabels());
        // 지금 붙어 있는 DB — 개발계 DB 모드에서 "어디를 보는지" 를 화면에 그대로 보여준다
        Map<String, Object> dbInfo = new LinkedHashMap<>();
        dbInfo.put("kind", db.kind().name());
        dbInfo.put("label", db.label());
        dbInfo.put("url", db.url());
        dbInfo.put("tables", tables.describe());
        dbInfo.put("xvarmMode", modeState.xvarm().name());
        out.put("db", dbInfo);
        out.put("configuredModes", modeState.configured());
        out.put("logCollector", logc);
        out.put("batch", batch);
        out.put("dirs", dirMap);
        // 두 트랙의 단계·모드·실제 호출 대상을 서버가 직접 내려준다.
        //   화면에 하드코딩하면 설정을 바꿔도 그림이 그대로라 "무엇이 실제로 도는지"를
        //   화면만 보고는 알 수 없게 된다 — 실제로 그래서 전화 트랙이 브로커 스위치에
        //   끌려가는 것을 아무도 눈치채지 못했다.
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("brokerBaseUrl", modeState.brokerBaseUrl());
        endpoints.put("brokerBaseUrlConfigured", modeState.configuredBrokerBaseUrl());
        out.put("endpoints", endpoints);
        out.put("tracks", tracks());
        // "이 조합으로는 반드시 실패한다" 를 배치 전에 알린다.
        out.put("warnings", warnings());
        // 장애 주입이 켜진 줄 모르고 시연하면 실패 건수를 버그로 오해한다 — 항상 노출한다.
        out.put("chaos", faultInjector.snapshot());
        out.put("dataset", dataset.snapshot());
        return out;
    }

    /**
     * 접견·전화 두 트랙의 단계 정의.
     *
     * <p>각 단계마다 <b>지금 어떤 모드로 도는지</b>와 <b>실제로 무엇을 호출하는지</b>를 함께 싣는다.
     * 화면은 이걸 그대로 그리기만 하면 된다.</p>
     */
    private Map<String, Object> tracks() {
        Map<String, Object> meet = new LinkedHashMap<>();
        meet.put("label", "접견 (MEET)");
        meet.put("kind", "MEET");
        meet.put("desc", "보라미 DB 4단 조인으로 키를 얻어 XVARM 브로커에 추출을 지시하고, ESB FILE2FILE 로 받는다");
        meet.put("steps", List.of(
                sourceStep("보라미 조회",
                        "TB_IMSC_PTPR_DT → TB_RERD_TFIN_DS → TB_SMSM_CMFI_BS → XVARM.ASYSCONTENTELEMENT (4단 조인)", true),
                brokerStep(),
                step("ESB 수신", null, props.sync().namingPolicy().name(),
                        dirs.receiveMeet(),
                        "ESB FILE2FILE(P20) 이 동기화해 준 파일을 감시한다. 크기가 안정되어야 처리한다"),
                step("복호화", "decrypt", decryptService.mode(),
                        "R플레이어 로직 포팅",
                        "CMMN_FILE_ENC_YN='Y' 인 건만. 키 미수령(계획서 Q8)"),
                step("STT", "stt", sttClient.mode(),
                        sttEndpoint(),
                        "STT 텍스트 생성 후 T2 ANALYZE 마감. T4 에는 처리 상태만 남는다"),
                step("결과 저장", null, "FILE",
                        dirs.outputMeet() + "/{execId}/",
                        "STT 텍스트(.txt)와 메타(.json)를 배치 폴더에 남긴다 — 하류(비식별)가 여기서 읽어 간다")));

        Map<String, Object> phone = new LinkedHashMap<>();
        phone.put("label", "전화 (PHONE)");
        phone.put("kind", "PHONE");
        phone.put("desc", "단일 테이블 조회 후 ESB 전화 전용 연계 프로바이더가 떨궈 주는 파일을 받는다. XVARM·브로커를 타지 않는다");
        phone.put("steps", List.of(
                sourceStep("전화 DB 조회",
                        "TB_IMPH_UCDR_DS 단일 테이블. TELP_PCALL_RECRD_YN='Y' + 특이수용자", false),
                step("전화 파일 연계", "phone", phoneFileProvider.mode(),
                        dirs.receivePhone(),
                        "별도 서버의 파일을 ESB 전화 전용 프로바이더가 수신 디렉터리에 떨궈 준다. 우리는 대기만 한다"),
                step("복호화", "decrypt", decryptService.mode(),
                        "ARIA-128 / AES-256",
                        "KEY = TELP_RECRD_FILE_ID. 복호화 주체 미확정(계획서 Q13)"),
                step("STT", "stt", sttClient.mode(),
                        sttEndpoint(),
                        "TELP_STT_FLPTH_NM 에 기존 STT 가 있으면 재수행하지 않는다(계획서 Q1)"),
                step("결과 저장", null, "FILE",
                        dirs.outputPhone() + "/{execId}/",
                        "STT 텍스트(.txt)와 메타(.json)를 배치 폴더에 남긴다")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meet", meet);
        out.put("phone", phone);
        return out;
    }

    /**
     * 브로커 단계 — 모드 드롭다운에 더해 <b>주소를 화면에서 바꿀 수 있게</b> 정보를 싣는다.
     *
     * <p>모드만 REST 로 올리고 주소를 못 바꾸면 배치가 전건 실패한다. 실제로 그 상태로
     * 두 번 막혔다. 브로커가 사는 곳은 환경마다 다르므로(개발계 K8s 서비스명 / 로컬 localhost)
     * 스위치 옆에서 바로 고를 수 있어야 한다.</p>
     *
     * <p>화면은 프리셋을 라디오로 그리고, 현재 주소({@code url})와 같은 항목을 선택 상태로,
     * 기동 설정값({@code urlConfigured})과 같은 항목에 (Default) 를 붙인다 — 어느 것이
     * 재기동·모드 초기화 시 돌아가는 값인지 화면만 보고 알 수 있어야 한다.</p>
     */
    private Map<String, Object> brokerStep() {
        Map<String, Object> m = step("XVARM 브로커", "broker", broker.mode(),
                brokerEndpoint(),
                "POST /api/v1/xvarm/extract 로 추출 지시 후 상태 폴링. XVARM 이 보라미 임시 폴더에 파일을 만든다");
        // MOCK 일 때는 주소를 쓰지 않으므로 편집 UI 를 내보내지 않는다 — 안 쓰는 값을
        // 고치게 두면 "바꿨는데 왜 그대로지?" 가 된다.
        if (modeState.broker() == VoiceProperties.BrokerMode.REST) {
            m.put("urlKey", "broker");
            m.put("url", modeState.brokerBaseUrl());
            m.put("urlConfigured", modeState.configuredBrokerBaseUrl());
            m.put("urlPresets", props.broker().presets().stream()
                    .map(x -> {
                        Map<String, Object> pm = new LinkedHashMap<>();
                        pm.put("label", x.label());
                        pm.put("url", x.url());
                        return pm;
                    })
                    .toList());
        }
        return m;
    }

    private Map<String, Object> step(String label, String switchKey, String mode, String endpoint, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("switchKey", switchKey);   // null 이면 이 단계는 스위치로 바꾸는 것이 아니다
        m.put("mode", mode);
        m.put("endpoint", blankToNull(endpoint));
        m.put("note", note);
        return m;
    }

    private String sourceEndpoint() {
        return switch (modeState.source()) {
            case MOCK -> "내부 Mock 생성기 (외부 호출 없음)";
            case DIRECT_JDBC -> "JDBC — " + db.label() + " · " + tables.imscPtprDt();
            case ESB_HTTP2DB -> {
                String base = blankToNull(props.source().esbBaseUrl());
                String ifId = blankToNull(props.source().interfaceId());
                yield (base == null ? "ESB 주소 미설정" : base) + "/" + (ifId == null ? "{인터페이스ID 미정}" : ifId);
            }
        };
    }

    /** 드롭다운 라벨 — source: MOCK(로컬 H2) / 개발계 DB / 메타빌드(ESB) · xvarm: XVARM DB MOCK(개발계) / 실 XVARM DB. */
    private static Map<String, Map<String, String>> switchLabels() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("MOCK", "MOCK (로컬 H2)");
        source.put("DIRECT_JDBC", "개발계 DB");
        source.put("ESB_HTTP2DB", "메타빌드 (ESB)");
        Map<String, String> xvarm = new LinkedHashMap<>();
        xvarm.put("MOCK_DEV", "XVARM DB MOCK (개발계)");
        xvarm.put("REAL", "실 XVARM DB");
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("xvarm", xvarm);
        return m;
    }

    /**
     * 데이터 조회 단계 — 드롭다운(source)에 더해, <b>개발계 DB 모드일 때 XVARM 연동 라디오</b>를 붙인다.
     *
     * <p>2026-09-12 확인 기준 개발계 borami-db 에는 공통파일기본·XVARM 테이블이 없다. 라디오가 없으면 접견 4단 조인이
     * 조회 즉시 실패하는데 그 이유가 화면에 드러나지 않는다. MOCK_DEV(기본)는 우리가 만든 테이블을,
     * REAL 은 설정된 실 테이블을 조인한다.</p>
     */
    private Map<String, Object> sourceStep(String label, String note, boolean withXvarm) {
        Map<String, Object> m = step(label, "source", source.mode(), sourceEndpoint(), note);
        if (withXvarm && modeState.source() == VoiceProperties.SourceMode.DIRECT_JDBC) {
            m.put("radioKey", "xvarm");
            m.put("radioValue", modeState.xvarm().name());
            m.put("radioConfigured", props.source().xvarmMode().name());
            m.put("radioOptions", List.of(
                    radioOpt("MOCK_DEV", "XVARM DB MOCK (개발계)",
                            "개발계 DB 에 누락된 " + tables.smsmCmfiBs() + " · " + tables.asysContentElement()
                                    + " 를 자동 생성·시딩해 4단 조인이 돌게 한다"),
                    radioOpt("REAL", "실 XVARM DB",
                            "설정된 실 테이블(voice.source.schema.smsm/xvarm)을 직접 조인한다 — 없으면 조회가 실패한다")));
            m.put("radioNote", "지금 조인: " + tables.smsmCmfiBs() + " · " + tables.asysContentElement());
        }
        return m;
    }

    private static Map<String, Object> radioOpt(String value, String label, String note) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("value", value);
        o.put("label", label);
        o.put("note", note);
        return o;
    }

    private String brokerEndpoint() {
        if (modeState.broker() == VoiceProperties.BrokerMode.MOCK) {
            return "내부 Mock 브로커 — " + dirs.receiveMeet() + " 에 직접 생성";
        }
        String base = blankToNull(modeState.brokerBaseUrl());
        if (base == null) {
            // 주소를 경로와 이어붙이면 "브로커 주소 미설정/api/v1/..." 처럼 읽혀 설정된 것처럼 보인다.
            // 배치를 돌려야 비로소 실패하므로, 여기서 문제로 드러나게 한다.
            return "⚠ voice.broker.base-url 미설정 — REST 모드에서는 필수 (local 프로파일 또는 VOICE_BROKER_BASE_URL)";
        }
        return base + "/api/v1/xvarm/extract";
    }

    /** 이 조합으로는 배치가 반드시 실패한다는 것을 미리 알리는 경고들. */
    private List<String> warnings() {
        List<String> w = new java.util.ArrayList<>();
        if (modeState.broker() == VoiceProperties.BrokerMode.REST
                && blankToNull(modeState.brokerBaseUrl()) == null) {
            w.add("접견 트랙: 브로커가 REST 인데 주소가 비어 있다 — 접견 배치는 전건 실패한다. "
                    + "아래 [XVARM 브로커] 단계에서 주소 프리셋을 고르십시오(로컬 테스트는 로컬 PC).");
        }
        if (modeState.source() == VoiceProperties.SourceMode.ESB_HTTP2DB) {
            w.add("보라미 조회가 ESB_HTTP2DB 인데 연계가 아직 구현되지 않았다(계획서 Q2·Q15) — "
                    + "조회 즉시 실패한다. MOCK 또는 DIRECT_JDBC 로 두십시오.");
        }
        return w;
    }

    private String sttEndpoint() {
        if (modeState.stt() == VoiceProperties.SttMode.MOCK) {
            return "내부 Mock STT (고정 문구 반환)";
        }
        String base = blankToNull(props.stt().baseUrl());
        return base == null ? "NPU STT 주소 미설정" : base;
    }

    @Operation(summary = "브로커 연결 확인",
            description = """
                    XVARM 브로커에 붙어 상태를 물어봅니다. **배치를 돌리기 전 대조용**입니다.

                    확인할 것은 연결 여부보다 **브로커의 출력 디렉터리가 우리 접견 수신 폴더와
                    같은가**입니다. 두 경로가 어긋나면 브로커는 "추출 완료"를 돌려주는데 우리는
                    빈 폴더를 보며 수신 대기 타임아웃이 납니다 — 원인을 알려 주는 신호가 없습니다.

                    로컬에서 브로커를 띄울 때는 `local` 프로파일을 쓰거나
                    `BROKER_OUTPUT_DIR` 로 우리 `voice.sync.meet-dir` 과 맞추십시오.
                    """)
    @GetMapping("/broker/probe")
    public Map<String, Object> brokerProbe() {
        Map<String, Object> out = new LinkedHashMap<>(restBroker.probe());
        String meetDir = dirs.receiveMeet();
        out.put("collectorMeetDir", meetDir);
        out.put("brokerMode", broker.mode());

        String brokerDir = (String) out.get("brokerOutputDir");
        Boolean reachable = (Boolean) out.get("reachable");
        if (Boolean.TRUE.equals(reachable) && brokerDir != null) {
            boolean same = sameDir(brokerDir, meetDir);
            out.put("pathMatched", same);
            out.put("verdict", same
                    ? "출력 경로가 일치한다 — 접견 배치를 돌릴 수 있다"
                    : "⚠ 브로커 출력 경로와 우리 접견 수신 폴더가 다르다. "
                      + "브로커에 BROKER_OUTPUT_DIR=" + toAbs(meetDir) + " 를 주거나 local 프로파일로 띄울 것");
        } else {
            out.put("pathMatched", null);
            out.put("verdict", Boolean.TRUE.equals(reachable)
                    ? "브로커가 출력 경로를 알려주지 않았다"
                    : "브로커에 붙지 못했다 — 8082 포트로 떠 있는지 확인할 것");
        }
        return out;
    }

    /** 표기가 달라도 같은 폴더면 같다고 본다(상대·절대, 슬래시 방향, 대소문자). */
    private static boolean sameDir(String a, String b) {
        try {
            return java.nio.file.Path.of(a).toAbsolutePath().normalize()
                    .equals(java.nio.file.Path.of(b).toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    private static String toAbs(String p) {
        try {
            return java.nio.file.Path.of(p).toAbsolutePath().normalize()
                    .toString().replace(java.io.File.separatorChar, '/');
        } catch (Exception e) {
            return p;
        }
    }

    @Operation(summary = "멱등 표식 초기화",
            description = "처리 완료 표식을 지워 같은 대상을 다시 처리할 수 있게 한다. 시연 반복용.")
    @DeleteMapping("/idempotency")
    public Map<String, Object> clearIdempotency() {
        int cleared = idempotency.clearAll();
        return Map.of("cleared", cleared);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
