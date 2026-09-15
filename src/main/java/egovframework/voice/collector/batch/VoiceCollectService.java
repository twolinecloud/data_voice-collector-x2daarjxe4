package egovframework.voice.collector.batch;

import egovframework.voice.collector.broker.XvarmBrokerClient;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.decrypt.DecryptService;
import egovframework.voice.collector.logging.LogCollectorClient;
import egovframework.voice.collector.model.BatchWindow;
import egovframework.voice.collector.model.FileProcOutcome;
import egovframework.voice.collector.model.SttResult;
import egovframework.voice.collector.model.VoiceFile;
import egovframework.voice.collector.model.VoiceKind;
import egovframework.voice.collector.model.VoiceTarget;
import egovframework.voice.collector.source.BoramiSourceClient;
import egovframework.voice.collector.stt.SttClient;
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
import java.util.List;

/**
 * 음성 수집 배치의 본체 — <b>대상 선별 → 파일 확보 → 복호화 → STT → 처리 이력 적재</b>.
 *
 * <p><b>이 서비스의 범위는 STT 처리와 내부 저장·감시까지다.</b> STT 텍스트를 외부 서비스로
 * 전송하지 않는다(비식별 커넥터 연동은 아키텍처 변경으로 제외됐다). 로그 테이블 INSERT 는
 * 로그 컬렉터 API 로만 하고, 재식별 매핑 적재는 다른 서비스의 책임이다.</p>
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
    private final BoramiSourceClient source;
    private final XvarmBrokerClient broker;
    private final egovframework.voice.collector.sync.PhoneFileProvider phoneFileProvider;
    private final FileArrivalWatcher watcher;
    private final DecryptService decryptService;
    private final SttClient sttClient;
    private final LogCollectorClient logCollector;
    private final IdempotencyGuard idempotency;
    private final InmatePidGenerator pidGenerator;
    private final PiiResidueAuditor residueAuditor;

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
        long startedAt = System.currentTimeMillis();
        List<VoiceKind> targets = (kinds == null || kinds.isEmpty())
                ? List.of(VoiceKind.MEET, VoiceKind.PHONE) : kinds;

        String execId = openBatch(window, triggerBy, testRun);
        log.info("[Batch] 시작 — execId={} {} kinds={}{}", execId, window, targets,
                testRun ? "  [시험 실행 — TST 로 채번, 초기화로 삭제 가능]" : "");
        // 트랙별로 따로 찍는다. 두 시나리오는 연동 주체가 달라서 한 줄에 섞으면
        // 어느 모드가 어느 경로에 걸린 것인지 읽히지 않는다.
        if (targets.contains(VoiceKind.MEET)) {
            log.info("[Batch]   접견 트랙 — 조회={} → 브로커={} → 복호화={} → STT={}",
                    source.mode(), broker.mode(), decryptService.mode(), sttClient.mode());
        }
        if (targets.contains(VoiceKind.PHONE)) {
            log.info("[Batch]   전화 트랙 — 조회={} → 파일연계={} → 복호화={} → STT={}  (XVARM 경유 없음)",
                    source.mode(), phoneFileProvider.mode(), decryptService.mode(), sttClient.mode());
        }

        String collectStep = logCollector.createStep(execId, (short) 1, "COLLECT");

        List<VoiceTarget> found = findTargets(window, targets);
        List<FileProcOutcome> outcomes = new ArrayList<>(found.size());

        for (VoiceTarget t : found) {
            outcomes.add(processOne(t));
        }

        int success = (int) outcomes.stream().filter(FileProcOutcome::isSuccess).count();
        int skipped = (int) outcomes.stream().filter(o -> o.status() == egovframework.voice.collector.model.ProcStatus.SKIPPED).count();
        int fail = outcomes.size() - success - skipped;

        logCollector.finishStep(collectStep, fail == 0 ? "SUCCESS" : "PARTIAL",
                elapsedSec(startedAt), (long) found.size(), (long) success, (long) fail, null);

        // T4 — 파일 1건 = 1행. 정합성 대사(T1.SUCCESS_CNT == Σ T3·T4·T5)의 근거다.
        logCollector.createFileProcs(execId, toFileProcReqs(outcomes));

        // PII 즉시 삭제 정책이 실제로 지켜졌는지 확인해 결과에 싣는다(계획서 5.3-(4)).
        PiiResidueAuditor.Residue residue = residueAuditor.audit();

        long elapsedMs = System.currentTimeMillis() - startedAt;
        VoiceBatchResult result = new VoiceBatchResult(execId, window.toString(), found.size(),
                success, fail, skipped, elapsedMs, residue, outcomes);

        logCollector.finishBatch(execId, result.execStsCd(), elapsedSec(startedAt),
                (long) found.size(), (long) success, (long) fail, null);
        log.info("[Batch] 종료 — {}", result.summary());
        return result;
    }

    // ── 단계별 ────────────────────────────────────────────────────────────

    /**
     * T1 을 열어 EXEC_ID 를 받아 온다. 컬렉터가 없으면 로컬 임시 ID 를 만든다.
     *
     * <p>임시 ID 에도 작업코드 자리를 지켜 {@code yyyyMMddHHmmss + VOC} 형태로 만든다 —
     * 로그를 눈으로 볼 때 정식 ID 와 구분되면서도 같은 자리에서 읽히게 하려는 것이다.</p>
     */
    private String openBatch(BatchWindow window, String triggerBy, boolean testRun) {
        String jobId = testRun ? props.batch().testJobId() : props.batch().jobId();
        String execId = logCollector.createBatch(jobId,
                props.batch().dataTypeCd(), window.label(), triggerBy);
        if (execId != null) {
            return execId;
        }
        // 컬렉터가 없을 때도 같은 자리에 작업코드가 오게 만든다.
        //   컬렉터 채번 규칙: yyyyMMdd(8) + 작업코드(3) + 회차(3)
        //   → 9~11번째 자리가 작업코드다. 테스트 데이터 삭제 SQL 이 그 자리를 본다
        //     (SUBSTRING(exec_id FROM 9 FOR 3) = 'TST'). 로컬 ID 도 자리를 맞춰야
        //     눈으로 읽을 때 정식 ID 와 같은 위치에서 구분된다.
        String local = LOCAL_DATE.format(LocalDateTime.now())
                + (testRun ? "TST" : "VOC")
                + LOCAL_TIME.format(LocalDateTime.now());
        log.info("[Batch] 로그 컬렉터 미연동 — 로컬 임시 execId 사용: {}", local);
        return local;
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
     * <p><b>원본 삭제는 {@code finally} 에서 한다.</b> STT 가 실패하면 정상 경로를 타지 않는데,
     * 그 자리에서 지우지 않으면 <b>복호화된 음성이 디스크에 남는다</b>. 정책은 성공·실패를
     * 가리지 않고 즉시 삭제다(계획서 5.3-(4)).</p>
     */
    private FileProcOutcome processOne(VoiceTarget target) {
        long t0 = System.currentTimeMillis();

        if (idempotency.isProcessed(target)) {
            log.debug("[Batch] 이미 처리됨 — {}", target.shortId());
            return FileProcOutcome.skipped(target, "이미 처리된 건");
        }

        // 이번 건이 디스크에 만든 것들 — 어떤 경로로 끝나든 전부 지운다.
        List<Path> toClean = new ArrayList<>(2);
        try {
            SttResult stt;
            long fileSize = 0L;

            SttResult reused = target.hasSourceStt() ? tryReadSourceStt(target) : null;
            if (reused != null) {
                // 보라미가 이미 STT 를 가지고 있는 경우(계획서 Q1). 사실이면 오디오를 만질 필요가 없다.
                stt = reused;
            } else {
                VoiceFile file = acquire(target);
                toClean.add(file.path());
                fileSize = file.sizeBytes();

                VoiceFile plain = decryptService.decrypt(file);
                if (!plain.path().equals(file.path())) {
                    toClean.add(plain.path());   // 복호화가 새 파일을 만든 경우
                }
                stt = sttClient.transcribe(plain);
            }

            if (stt.isEmpty()) {
                return FileProcOutcome.fail(target, "STT 결과가 비어 있음", System.currentTimeMillis() - t0);
            }

            idempotency.markProcessed(target);
            return FileProcOutcome.success(target, fileSize, stt.charCount(), System.currentTimeMillis() - t0);

        } catch (Exception e) {
            // 사유만 남긴다 — 예외 메시지에 파일 경로·업무 값이 섞여 들어가지 않게 요약한다.
            String reason = e.getClass().getSimpleName() + ": " + shorten(e.getMessage());
            log.warn("[Batch] 처리 실패 — {} ({})", target.shortId(), reason);
            return FileProcOutcome.fail(target, reason, System.currentTimeMillis() - t0);

        } finally {
            cleanupAll(toClean);
        }
    }

    /**
     * 파일을 우리 스토리지까지 가져온다.
     *
     * <p>경로가 둘로 갈린다 — 접견은 우리가 XVARM 브로커에 추출을 <b>지시</b>하고,
     * 전화는 별도 ESB 프로바이더가 떨궈 주는 것을 <b>기다린다</b>.
     * 어느 쪽이든 마지막은 수신 디렉터리를 보는 것으로 같다.</p>
     */
    private VoiceFile acquire(VoiceTarget target) {
        if (target.kind() == VoiceKind.MEET) {
            log.info("[Track:MEET] ② XVARM 추출 요청 — {} via 브로커 {}", target.shortId(), broker.mode());
            XvarmBrokerClient.ExtractResult extracted = broker.extract(target);
            log.info("[Track:MEET] ③ ESB 수신 대기 — {} (브로커 산출 {})",
                    target.shortId(), extracted.filePath());
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
     * 올리면 성공한 건이 실패로 뒤집힌다. 대신 배치 끝에서
     * {@link PiiResidueAuditor} 가 잔여를 세어 결과에 싣는다 — 조용히 남는 일이 없게.</p>
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
            if (o.status() == egovframework.voice.collector.model.ProcStatus.SKIPPED) {
                continue;   // 이번 배치가 처리한 건이 아니다 — 집계에 넣으면 대사가 어긋난다
            }
            rows.add(new LogCollectorClient.FileProcReq(
                    pidGenerator.of(o.target().corrNo()),
                    o.target().kind().name(),
                    o.target().srcFileName(),
                    o.fileSize(),
                    o.status().name(),
                    o.sttChars(),
                    o.isSuccess() ? null : "DATA",
                    o.errMsg()));
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
