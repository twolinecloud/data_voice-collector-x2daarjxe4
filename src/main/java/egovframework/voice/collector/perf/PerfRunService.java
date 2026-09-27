package egovframework.voice.collector.perf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import egovframework.voice.collector.batch.BatchProgress;
import egovframework.voice.collector.batch.ResumeMode;
import egovframework.voice.collector.batch.VerificationService;
import egovframework.voice.collector.batch.VoiceBatchResult;
import egovframework.voice.collector.batch.VoiceBatchScheduler;
import egovframework.voice.collector.batch.VoiceCollectService;
import egovframework.voice.collector.broker.XvarmBrokerClient;
import egovframework.voice.collector.config.DeployEnvPreset;
import egovframework.voice.collector.config.MockDatasetState;
import egovframework.voice.collector.config.SourcePoolPeak;
import egovframework.voice.collector.config.VoiceDirState;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.controller.VoiceMockController;
import egovframework.voice.collector.decrypt.DecryptService;
import egovframework.voice.collector.model.BatchWindow;
import egovframework.voice.collector.model.FileProcOutcome;
import egovframework.voice.collector.model.ProcStatus;
import egovframework.voice.collector.source.BoramiSourceClient;
import egovframework.voice.collector.source.DbKindDetector;
import egovframework.voice.collector.source.SimulationDataService;
import egovframework.voice.collector.stt.MockSttLatency;
import egovframework.voice.collector.stt.SttClient;
import egovframework.voice.collector.stt.SttOutputStore;
import egovframework.voice.collector.sync.PhoneFileProvider;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 성능 테스트 — <b>실제 Java 파이프라인</b>을 N건으로 돌려 처리량·시간·자원을 잰다. STT 만 MOCK(가상 지연)이다.
 *
 * <p><b>한 회차의 순서</b></p>
 * <ol>
 *   <li><b>준비</b> — 로컬 산출물(멱등 표식·수신 파일·보존물)과 지난 시험 출력 폴더를 비우고, 원천 DB 에
 *       SIM 데이터 N건(접견·전화 반반)을 <b>어제 하루</b>에 만든다. 준비 시간은 측정에 넣지 않는다</li>
 *   <li><b>측정</b> — {@code [어제 00:00, 오늘 00:00)} 를 수동 배치({@code TEST_BATCH} · {@code MANUAL} ·
 *       실행 주체 {@code PERF})로 워커 N개가 처리한다. 단계별 시간·힙·원천 풀 최고 연결 수를 함께 잰다</li>
 *   <li><b>검증</b> — 로그 컬렉터에서 되읽어 T2 에 RUNNING 이 남지 않았는지, 단계 행이 겹치지 않았는지,
 *       T4 행 수가 처리 건수와 같은지 본다</li>
 *   <li><b>정리</b> — 공용 DB 의 SIM 행과 원본 더미 파일을 <b>지운다</b>. 로그(TST)와 STT 출력은 남겨
 *       [상세 검증]으로 볼 수 있게 한다(다음 회차 준비 때 출력 폴더는 지운다)</li>
 * </ol>
 *
 * <p>이력은 {@code {ROOT}/perf/history.jsonl} 에 한 줄씩 쌓는다 — PV 라 파드를 다시 띄워도 남고,
 * 여러 사람이 같은 이력을 본다.</p>
 *
 * <p>한 번에 한 회차만 돈다. 배치(스케줄러·화면)가 돌고 있으면 시작하지 않는다.</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class PerfRunService {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String HISTORY_FILE = "history.jsonl";
    /** 화면이 읽는 이력 최대 건수 — 파일은 자르지 않는다. */
    private static final int HISTORY_READ_MAX = 300;

    public enum Phase {
        PREPARING("준비"), RUNNING("측정"), VERIFYING("검증"), CLEANING("정리"), DONE("완료"), FAILED("실패");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        boolean active() {
            return this != DONE && this != FAILED;
        }
    }

    private final VoiceCollectService collect;
    private final BatchProgress progress;
    private final VoiceBatchScheduler scheduler;
    private final SimulationDataService sim;
    private final MockDatasetState dataset;
    private final SttOutputStore outputStore;
    private final VerificationService verification;
    private final MockSttLatency latency;
    private final PerfStageMeter meter;
    private final SourcePoolPeak poolPeak;
    private final VoiceDirState dirs;
    private final VoiceProperties props;
    private final DeployEnvPreset deployEnv;
    private final DbKindDetector dbKind;
    private final BoramiSourceClient source;
    private final XvarmBrokerClient broker;
    private final PhoneFileProvider phoneFileProvider;
    private final DecryptService decryptService;
    private final SttClient sttClient;
    private final ObjectMapper objectMapper;
    /** 로컬 산출물 정리를 [시뮬레이션 데이터 생성]과 같은 코드로 한다. */
    private final VoiceMockController mock;

    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "perf-runner");
        t.setDaemon(true);
        return t;
    });

    private volatile Run current;

    /** 한 회차의 진행 상태 — 러너 스레드가 쓰고 API 스레드가 읽는다. */
    private static final class Run {
        final String id;
        final PerfRequest req;
        final long startedAt = System.currentTimeMillis();
        volatile Phase phase = Phase.PREPARING;
        volatile String message = "준비 중…";
        volatile long finishedAt;
        volatile Map<String, Object> result;
        volatile String error;
        volatile boolean cancelRequested;

        Run(String id, PerfRequest req) {
            this.id = id;
            this.req = req;
        }

        void to(Phase p, String msg) {
            this.phase = p;
            this.message = msg;
            log.info("[Perf] {} {} — {}", id, p.label(), msg);
        }
    }

    // ── 실행 ──────────────────────────────────────────────────────────────

    /**
     * 한 회차를 시작한다 — 비동기. 바로 돌아오고, 진행은 {@link #current()} 로 본다.
     *
     * @throws IllegalArgumentException 조건이 범위를 벗어났다(400)
     * @throws IllegalStateException    이미 성능 테스트나 배치가 돌고 있다(409)
     */
    public synchronized Map<String, Object> start(PerfRequest raw) {
        PerfRequest req = (raw == null ? new PerfRequest(null, null, null, null, null, null, null) : raw).withDefaults();
        req.validate(props.batch().maxFilesPerRun());
        Run running = current;
        if (running != null && running.phase.active()) {
            throw new IllegalStateException("성능 테스트가 이미 돌고 있습니다 — " + running.id + " (" + running.phase.label() + ")");
        }
        if (progress.isRunning() || scheduler.isRunning()) {
            throw new IllegalStateException("다른 배치가 실행 중입니다 — 끝난 뒤 다시 시작하십시오");
        }
        Run run = new Run(LocalDateTime.now().format(RUN_ID), req);
        current = run;
        runner.submit(() -> execute(run));
        return snapshot(run);
    }

    /** 중단 — 준비 중이면 측정을 건너뛰고, 측정 중이면 처리 중인 건만 끝내고 멈춘다. */
    public Map<String, Object> cancel() {
        Run run = current;
        Map<String, Object> out = new LinkedHashMap<>();
        if (run == null || !run.phase.active()) {
            out.put("accepted", false);
            out.put("message", "돌고 있는 성능 테스트가 없습니다");
            return out;
        }
        run.cancelRequested = true;
        boolean batch = progress.cancel();
        out.put("accepted", true);
        out.put("message", batch ? "중단 요청 — 처리 중인 건을 끝내고 멈춥니다" : "중단 요청 — 측정 전에 멈춥니다");
        out.putAll(snapshot(run));
        return out;
    }

    public boolean isActive() {
        Run run = current;
        return run != null && run.phase.active();
    }

    /** 지금(또는 마지막) 회차. 한 번도 안 돌았으면 {@code phase=null}. */
    public Map<String, Object> current() {
        Run run = current;
        if (run == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", null);
            m.put("phase", null);
            m.put("active", false);
            return m;
        }
        return snapshot(run);
    }

    private Map<String, Object> snapshot(Run run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.id);
        m.put("phase", run.phase.name());
        m.put("phaseLabel", run.phase.label());
        m.put("active", run.phase.active());
        m.put("message", run.message);
        m.put("request", run.req);
        m.put("elapsedMs", (run.finishedAt == 0 ? System.currentTimeMillis() : run.finishedAt) - run.startedAt);
        m.put("cancelRequested", run.cancelRequested);
        // 측정 뒤(검증·정리)에도 마지막 진행률을 싣는다 — 화면이 측정 마지막 폴링을 놓치면 14/20 에서 멈춰 보인다.
        if (run.phase == Phase.RUNNING || run.phase == Phase.VERIFYING || run.phase == Phase.CLEANING) {
            m.put("progress", progress.snapshot());
        }
        m.put("result", run.result);
        m.put("error", run.error);
        return m;
    }

    private void execute(Run run) {
        PerfRequest req = run.req;
        Map<String, Object> result = null;
        String error = null;
        try {
            // ── 준비 ────────────────────────────────────────────────────
            long p0 = System.currentTimeMillis();
            run.to(Phase.PREPARING, "로컬 산출물 정리 · 지난 시험 출력 삭제");
            Map<String, Object> cleared = mock.clearLocalFiles();
            int oldOutputs = outputStore.deleteTestOutputs();
            run.to(Phase.PREPARING, "SIM 데이터 %d건 생성 (접견 %d · 전화 %d) — %s"
                    .formatted(req.count(), req.meetCount(), req.phoneCount(), dbKind.label()));
            Map<String, Object> seed = sim.seedPerf(req.meetCount(), req.phoneCount());
            long prepareMs = System.currentTimeMillis() - p0;
            if (run.cancelRequested) {
                throw new CanceledBeforeRun();
            }

            // ── 측정 ────────────────────────────────────────────────────
            run.to(Phase.RUNNING, "워커 %d개로 %d건 처리 중".formatted(req.concurrency(), req.count()));
            latency.apply(req.mode(), req.latencyMs(), req.latencyMaxMs() == null ? req.latencyMs() : req.latencyMaxMs(),
                    req.sttTimeoutMs() == null ? 0L : req.sttTimeoutMs());
            LocalDate today = LocalDate.now();
            BatchWindow window = BatchWindow.manual(today.minusDays(1).atStartOfDay(), today.atStartOfDay());

            List<MemoryPoolMXBean> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(p -> p.getType() == MemoryType.HEAP && p.isValid()).toList();
            heapPools.forEach(MemoryPoolMXBean::resetPeakUsage);
            long heapStart = heapUsed();
            poolPeak.reset();
            meter.begin();
            VoiceBatchResult r;
            try {
                r = collect.run(window, null, "PERF", true, ResumeMode.FULL, null, req.concurrency());
            } finally {
                meter.end();
                latency.clear();
            }
            long heapEnd = heapUsed();
            long heapPeak = heapPools.stream().mapToLong(p -> {
                MemoryUsage u = p.getPeakUsage();
                return u == null ? 0L : u.getUsed();
            }).sum();

            // ── 검증 ────────────────────────────────────────────────────
            run.to(Phase.VERIFYING, "로그 컬렉터에서 T1·T2·T4 되읽기 — " + r.execId());
            Map<String, Object> checks = checks(r);

            result = summarize(run, r, seed, cleared, oldOutputs, prepareMs, checks,
                    new long[] {heapStart, heapEnd, heapPeak});
        } catch (CanceledBeforeRun e) {
            error = "측정 전에 중단했습니다";
        } catch (Exception e) {
            error = rootMessage(e);
            log.warn("[Perf] {} 실패 — {}", run.id, error, e);
        } finally {
            latency.clear();
            meter.end();
        }

        // ── 정리 — 공용 DB 의 SIM 행을 지운다 (실패·중단이어도) ─────────────────
        run.to(Phase.CLEANING, "공용 DB 의 SIM 행 · 원본 더미 파일 삭제");
        Map<String, Object> cleanup = cleanup();
        if (result != null) {
            result.put("cleanup", cleanup);
            appendHistory(result);
        }
        run.result = result;
        run.error = error;
        run.finishedAt = System.currentTimeMillis();
        if (error == null) {
            run.to(Phase.DONE, "완료 — %s · %.1f TPS · %s초".formatted(result.get("execStsCd"), result.get("tps"), result.get("totalSec")));
        } else {
            run.to(Phase.FAILED, error);
        }
    }

    /** 측정 전에 중단 요청이 들어왔다 — 정리만 하고 끝낸다. */
    private static final class CanceledBeforeRun extends RuntimeException {
        CanceledBeforeRun() {
            super(null, null, false, false);
        }
    }

    private Map<String, Object> cleanup() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Object> c = sim.clean();
            dataset.reset();
            out.put("ok", true);
            out.put("counts", c.get("counts"));
        } catch (Exception e) {
            log.warn("[Perf] SIM 데이터 정리 실패 — {} · [시뮬레이션 데이터 초기화]로 지우십시오", e.getMessage());
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    // ── 결과 ──────────────────────────────────────────────────────────────

    private Map<String, Object> summarize(Run run, VoiceBatchResult r, Map<String, Object> seed,
                                          Map<String, Object> cleared, int oldOutputs, long prepareMs,
                                          Map<String, Object> checks, long[] heap) {
        PerfRequest req = run.req;
        List<FileProcOutcome> done = r.outcomes().stream().filter(o -> o.status() != ProcStatus.SKIPPED).toList();
        long timeouts = done.stream().filter(PerfRunService::isTimeout).count();
        double sec = r.elapsedMs() / 1000d;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.id);
        m.put("at", LocalDateTime.now().withNano(0).toString());
        m.put("scenario", req.scenario());
        m.put("count", req.count());
        m.put("concurrency", req.concurrency());
        Map<String, Object> lat = new LinkedHashMap<>();
        lat.put("mode", req.latencyMode());
        lat.put("ms", req.latencyMs());
        lat.put("maxMs", req.latencyMaxMs());
        m.put("latency", lat);
        m.put("sttTimeoutMs", req.sttTimeoutMs());
        m.put("sttMode", sttClient.mode());
        m.put("modes", modes());
        m.put("env", deployEnv.snapshot().get("kind"));
        m.put("db", seed.get("db"));

        m.put("execId", r.execId());
        m.put("execStsCd", r.execStsCd());
        m.put("canceled", r.canceled());
        m.put("totalSec", round(sec, 2));
        m.put("avgMs", done.isEmpty() ? 0d : round(done.stream().mapToLong(FileProcOutcome::elapsedMs).average().orElse(0d), 1));
        m.put("tps", sec <= 0 ? 0d : round(r.successCnt() / sec, 2));
        m.put("total", r.targetCnt());
        m.put("success", r.successCnt());
        m.put("fail", r.failCnt());
        m.put("timeout", timeouts);
        m.put("skipped", r.skippedCnt());
        m.put("stages", meter.snapshot());

        Map<String, Object> h = new LinkedHashMap<>();
        h.put("startMb", mb(heap[0]));
        h.put("endMb", mb(heap[1]));
        h.put("peakMb", mb(heap[2]));
        h.put("maxMb", mb(Runtime.getRuntime().maxMemory()));
        m.put("heap", h);
        m.put("hikari", poolPeak.snapshot());
        m.put("checks", checks);
        m.put("prepareSec", round(prepareMs / 1000d, 1));
        Map<String, Object> prep = new LinkedHashMap<>();
        prep.put("idempotencyMarkers", cleared.get("idempotencyMarkers"));
        prep.put("sttTempFiles", cleared.get("sttTempFiles"));
        prep.put("oldOutputDirs", oldOutputs);
        prep.put("filesWritten", ((List<?>) seed.getOrDefault("files", List.of())).size());
        m.put("prepare", prep);
        return m;
    }

    /** STT 타임아웃 — MOCK 의 가상 타임아웃이거나, NPU 호출의 읽기 타임아웃. */
    static boolean isTimeout(FileProcOutcome o) {
        String e = o.errMsg();
        return e != null && (e.startsWith("SttTimeoutException") || e.contains("timed out"));
    }

    /**
     * 끝난 배치를 로그 컬렉터에서 되읽어 맞춰 본다.
     *
     * <ul>
     *   <li>T2 에 {@code RUNNING} 이 남지 않았는가 — 타임아웃·동시 처리 뒤에도 단계가 닫혔는지</li>
     *   <li>T2 단계 행이 겹치지 않았는가 — 동시 처리에서 두 워커가 같은 단계를 두 번 열지 않았는지</li>
     *   <li>T4 행 수 = 처리 건수(성공+실패), T1 성공 수 = T4 SUCCESS 수</li>
     * </ul>
     */
    private Map<String, Object> checks(VoiceBatchResult r) {
        Map<String, Object> c = new LinkedHashMap<>();
        Map<String, Object> db;
        try {
            db = verification.db(r.execId());
        } catch (Exception e) {
            c.put("available", false);
            c.put("reason", "로그 컬렉터 조회 실패 — " + e.getMessage());
            return c;
        }
        if (!Boolean.TRUE.equals(db.get("available"))) {
            c.put("available", false);
            c.put("reason", db.getOrDefault("reason", "로그 컬렉터 미연동"));
            return c;
        }
        c.put("available", true);
        Map<?, ?> t1 = (Map<?, ?>) db.get("t1");
        List<?> t2 = (List<?>) db.getOrDefault("t2", List.of());
        Map<?, ?> t4 = (Map<?, ?>) db.getOrDefault("t4", Map.of());

        long running = 0;
        Map<String, Integer> perType = new HashMap<>();
        for (Object o : t2) {
            Map<?, ?> s = (Map<?, ?>) o;
            if ("RUNNING".equals(String.valueOf(s.get("stepStsCd")))) {
                running++;
            }
            perType.merge(String.valueOf(s.get("stepTypeCd")), 1, Integer::sum);
        }
        List<String> dup = perType.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).sorted().toList();
        long expected = (long) r.successCnt() + r.failCnt();
        long t4Rows = num(t4.get("total"));
        Map<?, ?> byStatus = t4.get("byStatus") instanceof Map<?, ?> b ? b : Map.of();
        long t4Success = num(byStatus.get("SUCCESS"));
        long t1Success = num(t1 == null ? null : t1.get("successCnt"));

        c.put("t1Status", t1 == null ? null : t1.get("execStsCd"));
        c.put("t2Steps", t2.size());
        c.put("t2Running", running);
        c.put("t2Duplicated", dup);
        c.put("t4Rows", t4Rows);
        c.put("t4Expected", expected);
        c.put("t1Success", t1Success);
        c.put("t4Success", t4Success);
        c.put("ok", running == 0 && dup.isEmpty() && t4Rows == expected && t1Success == t4Success);
        return c;
    }

    private Map<String, Object> modes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source.mode());
        m.put("broker", broker.mode());
        m.put("phone", phoneFileProvider.mode());
        m.put("decrypt", decryptService.mode());
        m.put("stt", sttClient.mode());
        return m;
    }

    /** 화면 입력 패널이 쓰는 한 장 — 범위·기본값·지금 구성. */
    public Map<String, Object> info(int defaultConcurrency) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sttMode", sttClient.mode());
        m.put("sttTimeoutSec", props.stt().timeoutSec());
        m.put("mockLatency", latency.snapshot());
        m.put("minCount", PerfRequest.MIN_COUNT);
        m.put("maxCount", PerfRequest.MAX_COUNT);
        m.put("maxLatencyMs", PerfRequest.MAX_LATENCY_MS);
        m.put("concurrencyOptions", PerfRequest.CONCURRENCY_OPTIONS);
        m.put("batchConcurrency", defaultConcurrency);
        m.put("maxFilesPerRun", props.batch().maxFilesPerRun());
        m.put("modes", modes());
        m.put("env", deployEnv.snapshot().get("kind"));
        m.put("db", dbKind.label());
        m.put("historyFile", historyFile().toString().replace('\\', '/'));
        m.put("active", isActive());
        return m;
    }

    // ── 이력 ──────────────────────────────────────────────────────────────

    private Path historyFile() {
        return Path.of(dirs.baseDir(), "perf", HISTORY_FILE);
    }

    private synchronized void appendHistory(Map<String, Object> result) {
        Path f = historyFile();
        try {
            Files.createDirectories(f.getParent());
            String line = objectMapper.writeValueAsString(result) + "\n";
            Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[Perf] 이력 기록 실패 — {} ({})", f, e.getMessage());
        }
    }

    /** 이력 — 최근 것이 앞. 한 줄이 깨져 있어도 나머지는 읽는다. */
    public synchronized Map<String, Object> history() {
        Path f = historyFile();
        List<Map<String, Object>> items = new ArrayList<>();
        int broken = 0;
        if (Files.isRegularFile(f)) {
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        items.add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() { }));
                    } catch (IOException e) {
                        broken++;
                    }
                }
            } catch (IOException e) {
                log.warn("[Perf] 이력 읽기 실패 — {} ({})", f, e.getMessage());
            }
        }
        Collections.reverse(items);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", f.toString().replace('\\', '/'));
        out.put("total", items.size());
        out.put("broken", broken);
        out.put("items", items.size() > HISTORY_READ_MAX ? items.subList(0, HISTORY_READ_MAX) : items);
        return out;
    }

    public synchronized Map<String, Object> clearHistory() {
        Path f = historyFile();
        Map<String, Object> out = new LinkedHashMap<>();
        int n = 0;
        try {
            if (Files.isRegularFile(f)) {
                n = (int) Files.readAllLines(f, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).count();
                Files.delete(f);
            }
        } catch (IOException e) {
            out.put("error", e.getMessage());
        }
        out.put("deleted", n);
        out.put("file", f.toString().replace('\\', '/'));
        return out;
    }

    // ── 보조 ──────────────────────────────────────────────────────────────

    /** 화면에 올릴 실패 사유 — 가장 안쪽 원인만, 300자까지. 바깥 예외는 SQL 전문을 싣고 와 읽히지 않는다. */
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage() == null ? "" : root.getMessage();
        return root.getClass().getSimpleName() + ": " + (msg.length() > 300 ? msg.substring(0, 300) + "…" : msg);
    }

    private static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static double mb(long bytes) {
        return round(bytes / 1024d / 1024d, 1);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? 0L : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }
}
