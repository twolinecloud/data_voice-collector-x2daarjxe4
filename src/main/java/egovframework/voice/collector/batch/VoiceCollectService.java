package egovframework.voice.collector.batch;

import egovframework.voice.collector.broker.BrokerOutputCheck;
import egovframework.voice.collector.broker.XvarmBrokerClient;
import egovframework.voice.collector.config.VoiceDirState;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.decrypt.DecryptService;
import egovframework.voice.collector.logging.LogCollectorClient;
import egovframework.voice.collector.model.BatchWindow;
import egovframework.voice.collector.model.FileProcOutcome;
import egovframework.voice.collector.model.ProcStatus;
import egovframework.voice.collector.model.SttResult;
import egovframework.voice.collector.model.VoiceFile;
import egovframework.voice.collector.model.VoiceKind;
import egovframework.voice.collector.model.VoiceTarget;
import egovframework.voice.collector.source.BoramiSourceClient;
import egovframework.voice.collector.stt.SttClient;
import egovframework.voice.collector.stt.SttOutputStore;
import egovframework.voice.collector.sync.FileArrivalWatcher;
import egovframework.voice.collector.util.InmatePidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 음성 수집 배치의 본체 — <b>대상 선별 → 파일 확보 → 복호화 → STT → 출력 저장 → 처리 이력 적재</b>.
 *
 * <p><b>이 서비스의 범위는 STT 처리와 내부 저장·감시까지다.</b> STT 텍스트를 외부 서비스로
 * 전송하지 않는다(비식별 커넥터 연동은 아키텍처 변경으로 제외됐다). 텍스트는 배치 단위 출력 폴더
 * ({@code {output}/{execId}/})에 남기고, 로그 테이블 INSERT 는 로그 컬렉터 API 로만 한다.</p>
 *
 * <p><b>T2 단계는 커넥터 호출 여부와 무관하게 남긴다.</b> 비정형 체인
 * (COLLECT 1 → ANALYZE 2 → DEIDENT 3 → SEND 4) 중 이 서비스가 도는 세 단계를 기록한다 —
 * {@code COLLECT}(파일 확보·복호화)는 배치 시작에 열고, {@code ANALYZE}(STT)는 첫 STT 가 시작될 때,
 * {@code SEND}(출력 저장)는 첫 저장 직전에 연다. 셋 다 배치 끝에서 한 번에 마감한다.
 * {@code DEIDENT} 는 이 서비스의 범위가 아니다(비식별 커넥터 연동 제외).</p>
 *
 * <p><b>SEND 를 ANALYZE 에서 떼어 낸 이유</b>: 예전에는 STT 와 출력 저장이 한 단계였다. 그래서
 * "STT 는 됐는데 저장에서 깨진" 건과 "STT 자체가 깨진" 건이 T2 에서 같은 줄로 보였고, 파이프라인
 * 로그가 ANALYZE 에서 끊겨 결과물이 어디까지 갔는지 읽히지 않았다. 이 서비스는 STT 텍스트를 외부로
 * 전송하지 않지만, 다음 단계(제논)가 배치 폴더에서 집어 가는 것이 인계 방식이므로 그 구간을 SEND 로 둔다.</p>
 *
 * <p><b>건별 격리</b>: 한 건이 실패해도 배치를 멈추지 않는다. 1,300건을 도는 배치에서
 * 한 파일이 깨졌다고 전체가 중단되면 나머지 1,299건을 다시 처리해야 한다.
 * 실패는 T4 에 사유와 함께 남기고 다음 건으로 넘어간다.</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class VoiceCollectService {

    /** 로컬 임시 EXEC_ID — 컬렉터 규칙(yyyyMMdd + 작업코드3 + 회차3)의 자리를 맞춘다. */
    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HHmmss");

    private final VoiceProperties props;
    private final VoiceDirState dirs;
    private final BoramiSourceClient source;
    private final XvarmBrokerClient broker;
    private final egovframework.voice.collector.sync.PhoneFileProvider phoneFileProvider;
    private final FileArrivalWatcher watcher;
    private final DecryptService decryptService;
    private final SttClient sttClient;
    private final SttOutputStore outputStore;
    private final LogCollectorClient logCollector;
    private final IdempotencyGuard idempotency;
    private final InmatePidGenerator pidGenerator;
    private final BatchProgress progress;
    private final egovframework.voice.collector.transfer.AgentConnectorClient agentConnector;
    private final LastSuccessState lastSuccess;
    private final StageFaultState stageFault;
    private final egovframework.voice.collector.stt.SttTempStore sttTemp;

    /**
     * 실패한 건의 중간 산출물을 남길지 — <b>Resume 의 전제</b>.
     *
     * <p>켜면 복호화 오디오({@code {ROOT}/xvram/decoding})와 전사 결과({@code stt_temp/{execId}})가
     * 남아 다음 재처리가 그 단계부터 이어서 간다. ⚠ 복호화 오디오는 평문이고 그 안에 성명·
     * 주민등록번호가 들어 있다 — 운영에서 PII 를 즉시 지워야 하면 {@code false} 로 내린다.
     * 꺼도 재처리는 동작한다: 보존물이 없으면 앞 단계부터 다시 한다.</p>
     */
    @org.springframework.beans.factory.annotation.Value("${voice.resume.keep-on-failure:true}")
    private boolean keepOnFailure;

    /**
     * 배치를 1회 실행한다.
     *
     * @param window    처리할 시간창
     * @param kinds     처리할 종류(접견/전화). 비면 둘 다
     * @param triggerBy 실행 주체(SCHEDULER / 사용자)
     */
    public VoiceBatchResult run(BatchWindow window, List<VoiceKind> kinds, String triggerBy) {
        return run(window, kinds, triggerBy, false);
    }

    /**
     * 배치를 1회 실행한다.
     *
     * @param testRun 시뮬레이터에서 돌린 시험인가. {@code true} 면 EXEC_ID 가 {@code ...TST...} 로
     *                채번되어 나중에 [테스트 데이터 초기화] 로 통째로 지울 수 있다.
     *                스케줄러가 도는 실제 배치는 항상 {@code false} 다 — 시험 기록과 섞이면
     *                정합성 대사(T1.SUCCESS_CNT == Σ T3·T4·T5)가 의미를 잃는다.
     */
    public VoiceBatchResult run(BatchWindow window, List<VoiceKind> kinds, String triggerBy, boolean testRun) {
        return run(window, kinds, triggerBy, testRun, ResumeMode.FULL, null);
    }

    /**
     * 배치를 1회 실행한다 — <b>이어서 하기(Resume)</b> 포함.
     *
     * @param resume     어느 단계부터 이어서 할지. {@link ResumeMode#FULL} 이면 종전대로 전부 한다
     * @param fromExecId 보존물을 어느 배치에서 찾을지. 비면 가장 최근 것
     */
    public VoiceBatchResult run(BatchWindow window, List<VoiceKind> kinds, String triggerBy, boolean testRun,
                                ResumeMode resume, String fromExecId) {
        long startedAt = System.currentTimeMillis();
        List<VoiceKind> targets = (kinds == null || kinds.isEmpty())
                ? List.of(VoiceKind.MEET, VoiceKind.PHONE) : kinds;

        String collectorExecId = openBatch(window, triggerBy, testRun);
        String execId = collectorExecId != null ? collectorExecId : localExecId(testRun);
        log.info("[Batch] 시작 — execId={} {} kinds={}{}", execId, window, targets,
                testRun ? "  [시험 실행 — TST 로 채번, 초기화로 삭제 가능]" : "");
        // 트랙별로 따로 찍는다. 두 시나리오는 연동 주체가 달라서 한 줄에 섞으면
        // 어느 모드가 어느 경로에 걸린 것인지 읽히지 않는다.
        if (targets.contains(VoiceKind.MEET)) {
            log.info("[Batch]   접견 트랙 — 조회={} → 브로커={} → 복호화={} → STT={} → 출력={}",
                    source.mode(), broker.mode(), decryptService.mode(), sttClient.mode(),
                    dirs.outputDir(VoiceKind.MEET, execId));
        }
        if (targets.contains(VoiceKind.PHONE)) {
            log.info("[Batch]   전화 트랙 — 조회={} → 파일연계={} → 복호화={} → STT={} → 출력={}  (XVARM 경유 없음)",
                    source.mode(), phoneFileProvider.mode(), decryptService.mode(), sttClient.mode(),
                    dirs.outputDir(VoiceKind.PHONE, execId));
        }

        // ── T2 ① COLLECT — 파일 확보·복호화. 배치 시작에 연다 ────────────────────
        RunContext ctx = new RunContext(execId);
        ctx.fromExecId = fromExecId;
        ctx.collectStepId = logCollector.createStep(execId, (short) 1, FileProcOutcome.STEP_COLLECT);

        List<VoiceTarget> found = findTargets(window, targets);
        List<FileProcOutcome> outcomes = new ArrayList<>(found.size());

        // 화면 진행률 — 대상 수가 확정된 지금부터 센다. 배치 REST 는 동기라 이것 없이는
        // 수 분짜리 배치가 도는지 죽었는지 화면에서 알 수 없다.
        progress.begin(execId, window.toString(), found.size());
        // 부분 성공은 확률이 아니라 건수다 — 대상 수가 정해진 지금 몇 건을 떨어뜨릴지 확정한다.
        stageFault.beginBatch(found.size());
        boolean canceled = false;
        try {
            for (VoiceTarget t : found) {
                // 중단은 건과 건 사이에서만 받는다 — 처리 중인 건을 끊으면 복호화 원본이 남거나
                //   반쯤 쓴 산출물이 생긴다. 남은 건은 '중단됨' 으로 세어 합계를 맞춘다.
                if (progress.isCancelRequested()) {
                    canceled = true;
                    log.warn("[Batch] 중단 요청 — execId={} · 처리 {}건 · 남은 {}건은 건너뜀으로 남긴다",
                            execId, outcomes.size(), found.size() - outcomes.size());
                    for (VoiceTarget rest : found.subList(outcomes.size(), found.size())) {
                        outcomes.add(FileProcOutcome.skipped(rest, "중단됨 — 사용자가 배치를 멈췄습니다"));
                        progress.finishFile(ProcStatus.SKIPPED);
                    }
                    break;
                }
                progress.startFile(t);
                FileProcOutcome o = processOne(t, ctx, resume);
                progress.finishFile(o.status());
                outcomes.add(o);
            }
        } finally {
            progress.end();
        }

        int success = (int) outcomes.stream().filter(FileProcOutcome::isSuccess).count();
        int skipped = (int) outcomes.stream().filter(o -> o.status() == ProcStatus.SKIPPED).count();
        int fail = outcomes.size() - success - skipped;

        // ── T2 마감 — COLLECT 는 확보 건수로, ANALYZE 는 STT 건수로 ───────────────
        List<VoiceBatchResult.StepLog> steps = new ArrayList<>(3);
        long collectIn = outcomes.size() - skipped;
        long collectErr = outcomes.stream().filter(o -> o.failedAt(FileProcOutcome.STEP_COLLECT)).count();
        long collectOut = collectIn - collectErr;
        steps.add(finishStep(ctx.collectStepId, FileProcOutcome.STEP_COLLECT,
                collectIn, collectOut, collectErr, ctx.collectMs));

        // STT 가 한 건이라도 시작됐을 때만 ANALYZE 행이 있다 — 전건 확보 실패면 STT 단계는 돌지 않은 것이다.
        long analyzeErr = outcomes.stream().filter(o -> o.failedAt(FileProcOutcome.STEP_ANALYZE)).count();
        if (ctx.analyzeStarted) {
            // ANALYZE 는 STT 까지다 — 나간 건수는 STT 를 통과해 SEND 로 넘어간 수.
            steps.add(finishStep(ctx.analyzeStepId, FileProcOutcome.STEP_ANALYZE,
                    collectOut, collectOut - analyzeErr, analyzeErr, ctx.analyzeMs));
        }
        // SEND — STT 를 통과한 건이 한 번이라도 저장을 시도했을 때만 행이 있다.
        if (ctx.sendStarted) {
            long sendErr = outcomes.stream().filter(o -> o.failedAt(FileProcOutcome.STEP_SEND)).count();
            steps.add(finishStep(ctx.sendStepId, FileProcOutcome.STEP_SEND,
                    collectOut - analyzeErr, success, sendErr, ctx.sendMs));
        }

        // ── 이관 — 1차 저장({ROOT}/xenon/…)이 끝난 뒤 에이전트 커넥터로 넘긴다 ──────────
        //   실패해도 배치를 실패로 돌리지 않는다. 우리 구간의 산출물은 이미 디스크에 있고,
        //   못 넘긴 것은 폴더를 보고 다시 넘기면 된다 — 이관 때문에 STT 를 다시 도는 것은 낭비다.
        var transfer = agentConnector.send(execId, ctx.toTransfer);
        if (transfer.enabled() && !transfer.success()) {
            log.warn("[Batch] 커넥터 이관 미완료 — execId={} {} (1차 저장은 정상)", execId, transfer.message());
        }

        // T4 — 파일 1건 = 1행. 정합성 대사(T1.SUCCESS_CNT == Σ T3·T4·T5)의 근거다.
        logCollector.createFileProcs(execId, toFileProcReqs(outcomes));

        Map<String, String> outputDirs = new LinkedHashMap<>();
        for (VoiceKind k : targets) {
            outputDirs.put(k.name(), dirs.outputDir(k, execId).toString().replace('\\', '/'));
        }

        long elapsedMs = System.currentTimeMillis() - startedAt;
        VoiceBatchResult result = new VoiceBatchResult(execId, collectorExecId != null, window.toString(),
                found.size(), success, fail, skipped, elapsedMs, outputDirs, steps, outcomes, canceled);

        // [바로 실행]의 시작점 — 성공 건이 있을 때만 민다. 실패한 배치로 기준점을 옮기면
        // 그 구간이 영영 수집되지 않는다.
        //   중단도 같다. 160건 중 43건만 하고 멈췄는데 기준점을 '지금'으로 옮기면,
        //   손대지 않은 117건은 다음 [바로 실행]의 창에서 빠져 아무도 다시 보지 않는다.
        if (!canceled) {
            lastSuccess.record(LocalDateTime.now().withNano(0), execId, success);
        }

        logCollector.finishBatch(execId, result.execStsCd(), elapsedSec(startedAt),
                (long) found.size(), (long) success, (long) fail,
                result.errMsg() == null ? null : LogCollectorClient.FileProcReq.errStackOf(result.errMsg()));
        log.info("[Batch] 종료{} — {}", canceled ? "(중단됨)" : "", result.summary());
        return result;
    }

    // ── 단계별 ────────────────────────────────────────────────────────────

    /** 배치 1회 동안 단계 기록이 들고 다니는 상태 — 단계 ID 와 구간별 누적 시간. */
    private static final class RunContext {
        final String execId;
        String fromExecId;
        String collectStepId;
        String analyzeStepId;
        String sendStepId;
        boolean analyzeStarted;
        boolean sendStarted;
        long collectMs;
        long analyzeMs;
        long sendMs;
        /** 이관에 넘길 산출물 — 1차 저장이 끝난 건만 담는다. */
        final List<egovframework.voice.collector.transfer.AgentConnectorClient.Output> toTransfer = new ArrayList<>();

        RunContext(String execId) {
            this.execId = execId;
        }
    }

    /**
     * T1 을 열어 EXEC_ID 를 받아 온다. 컬렉터가 없으면 null — 호출자가 로컬 임시 ID 를 만든다.
     */
    private String openBatch(BatchWindow window, String triggerBy, boolean testRun) {
        String jobId = testRun ? props.batch().testJobId() : props.batch().jobId();
        return logCollector.createBatch(jobId, props.batch().dataTypeCd(), window.label(), triggerBy);
    }

    /**
     * 컬렉터가 없을 때의 임시 EXEC_ID — 같은 자리에 작업코드가 오게 만든다.
     *
     * <p>컬렉터 채번 규칙: yyyyMMdd(8) + 작업코드(3) + 회차(3) → 9~11번째 자리가 작업코드다.
     * 테스트 데이터 삭제가 그 자리(TST)를 보므로 로컬 ID 도 자리를 맞춘다.</p>
     */
    private static String localExecId(boolean testRun) {
        LocalDateTime now = LocalDateTime.now();
        String local = LOCAL_DATE.format(now) + (testRun ? "TST" : "VOC") + LOCAL_TIME.format(now);
        log.info("[Batch] 로그 컬렉터 미연동 — 로컬 임시 execId 사용: {}", local);
        return local;
    }

    /** ANALYZE 단계를 연다 — 첫 STT 직전에 한 번. 컬렉터는 같은 단계 재호출을 기존 행으로 돌려보내므로 안전하다. */
    private void beginAnalyze(RunContext ctx) {
        if (ctx.analyzeStarted) {
            return;
        }
        ctx.analyzeStarted = true;
        ctx.analyzeStepId = logCollector.createStep(ctx.execId, (short) 2, FileProcOutcome.STEP_ANALYZE);
        log.info("[Batch] T2 ANALYZE 시작 — stepLogId={}", ctx.analyzeStepId == null ? "(미연동)" : ctx.analyzeStepId);
    }

    /** SEND 단계를 연다 — 첫 출력 저장 직전에 한 번. ANALYZE 와 같은 방식이다. */
    private void beginSend(RunContext ctx) {
        if (ctx.sendStarted) {
            return;
        }
        ctx.sendStarted = true;
        // 비정형 체인의 4번 칸(COLLECT 1 · ANALYZE 2 · DEIDENT 3 · SEND 4). DEIDENT 는 이 서비스의 범위가 아니다.
        ctx.sendStepId = logCollector.createStep(ctx.execId, (short) 4, FileProcOutcome.STEP_SEND);
        log.info("[Batch] T2 SEND 시작 — stepLogId={}", ctx.sendStepId == null ? "(미연동)" : ctx.sendStepId);
    }

    /** 단계를 마감하고 결과 요약을 돌려준다. 컬렉터 미연동이면 요약만 만든다. */
    private VoiceBatchResult.StepLog finishStep(String stepLogId, String stepTypeCd,
                                                long in, long out, long err, long elapsedMs) {
        String sts = err == 0 ? "SUCCESS" : (out == 0 ? "FAIL" : "PARTIAL");
        int sec = (int) (elapsedMs / 1000);
        boolean logged = stepLogId != null;
        if (logged) {
            logCollector.finishStep(stepLogId, sts, sec, in, out, err, null);
        }
        log.info("[Batch] T2 {} 마감 — {} in={} out={} err={} ({}초){}", stepTypeCd, sts, in, out, err, sec,
                logged ? "" : "  [컬렉터 미연동 — 적재 안 됨]");
        return new VoiceBatchResult.StepLog(stepTypeCd, stepLogId, sts, in, out, err, sec, logged);
    }

    private List<VoiceTarget> findTargets(BatchWindow window, List<VoiceKind> kinds) {
        List<VoiceTarget> all = new ArrayList<>();
        int limit = props.batch().maxFilesPerRun();
        List<String> codes = props.batch().speclMngSeCd();
        if (kinds.contains(VoiceKind.MEET)) {
            log.info("[Track:MEET] ① 보라미 조회 — 4단 조인(특이수용자→녹취파일→공통파일→XVARM) via {}",
                    source.mode());
            all.addAll(source.findMeetTargets(window, codes, limit));
        }
        if (kinds.contains(VoiceKind.PHONE)) {
            log.info("[Track:PHONE] ① 보라미 조회 — 단일 테이블(TB_IMPH_UCDR_DS) via {}", source.mode());
            all.addAll(source.findPhoneTargets(window, codes, limit));
        }
        if (all.size() > limit) {
            log.warn("[Batch] 대상이 상한을 넘어 잘라낸다 — {}건 → {}건", all.size(), limit);
            return all.subList(0, limit);
        }
        return all;
    }

    /**
     * 한 건을 끝까지 처리한다. 실패해도 예외를 밖으로 던지지 않는다.
     *
     * <p>두 구간으로 나눠 잰다 — <b>COLLECT</b>(파일 확보·복호화)와 <b>ANALYZE</b>(STT·출력 저장).
     * 어느 구간에서 실패했는지가 T2 의 단계별 건수를 가른다.</p>
     *
     * <p><b>원본 삭제는 {@code finally} 에서 한다.</b> STT 가 실패하면 정상 경로를 타지 않는데,
     * 그 자리에서 지우지 않으면 <b>복호화된 음성이 디스크에 남는다</b>. 정책은 성공·실패를
     * 가리지 않고 즉시 삭제다(계획서 5.3-(4)).</p>
     */
    /**
     * 한 건을 처리한다 — 수집(+복호화) → 분석(STT) → 전송(최종 저장).
     *
     * <p><b>이어서 하기</b>: {@code resume} 가 가리키는 단계부터 시작하되, 그 단계가 기대하는
     * 보존물이 없으면 앞 단계로 내려간다. 보존물은 빠른 길일 뿐 유일한 길이 아니다.</p>
     *
     * <p><b>중간 산출물</b>: 성공하면 지우고, 실패하면 남긴다({@code voice.resume.keep-on-failure}).
     * 남겨야 다음 재처리가 그 단계부터 갈 수 있다. ⚠ 복호화 오디오는 평문이라 PII 가 디스크에
     * 머문다 — 운영에서 즉시 지워야 하면 설정을 내린다.</p>
     */
    private FileProcOutcome processOne(VoiceTarget target, RunContext ctx, ResumeMode resume) {
        long t0 = System.currentTimeMillis();

        if (idempotency.isProcessed(target)) {
            log.debug("[Batch] 이미 처리됨 — {}", target.shortId());
            return FileProcOutcome.skipped(target, "이미 처리된 건");
        }

        // 이 건이 디스크에 만든 것들 — 성공했을 때만 지운다.
        List<Path> toClean = new ArrayList<>(2);
        String step = FileProcOutcome.STEP_COLLECT;
        boolean ok = false;
        // STT 가 쓸 오디오 — 실패했을 때 이것을 보존해야 재처리가 STT 부터 갈 수 있다.
        //   복호화 산출물에만 기대면 안 된다: 복호화가 SKIP 이면 산출물이 아예 없고,
        //   그러면 '분석 장애 -> 이어서 하기' 시나리오가 성립하지 않는다.
        Path readyAudio = null;
        try {
            SttResult stt = null;
            long fileSize = 0L;
            VoiceFile plain = null;

            // ── SEND 부터 이어서 — 보존된 전사 결과를 찾는다 ──────────────────────
            if (resume == ResumeMode.FROM_SEND) {
                stt = sttTemp.find(target, ctx.fromExecId).orElse(null);
                if (stt == null) {
                    log.info("[Resume] 보존된 전사 결과가 없다 — {} · STT 부터 다시 한다", target.shortId());
                    resume = ResumeMode.FROM_ANALYZE;
                } else {
                    log.info("[Resume] 전사 결과 재사용 — {} ({}자) · 수집·복호화·STT 생략",
                            target.shortId(), stt.charCount());
                }
            }

            // ── ANALYZE 부터 이어서 — 보존된 복호화 오디오를 찾는다 ────────────────
            if (stt == null && resume == ResumeMode.FROM_ANALYZE) {
                plain = findPreservedAudio(target).orElse(null);
                if (plain == null) {
                    log.info("[Resume] 보존된 복호화 오디오가 없다 — {} · 수집부터 다시 한다", target.shortId());
                } else {
                    fileSize = plain.sizeBytes();
                    log.info("[Resume] 복호화 오디오 재사용 — {} · 수집·복호화 생략", plain.path().getFileName());
                }
            }

            // ── COLLECT — 파일 확보 · 복호화 ────────────────────────────────────
            if (stt == null && plain == null) {
                if (stageFault.shouldFail(StageFaultState.Stage.COLLECT)) {
                    throw StageFaultState.fault(StageFaultState.Stage.COLLECT, "수집 단계 장애 주입");
                }
                SttResult reused = target.hasSourceStt() ? tryReadSourceStt(target) : null;
                if (reused == null) {
                    VoiceFile file = acquire(target, ctx.execId);
                    toClean.add(file.path());
                    fileSize = file.sizeBytes();

                    plain = decryptService.decrypt(file);
                    if (!plain.path().equals(file.path())) {
                        toClean.add(plain.path());   // 복호화가 새 파일을 만든 경우
                    }
                } else {
                    stt = reused;   // 보라미가 이미 가진 STT(계획서 Q1) — 오디오를 만질 필요가 없다
                }
            }
            if (plain != null) {
                readyAudio = plain.path();
            }
            long tCollected = System.currentTimeMillis();
            ctx.collectMs += tCollected - t0;

            // ── ANALYZE — STT ─────────────────────────────────────────────────
            step = FileProcOutcome.STEP_ANALYZE;
            if (stt == null) {
                beginAnalyze(ctx);
                if (stageFault.shouldFail(StageFaultState.Stage.ANALYZE)) {
                    throw StageFaultState.fault(StageFaultState.Stage.ANALYZE,
                            "STT 호출 실패(500/Timeout) 주입");
                }
                stt = sttClient.transcribe(plain);
                if (stt.isEmpty()) {
                    ctx.analyzeMs += System.currentTimeMillis() - tCollected;
                    return FileProcOutcome.fail(target, step, "STT 결과가 비어 있음", System.currentTimeMillis() - t0);
                }
                // 전사 결과를 남긴다 — SEND 가 깨져도 STT 를 다시 돌리지 않게.
                sttTemp.save(ctx.execId, target, stt, fileSize);
            }
            long tAnalyzed = System.currentTimeMillis();
            ctx.analyzeMs += tAnalyzed - tCollected;

            // ── SEND — 최종 저장(PV) ──────────────────────────────────────────
            step = FileProcOutcome.STEP_SEND;
            beginSend(ctx);
            if (stageFault.shouldFail(StageFaultState.Stage.SEND)) {
                throw StageFaultState.fault(StageFaultState.Stage.SEND,
                        "최종 저장 실패(Disk Full / IOException) 주입");
            }
            SttOutputStore.Saved saved = outputStore.save(ctx.execId, target, stt, fileSize);
            ctx.sendMs += System.currentTimeMillis() - tAnalyzed;
            // 이관은 배치 끝에 한 번에 넘긴다 — 건마다 부르면 커넥터가 죽어 있을 때 건당 대기가 쌓인다.
            ctx.toTransfer.add(new egovframework.voice.collector.transfer.AgentConnectorClient.Output(
                    target.kind(), saved.textFile().getFileName().toString(), stt.text()));

            // 끝까지 갔다 — 중간 산출물은 더 필요 없다.
            sttTemp.discardAnywhere(target);
            idempotency.markProcessed(target);
            ok = true;
            return FileProcOutcome.success(target, fileSize, stt.charCount(),
                    saved.textFile().toString().replace('\\', '/'), System.currentTimeMillis() - t0);

        } catch (Exception e) {
            // 사유만 남긴다 — 예외 메시지에 파일 경로·업무 값이 섞여 들어가지 않게 요약한다.
            String reason = e.getClass().getSimpleName() + ": " + shorten(e.getMessage());
            log.warn("[Batch] 처리 실패 [{}] — {} ({})", step, target.shortId(), reason);
            long ms = System.currentTimeMillis() - t0;
            if (FileProcOutcome.STEP_COLLECT.equals(step)) {
                ctx.collectMs += ms;
            } else if (FileProcOutcome.STEP_SEND.equals(step)) {
                ctx.sendMs += ms;
            } else {
                ctx.analyzeMs += ms;
            }
            return FileProcOutcome.fail(target, step, reason, ms);

        } finally {
            // 성공했을 때만 지운다. 실패한 건의 복호화 오디오를 남겨야 다음 재처리가
            // STT 부터 이어서 갈 수 있다 — keep-on-failure 를 내리면 종전대로 즉시 지운다.
            if (ok) {
                cleanupAll(toClean);
                // 이 건이 앞선 실패에서 남긴 보존 오디오도 지운다 — 재처리로 되살려 썼든,
                // 수집부터 다시 해서 성공했든 이제 필요 없다. 안 지우면 평문 PII 가 계속 쌓인다.
                discardPreservedAudio(target);
            } else if (!keepOnFailure) {
                cleanupAll(toClean);
            } else {
                // 실패 — STT 가 쓸 오디오를 작업 폴더에 약속된 이름으로 남기고 나머지는 지운다.
                Path kept = preserveAudio(target, readyAudio);
                cleanupAll(toClean.stream().filter(p -> !p.equals(kept)).toList());
                if (kept != null) {
                    log.info("[Resume] 오디오 보존 — {} · 재처리가 STT 부터 이어 간다", kept.getFileName());
                }
            }
        }
    }

    /**
     * 보존된 복호화 오디오를 찾는다 — {@code {ROOT}/xvram/decoding/decrypted_*}.
     *
     * <p>파일명은 복호화기가 정한 {@code decrypted_<원본명>} 이다. 원본명을 모르면 찾지 못한 것으로
     * 본다 — 추측해서 엉뚱한 파일을 집으면 다른 사람의 음성을 STT 에 태우게 된다.</p>
     */
    /**
     * 실패한 건의 오디오를 작업 폴더에 <b>약속된 이름</b>({@code decrypted_<원본명>})으로 남긴다.
     *
     * <p>이미 그 자리에 그 이름으로 있으면(복호화가 만든 경우) 그대로 두고, 다른 곳에 있으면
     * 옮긴다. 이름을 한곳에서 정해 두어야 {@link #findPreservedAudio} 가 찾을 수 있다.</p>
     *
     * @return 보존한 경로. 남길 것이 없으면 {@code null}
     */
    /** 성공했으니 보존 오디오를 지운다 — 평문 PII 를 필요 이상으로 남기지 않는다. */
    private void discardPreservedAudio(VoiceTarget target) {
        String src = target.srcFileName();
        if (src == null || src.isBlank()) {
            return;
        }
        Path p = Path.of(dirs.work()).resolve("decrypted_" + src);
        try {
            if (Files.deleteIfExists(p)) {
                log.debug("[Resume] 보존 오디오 정리 — {}", p.getFileName());
            }
        } catch (IOException e) {
            log.debug("[Resume] 보존 오디오 정리 실패 — {} ({})", p, e.getMessage());
        }
    }

    private Path preserveAudio(VoiceTarget target, Path audio) {
        String src = target.srcFileName();
        if (audio == null || src == null || src.isBlank() || !Files.isRegularFile(audio)) {
            return null;
        }
        Path dest = Path.of(dirs.work()).resolve("decrypted_" + src);
        try {
            if (audio.toAbsolutePath().normalize().equals(dest.toAbsolutePath().normalize())) {
                return audio;
            }
            Files.createDirectories(dest.getParent());
            Files.copy(audio, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (IOException e) {
            // 보존에 실패해도 배치 결과는 바뀌지 않는다 — 재처리가 수집부터 다시 하면 된다.
            log.warn("[Resume] 오디오 보존 실패 — {} ({}) · 재처리는 수집부터 한다", dest, e.getMessage());
            return null;
        }
    }

    private java.util.Optional<VoiceFile> findPreservedAudio(VoiceTarget target) {
        String src = target.srcFileName();
        if (src == null || src.isBlank()) {
            return java.util.Optional.empty();
        }
        Path p = Path.of(dirs.work()).resolve("decrypted_" + src);
        if (!Files.isRegularFile(p)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new VoiceFile(target, p, Files.size(p), formatOf(src), true));
        } catch (IOException e) {
            log.warn("[Resume] 보존 오디오를 읽지 못했다 — {} ({})", p, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** 원본 파일명의 확장자로 포맷을 본다 — 보존물은 이미 복호화돼 있어 매직 넘버 판별이 필요 없다. */
    private static String formatOf(String srcFileName) {
        int dot = srcFileName.lastIndexOf('.');
        return dot > 0 ? srcFileName.substring(dot + 1).toLowerCase() : "unknown";
    }

    /**
     * 파일을 우리 스토리지까지 가져온다.
     *
     * <p>경로가 둘로 갈린다 — 접견은 우리가 XVARM 브로커에 추출을 <b>지시</b>하고,
     * 전화는 별도 ESB 프로바이더가 떨궈 주는 것을 <b>기다린다</b>.
     * 어느 쪽이든 마지막은 수신 디렉터리를 보는 것으로 같다.</p>
     */
    private VoiceFile acquire(VoiceTarget target, String execId) {
        // 요청 전에 그 이름에 남아 있는 것은 이번 요청의 산출물일 수 없다 — 먼저 치운다.
        watcher.clearStale(target);
        if (target.kind() == VoiceKind.MEET) {
            log.info("[Track:MEET] ② XVARM 추출 요청 — {} via 브로커 {} (execId={})", target.shortId(), broker.mode(), execId);
            XvarmBrokerClient.ExtractResult extracted = broker.extract(target, execId);
            log.info("[Track:MEET] ③ ESB 수신 대기 — {} (브로커 산출 {})",
                    target.shortId(), extracted.filePath());
            // 브로커가 만든 파일이 우리 쪽에 보이는데 수신 폴더 밖이면 기다려 봐야 타임아웃이다.
            //   브로커를 local 프로파일 없이 띄웠을 때 300초를 날리던 자리 — 지금 끊는다.
            //   운영은 보라미 서버 경로라 우리 쪽에 없어 이 판정에 걸리지 않는다(BrokerOutputCheck).
            BrokerOutputCheck.mismatch(extracted.filePath(), dirs.receiveMeet())
                    .ifPresent(reason -> { throw new IllegalStateException(reason); });
            // 브로커가 알려준 실제 파일명을 그대로 쓴다.
            //   추측한 이름으로 찾으면 브로커가 다른 이름으로 만들었을 때 영영 못 찾고 타임아웃이 난다.
            //   Mock 브로커는 우리와 같은 명명 정책을 써서 우연히 일치했을 뿐이고,
            //   실제 XVARM 이 파일명을 어떻게 정하는지는 아직 모른다(계획서 Q3).
            return watcher.await(target, fileNameOf(extracted.filePath()));
        }
        log.info("[Track:PHONE] ② 전화 파일 연계 요청 — {} via {}", target.shortId(), phoneFileProvider.mode());
        phoneFileProvider.request(target);
        log.info("[Track:PHONE] ③ 수신 대기 — {}", target.shortId());
        return watcher.await(target);
    }

    /** 경로에서 파일명만 뽑는다. 경로가 비었으면 null — watcher 가 정책으로 되돌아간다. */
    private static String fileNameOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path name = Path.of(path).getFileName();
            return name == null ? null : name.toString();
        } catch (Exception e) {
            // 브로커가 우리 OS 와 다른 경로 표기를 줄 수 있다(보라미는 리눅스).
            // 파싱이 안 되면 구분자로 직접 자른다.
            int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String tail = (i >= 0) ? path.substring(i + 1) : path;
            return tail.isBlank() ? null : tail;
        }
    }

    /**
     * 보라미가 만들어 둔 STT 텍스트를 읽어 온다. <b>못 읽으면 null 을 돌려주고 일반 경로로 넘긴다.</b>
     *
     * <p>예전에는 여기서 예외를 던져 그 건을 통째로 실패시켰다. 그런데 {@code TELP_STT_FLPTH_NM}
     * 은 <b>보라미 서버 기준 경로</b>다(실DB 표본값 {@code /data001/stt/...}). 그 파일이 우리 쪽에
     * 동기화되어 있다는 보장이 없고, 어떻게 넘어오는지도 아직 정해지지 않았다(계획서 Q1).</p>
     *
     * <p>읽히지 않는다고 수집 자체를 버리는 것은 과하다 — 우리에겐 오디오를 받아 직접 STT 하는
     * 정상 경로가 있다. 지름길이 막혔으면 먼 길로 가면 된다. 다만 <b>조용히 넘어가지는 않는다</b>:
     * 이 경고가 반복되면 Q1 의 전제가 틀렸다는 신호이므로 로그로 남긴다.</p>
     *
     * @return 재사용할 STT. 경로가 없거나 읽지 못하면 {@code null}
     */
    private SttResult tryReadSourceStt(VoiceTarget target) {
        java.nio.file.Path p;
        try {
            p = java.nio.file.Path.of(target.sourceSttPath());
        } catch (Exception e) {
            log.warn("[Track:PHONE] 보라미 STT 경로를 해석할 수 없다 — 직접 STT 로 돌린다 ({} · {})",
                    target.sourceSttPath(), target.shortId());
            return null;
        }
        if (!Files.exists(p)) {
            log.warn("[Track:PHONE] 보라미 STT 파일이 우리 쪽에 없다 — 직접 STT 로 돌린다 "
                            + "({} · {}). TELP_STT_FLPTH_NM 은 보라미 서버 기준 경로다(계획서 Q1)",
                    p, target.shortId());
            return null;
        }
        try {
            String text = Files.readString(p);
            log.info("[Track:PHONE] 보라미 STT 재사용 — {} ({}자, 복호화·STT 생략)",
                    target.shortId(), text.length());
            return new SttResult(text, "BORAMI", 0, true);
        } catch (IOException e) {
            log.warn("[Track:PHONE] 보라미 STT 파일을 읽지 못했다 — 직접 STT 로 돌린다 ({} · {})",
                    e.getMessage(), target.shortId());
            return null;
        }
    }

    /**
     * 이번 건이 디스크에 만든 원본·복호화 산출물을 지운다.
     *
     * <p>기본은 삭제다. 복호화된 원본 음성은 그 자체로 민감하고 우리가 보관할 이유가 없다
     * — STT 가 끝나면 원본은 역할을 다한 것이다. 보존이 필요하면 보존 기간·암호화 저장을
     * 따로 정해야 한다(계획서 Q5).</p>
     *
     * <p>삭제 실패는 경고만 남기고 넘어간다. 데이터 처리 자체는 이미 끝났고, 여기서 예외를
     * 올리면 성공한 건이 실패로 뒤집힌다.</p>
     */
    private void cleanupAll(List<Path> paths) {
        if (props.batch().retainSourceFile() || paths.isEmpty()) {
            return;
        }
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn("[Batch] 원본 삭제 실패 — {} ({})", p.getFileName(), e.getMessage());
            }
        }
    }

    private List<LogCollectorClient.FileProcReq> toFileProcReqs(List<FileProcOutcome> outcomes) {
        List<LogCollectorClient.FileProcReq> rows = new ArrayList<>(outcomes.size());
        for (FileProcOutcome o : outcomes) {
            if (o.status() == ProcStatus.SKIPPED) {
                continue;   // 이번 배치가 처리한 건이 아니다 — 집계에 넣으면 대사가 어긋난다
            }
            // 컬렉터 T4 스펙 순서대로: REC_FILE_ID · FILE_PATH · FILE_NM · INMATE_PID · FILE_SIZE · PROC_STS_CD · ERR_STACK
            rows.add(new LogCollectorClient.FileProcReq(
                    o.target().idempotencyKey(),          // 접견 TARE_FILE_NO / 전화 VRFC_ESTL_ID — NOT NULL
                    o.target().srcFilePath(),             // 보라미 쪽 원본 경로(우리 임시 경로가 아니다)
                    o.target().srcFileName(),
                    pidGenerator.of(o.target().corrNo()),
                    o.fileSize(),
                    o.status().name(),
                    o.isSuccess() ? null : LogCollectorClient.FileProcReq.errStackOf(o.errMsg())));
        }
        return rows;
    }

    private static Integer elapsedSec(long startedAt) {
        return (int) ((System.currentTimeMillis() - startedAt) / 1000);
    }

    private static String shorten(String msg) {
        if (msg == null) {
            return "(사유 없음)";
        }
        return msg.length() <= 300 ? msg : msg.substring(0, 300) + "...";
    }
}
