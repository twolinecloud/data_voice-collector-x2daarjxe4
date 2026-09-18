package egovframework.voice.collector.source;

import egovframework.voice.collector.config.MockDatasetState;
import egovframework.voice.collector.config.VoiceDirState;
import egovframework.voice.collector.config.VoiceProperties;
import egovframework.voice.collector.model.VoiceKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 시뮬레이션 데이터 — <b>DB 메타(보라미·XVARM) + 물리 더미 음성 파일</b>을 한 번에 만들고 지운다.
 *
 * <p><b>구성 (접견 7 · 전화 7 = 14건)</b>: 트랙마다 5건은 <b>어제 09:10~09:50</b>(일배치 창), 2건은
 * <b>지금-6분 / 지금-3분</b>(10분 주기 창). 접견은 4단(re·im·sm·xvarm) 1:1:1:1, 전화는 im 통화내역 + im 특이수용자 1:1 로
 * 조인이 서는 유효 메타를 넣고, DB 의 파일명과 1:1 인 경량 더미 파일(0~1KB)을 XVARM 원본 스토리지에 쓴다.</p>
 *
 * <p><b>같은 코드가 H2(로컬)와 개발계 PostgreSQL 을 상대한다.</b> 테이블명은 {@link BoramiTableNames} 가 스키마를 붙여 주고,
 * 값은 전부 바인딩({@code ?})이라 DB 문법 차이가 없다. 스펙의 NOT NULL 컬럼(테이블 정의서 기준)을 모두 채운다 —
 * 개발계 실제 테이블에는 H2 Mock 보다 컬럼이 많기 때문이다. 개발계에 없는 두 테이블(공통파일기본·XVARM)은
 * XVARM 모드가 {@code MOCK_DEV} 일 때 {@link #ensureXvarmMockTables()} 가 먼저 만든다.</p>
 *
 * <p><b>식별</b>: 우리가 넣은 행은 전부 {@code SIM} 접두다 (교정번호 {@code SIM…}, 녹취 {@code SIM-MEET-nnn}, 통화 {@code SIM-PHONE-nnn},
 * 공통파일 {@code SIMCMFInnnn}, 문서 {@code SIMDOCnnnn}). 초기화는 이 접두만 지우므로 운영·다른 사람 데이터는 건드리지 않는다.</p>
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class SimulationDataService {

    /** 시연 기본 — 트랙별 일배치 5건(어제) + 주기배치 2건(최근 10분) = 7건, 합계 14건. */
    public static final int DAILY_PER_KIND = 5;
    public static final int PERIODIC_PER_KIND = 2;
    public static final int MEET_COUNT = DAILY_PER_KIND + PERIODIC_PER_KIND;
    public static final int PHONE_COUNT = DAILY_PER_KIND + PERIODIC_PER_KIND;
    /** 이 건수를 넘는 대용량 시딩은 더미 파일을 쓰지 않는다(파이프라인은 브로커·Mock 이 만든 파일을 쓴다). */
    public static final int FILE_LIMIT = 200;
    /** 이 번호의 전화 건은 "보라미가 이미 STT 를 가지고 있는" 시나리오(계획서 Q1). */
    public static final int PHONE_WITH_SOURCE_STT = 3;
    /** 이 번호의 접견 건은 암호화되지 않은 파일 — 복호화가 통과(Noop)하는지 본다. */
    public static final int MEET_UNENCRYPTED = 2;
    /**
     * 화면에 돌려줄 SQL 문장 수 상한. 대용량 시딩은 수천 문장이 되는데 그걸 전부 JSON 에 실으면
     * 응답이 메가 단위로 붓고 로그 콘솔이 잠긴다. 넘치면 앞에서부터 이만큼만 싣고 총 건수를 따로 알린다.
     */
    public static final int SQL_TRACE_LIMIT = 150;

    private static final String USR = "simadm";
    /** 특이수용자 코드 — 001 만 마약(1) 이라 코드 필터 테스트의 기준이 된다. */
    private static final String[] SPECL_CODES = {"1", "2", "3", "0", "5"};

    private final JdbcTemplate jdbc;
    private final BoramiTableNames tables;
    private final DbKindDetector db;
    private final VoiceDirState dirs;
    private final VoiceProperties props;
    private final MockDatasetState dataset;

    // ── 조회 ──────────────────────────────────────────────────────────────

    /** 현재 상태 — DB 종류, 테이블, SIM 행 수, 더미 파일 수. 실패해도 예외 대신 사유를 싣는다. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", db.label());
        out.put("dbUrl", db.url());
        out.put("xvarmMode", tables.isXvarmMock() ? "MOCK_DEV" : "REAL");
        out.put("tables", tables.describe());
        Map<String, Object> rows = new LinkedHashMap<>();
        try {
            rows.put("inmates", count(tables.imscPtprDt(), "CORR_NO LIKE 'SIM%'"));
            rows.put("meet", count(tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'SIM-MEET-%'"));
            rows.put("phone", count(tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE 'SIM-PHONE-%'"));
            rows.put("cmfi", count(tables.smsmCmfiBs(), "CMMN_FILE_ID LIKE 'SIMCMFI%'"));
            rows.put("xvarm", count(tables.asysContentElement(), "ELEMENTID LIKE 'SIMDOC%'"));
        } catch (Exception e) {
            rows.put("error", rootMessage(e));
        }
        out.put("rows", rows);
        out.put("files", fileStatus());
        return out;
    }

    // ── 생성 ──────────────────────────────────────────────────────────────

    /**
     * Clean &amp; Seed — 기존 SIM 행·더미 파일을 지운 뒤 시연 기본 10건(트랙별 일배치 3 + 주기 2)을 새로 만든다.
     *
     * @return 결과 요약(테이블별 행 수 · 파일 목록 · 폴더)
     */
    @Transactional
    public Map<String, Object> seed() {
        return seed(DAILY_PER_KIND, DAILY_PER_KIND);
    }

    /**
     * Clean &amp; Seed — 일배치용 건수를 지정한다(대용량 시험). 주기배치용 2·2 는 늘 같다.
     *
     * <p><b>대용량(기본 10건 초과)은 로컬 H2 에서만</b> 허용한다 — 개발계 DB 에 수천 행을 넣으면 남의 시험을 방해한다.
     * {@link #FILE_LIMIT} 을 넘으면 더미 파일은 쓰지 않는다.</p>
     */
    @Transactional
    public Map<String, Object> seed(int dailyMeet, int dailyPhone) {
        if (dailyMeet < 0 || dailyPhone < 0) {
            throw new IllegalArgumentException("건수는 음수일 수 없다");
        }
        boolean bulk = dailyMeet > DAILY_PER_KIND || dailyPhone > DAILY_PER_KIND;
        if (bulk && !db.isH2()) {
            throw new IllegalStateException("대용량 시딩(일배치 " + dailyMeet + "·" + dailyPhone + ")은 로컬 H2 에서만 허용한다 — 지금 대상: " + db.label());
        }
        dataset.set(dailyMeet, dailyPhone);
        int meetCount = dailyMeet + PERIODIC_PER_KIND;
        int phoneCount = dailyPhone + PERIODIC_PER_KIND;
        boolean writeFiles = meetCount + phoneCount <= FILE_LIMIT;

        SqlTrace trace = new SqlTrace();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", db.label());
        out.put("target", db.target().name());
        out.put("dirsEnsured", dirs.ensureDirs());
        out.put("ensured", ensureXvarmMockTables(trace));
        Map<String, Object> cleaned = cleanRows(trace);
        out.put("cleaned", cleaned);

        LocalDateTime now = LocalDateTime.now().withNano(0);
        Timestamp ts = Timestamp.valueOf(now);
        // 특이수용자 — 접견·전화가 같은 사람(001~)을 쓴다. 코드는 1/2/3/0/5 를 돌려 가며 준다
        int inmates = Math.max(meetCount, phoneCount);
        List<Object[]> inmateRows = new ArrayList<>();
        for (int i = 1; i <= inmates; i++) {
            // 001 만 마약(1) — 나머지는 2/3/0/5 를 돌린다(코드 필터 테스트가 001 하나만 기대한다)
            String code = i == 1 ? SPECL_CODES[0] : SPECL_CODES[1 + ((i - 2) % (SPECL_CODES.length - 1))];
            inmateRows.add(new Object[] {corrNo(i), 1, code, "A01", "20260101", null, ts, USR, ts, USR});
        }
        batch(trace, "INSERT INTO " + tables.imscPtprDt()
                + " (CORR_NO, PTCR_PRSR_DTL_SN, SPECL_MNG_SE_CD, PTCR_PRSR_SE_CD, PTCR_PRSR_APNT_YMD, PTCR_PRSR_RMV_YMD,"
                + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID) VALUES (?,?,?,?,?,?,?,?,?,?)", inmateRows);

        List<Map<String, Object>> files = new ArrayList<>();
        Path meetDir = dirs.xvarmOriginalDir(VoiceKind.MEET);
        Path phoneDir = dirs.xvarmOriginalDir(VoiceKind.PHONE);

        // 접견 — re → im → sm → xvarm
        for (int i = 1; i <= meetCount; i++) {
            LocalDateTime at = occurredAt(now, i, dailyMeet);
            Timestamp crt = Timestamp.valueOf(at);
            String fileNm = meetFileName(i);
            Path file = meetDir.resolve(fileNm);
            String cmfi = "SIMCMFI" + (i < 10000 ? "%04d".formatted(i) : String.valueOf(i));
            String doc = "SIMDOC" + (i < 10000 ? "%04d".formatted(i) : String.valueOf(i));
            exec(trace, "INSERT INTO " + tables.smsmCmfiBs()
                    + " (CMMN_FILE_ID, DOC_ID, FILE_NM, CORR_WRK_SE_CD, FILE_TY_CD, REG_DT, RPRS_YN, CMMN_FILE_ENC_YN, DEL_YN,"
                    + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    cmfi, doc, fileNm, "01", "A", crt, "Y", i == MEET_UNENCRYPTED ? "N" : "Y", "N", crt, USR, crt, USR);
            exec(trace, "INSERT INTO " + tables.asysContentElement() + " (ELEMENTID, FILEKEY) VALUES (?,?)",
                    doc, slash(file));
            exec(trace, "INSERT INTO " + tables.rerdTfinDs()
                    + " (TARE_FILE_NO, CORR_INSTT_CD, ADNC_SE_CD, RCPT_YMD, RCPT_SN, CORR_NO, ADNC_YMD,"
                    + "  TBLT_RECRD_FILE_ID, TBLT_VTR_FILE_ID, TARE_FILE_NM, TARE_BGNG_HMS, TARE_END_HMS, TARE_FILE_MG_VL, TARE_FLPTH_NM,"
                    + "  DEL_YN, RECRD_FILE_DEL_YN, RECRD_BKUP_FILE_DEL_YN, CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    meetKey(i), "CI00001", "01", ymd(at), i, corrNo(i), ymd(at),
                    "SIMTRCD" + (i < 10000 ? "%04d".formatted(i) : String.valueOf(i)), cmfi, fileNm, hms(at), hms(at.plusMinutes(10)), "1024", slash(meetDir),
                    "N", "N", "N", crt, USR, crt, USR);
            if (writeFiles) {
                files.add(writeDummy(file, "m4a", meetKey(i)));
            }
        }

        // 전화 — im 통화내역 → im 특이수용자
        for (int i = 1; i <= phoneCount; i++) {
            LocalDateTime at = occurredAt(now, i, dailyPhone);
            Timestamp crt = Timestamp.valueOf(at);
            String fileNm = phoneFileName(i);
            Path file = phoneDir.resolve(fileNm);
            String sttPath = null;
            if (i == PHONE_WITH_SOURCE_STT && dailyPhone >= PHONE_WITH_SOURCE_STT) {
                Path stt = phoneDir.resolve("mock_phone_" + seq(i) + ".stt.txt");
                files.add(writeText(stt, "(보라미 기존 STT / 시뮬레이션) 여보세요 저 김수용입니다. 어머니 잘 계시죠.\n"
                        + "연락처 010-9876-5432 로 전화 주세요. 주민번호는 900101-1234567 입니다.\n"));
                sttPath = slash(stt);
            }
            exec(trace, "INSERT INTO " + tables.imphUcdrDs()
                    + " (VRFC_ESTL_ID, PCALL_KND_CD, TELP_USR_SCPT_SE_CD, CORR_NO, TELP_LST_SE_CD, RCVER_NM, ACQT_RLTNS_NM, INTRL_TELNO,"
                    + "  TELP_PCALL_BGNG_DT, TELP_PCALL_RSPNS_DT, TELP_PCALL_END_DT, TELP_PCALL_TIME, TELP_RSPNS_TIME, TELP_PCALL_RSPNS_YN,"
                    + "  TELP_PCALL_OCRN_AMT, TELP_PCALL_RECRD_YN, TELP_PTCR_PRSR_YN, TELP_PTCR_PRSR_TCNT, CORR_INSTT_CD, TELP_USE_PLACE_NM,"
                    + "  TELP_RECRD_FLPTH_NM, TELP_RECRD_FILE_NM, TELP_RECRD_FILE_ID, TELP_STT_FLPTH_NM, DEL_DT,"
                    + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    phoneKey(i), "P001", "01", corrNo(i), "L001", "(시뮬레이션)수신자" + i, "(시뮬레이션)관계", "000-0000-000" + i,
                    dt14(at), dt14(at.plusSeconds(5)), dt14(at.plusMinutes(3)), 180, 5, "Y",
                    0, "Y", props.source().flag().ptcrYes(), 1, "CI00001", "(시뮬레이션)전화실",
                    slash(phoneDir), fileNm, "SIMPHONEKEY" + (i < 10000 ? "%04d".formatted(i) : String.valueOf(i)), sttPath, null,
                    crt, USR, crt, USR);
            if (writeFiles) {
                files.add(writeDummy(file, "wav", phoneKey(i)));
            }
        }

        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("inmates", inmates);
        rows.put("meet", meetCount);
        rows.put("phone", phoneCount);
        rows.put("cmfi", meetCount);
        rows.put("xvarm", meetCount);
        out.put("rows", rows);
        out.put("files", files);
        out.put("filesSkipped", !writeFiles);
        out.put("dirs", Map.of("meet", slash(meetDir), "phone", slash(phoneDir)));
        out.put("tables", tables.describe());
        out.put("windows", Map.of(
                "daily", "접견 %d · 전화 %d (어제 09:10 부터 10분 간격)".formatted(dailyMeet, dailyPhone),
                "periodic", "접견 %d · 전화 %d (지금-6분 / 지금-3분)".formatted(PERIODIC_PER_KIND, PERIODIC_PER_KIND)));
        trace.into(out);
        log.info("[Sim] 시뮬레이션 데이터 생성 — {} · 파일 {}개{} · SQL {}문장 · {}", rows, files.size(),
                writeFiles ? "" : " (대용량 — 더미 파일 생략)", trace.total, tables.describe());
        return out;
    }

    // ── 초기화 ────────────────────────────────────────────────────────────

    /** Complete Clean — SIM 행 일괄 삭제 + 원본 스토리지의 더미 파일 삭제. */
    @Transactional
    public Map<String, Object> clean() {
        SqlTrace trace = new SqlTrace();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", db.label());
        out.put("dirsEnsured", dirs.ensureDirs());
        out.put("cleaned", cleanRows(trace));
        out.put("filesDeleted", deleteDummyFiles());
        trace.into(out);
        log.info("[Sim] 시뮬레이션 데이터 초기화 — {}", out);
        return out;
    }

    /** SIM 접두 행만 지운다 — 자식(xvarm·sm·re·im 통화) → 부모(im 수용자). 테이블이 없으면(REAL 인데 미구축) 사유만 남긴다. */
    private Map<String, Object> cleanRows(SqlTrace trace) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("xvarm", safeDelete(trace, tables.asysContentElement(), "ELEMENTID LIKE 'SIMDOC%'"));
        m.put("cmfi", safeDelete(trace, tables.smsmCmfiBs(), "CMMN_FILE_ID LIKE 'SIMCMFI%'"));
        m.put("meet", safeDelete(trace, tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'SIM-MEET-%'"));
        m.put("phone", safeDelete(trace, tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE 'SIM-PHONE-%'"));
        m.put("inmates", safeDelete(trace, tables.imscPtprDt(), "CORR_NO LIKE 'SIM%'"));
        return m;
    }

    private Object safeDelete(SqlTrace trace, String table, String where) {
        String sql = "DELETE FROM " + table + " WHERE " + where;
        try {
            trace.add(sql);
            return jdbc.update(sql);
        } catch (Exception e) {
            String msg = rootMessage(e);
            trace.mark("-- ↑ 실패(건너뜀): " + msg);
            log.warn("[Sim] 삭제 건너뜀 — {} ({})", table, msg);
            return "skip: " + msg;
        }
    }

    /** 원본 스토리지의 더미 파일({@code mock_meet_*} · {@code mock_phone_*})을 지운다. 우리가 만든 이름만 — 남의 파일은 손 대지 않는다. */
    private Map<String, Integer> deleteDummyFiles() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (VoiceKind k : VoiceKind.values()) {
            Path dir = dirs.xvarmOriginalDir(k);
            String prefix = k == VoiceKind.MEET ? "mock_meet_" : "mock_phone_";
            int n = 0;
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path f : s.filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().startsWith(prefix)).toList()) {
                        Files.deleteIfExists(f);
                        n++;
                    }
                } catch (IOException e) {
                    log.warn("[Sim] 더미 파일 삭제 실패 — {} ({})", dir, e.getMessage());
                }
            }
            m.put(k.name(), n);
        }
        return m;
    }

    // ── XVARM MOCK 테이블 ──────────────────────────────────────────────────

    /**
     * 개발계 PostgreSQL 에 누락된 공통파일기본·XVARM 테이블을 만든다(CREATE … IF NOT EXISTS).
     * XVARM 모드가 REAL 이거나 H2(스키마 스크립트가 이미 만든다)면 건너뛴다.
     *
     * @return 무엇을 했는지 한 줄
     */
    public String ensureXvarmMockTables() {
        return ensureXvarmMockTables(null);
    }

    /** 위와 같되, 실행한 DDL 을 화면용 SQL 목록에 같이 남긴다. */
    private String ensureXvarmMockTables(SqlTrace trace) {
        if (!tables.isXvarmMock()) {
            return "REAL 모드 — 실 테이블(" + tables.smsmCmfiBs() + " · " + tables.asysContentElement() + ")을 그대로 쓴다";
        }
        if (db.isH2()) {
            return "H2 — schema-borami-mock.sql 이 이미 만든 평평한 테이블을 쓴다";
        }
        if (!db.isPostgres()) {
            throw new IllegalStateException("XVARM MOCK 테이블 자동 생성은 PostgreSQL(개발계)만 지원한다 — 현재 " + db.label());
        }
        String sm = tables.smsmSchema();
        String xv = tables.xvarmSchema();
        String ddl;
        try {
            ddl = new String(new ClassPathResource("sql/xvarm_mock_tables_postgres.sql").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("DDL 리소스를 읽지 못했다: sql/xvarm_mock_tables_postgres.sql", e);
        }
        // 주석을 먼저 걷어낸 뒤 세미콜론으로 문장을 나눈다 — 주석 안의 세미콜론이 문장을 쪼개던 문제
        StringBuilder body = new StringBuilder();
        ddl.replace("__SM__", sm).replace("__XVARM__", xv).lines().forEach(l -> {
            int c = l.indexOf("--");
            body.append(c >= 0 ? l.substring(0, c) : l).append('\n');
        });
        int n = 0;
        for (String stmt : body.toString().split(";")) {
            String sql = stmt.trim();
            if (sql.isEmpty()) {
                continue;
            }
            if (trace != null) {
                trace.add(sql);
            }
            jdbc.execute(sql);
            n++;
        }
        String msg = "PostgreSQL — %s.tb_smsm_cmfi_bs · %s.asyscontentelement 확인/생성 (%d문장)".formatted(sm, xv, n);
        log.info("[Sim] {}", msg);
        return msg;
    }

    // ── SQL 트레이스 ──────────────────────────────────────────────────────

    /** 바인딩 실행 + 화면용 SQL 기록. */
    private void exec(SqlTrace trace, String sql, Object... args) {
        trace.add(sql, args);
        jdbc.update(sql, args);
    }

    /** 배치 실행 + 화면용 SQL 기록(행마다 한 문장으로 풀어 적는다). */
    private void batch(SqlTrace trace, String sql, List<Object[]> rows) {
        for (Object[] r : rows) {
            trace.add(sql, r);
        }
        jdbc.batchUpdate(sql, rows);
    }

    /**
     * 시딩에 쓴 SQL 을 <b>화면에 보여 주기 위해</b> 모아 둔다.
     *
     * <p><b>여기서 만드는 문자열은 표시 전용이다 — 실행되는 SQL 이 아니다.</b> 실제 실행은 끝까지
     * 바인딩({@code ?})이고, 이 클래스는 읽는 사람이 DBeaver 에 그대로 붙여 넣어 대조할 수 있도록
     * 값만 끼워 넣은 사본을 만든다. 그래서 문자열 값은 따옴표를 겹쳐 이스케이프한다 — SQL 주입 경로가
     * 아니라 <b>보이는 문장이 실제로 실행된 것과 달라지지 않게</b> 하려는 것이다.</p>
     *
     * <p>{@link #SQL_TRACE_LIMIT} 를 넘으면 더 담지 않고 총 건수만 센다.</p>
     */
    private static final class SqlTrace {

        private static final java.time.format.DateTimeFormatter TS_FMT =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        private final List<String> lines = new ArrayList<>();
        private int total;

        void add(String sql, Object... args) {
            total++;
            if (lines.size() < SQL_TRACE_LIMIT) {
                lines.add(render(sql, args));
            }
        }

        /** 앞 문장에 붙는 한 줄 메모(실패 사유 등). 총 건수에는 넣지 않는다. */
        void mark(String note) {
            if (lines.size() < SQL_TRACE_LIMIT) {
                lines.add(note);
            }
        }

        void into(Map<String, Object> out) {
            out.put("sqls", List.copyOf(lines));
            out.put("sqlTotal", total);
            out.put("sqlTruncated", total > lines.size());
        }

        private static String render(String sql, Object... args) {
            String one = sql.replaceAll("\\s+", " ").trim();
            StringBuilder sb = new StringBuilder(one.length() + 64);
            int ai = 0;
            for (int i = 0; i < one.length(); i++) {
                char c = one.charAt(i);
                if (c == '?' && ai < args.length) {
                    sb.append(literal(args[ai++]));
                } else {
                    sb.append(c);
                }
            }
            return sb.append(';').toString();
        }

        private static String literal(Object v) {
            if (v == null) {
                return "NULL";
            }
            if (v instanceof Number || v instanceof Boolean) {
                return String.valueOf(v);
            }
            if (v instanceof Timestamp t) {
                // toString() 은 'T' 를 끼우고 초가 0이면 떨어뜨린다 — 붙여 넣어 쓸 수 있게 고정 형식으로 적는다
                return "TIMESTAMP '" + TS_FMT.format(t.toLocalDateTime()) + "'";
            }
            return "'" + String.valueOf(v).replace("'", "''") + "'";
        }
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    /**
     * i 번째 건의 발생 시각 — 1~daily 는 어제 09:10 부터 10분 간격(하루 안에서 돈다), 그 뒤 2건은 지금-6분/지금-3분.
     */
    static LocalDateTime occurredAt(LocalDateTime now, int i, int daily) {
        if (i <= daily) {
            long minutes = 9 * 60 + ((10L * i) % (14 * 60));      // 09:10 ~ 22:59
            return now.toLocalDate().minusDays(1).atStartOfDay().plusMinutes(minutes).plusSeconds((10L * i) / (14 * 60));
        }
        int k = i - daily;                                      // 1, 2
        return now.minusMinutes(3L * (PERIODIC_PER_KIND - k + 1));   // 1 → -6분, 2 → -3분
    }

    /** 세 자리를 넘는 대용량은 자릿수를 늘린다 — 키 형식은 시연 10건(SIM-MEET-001…)과 같다. */
    private static String seq(int i) { return i < 1000 ? "%03d".formatted(i) : "%05d".formatted(i); }

    public static String corrNo(int i) { return "SIM%014d".formatted(i); }
    public static String meetKey(int i) { return "SIM-MEET-" + seq(i); }
    public static String phoneKey(int i) { return "SIM-PHONE-" + seq(i); }
    public static String meetFileName(int i) { return "mock_meet_" + seq(i) + ".m4a"; }
    public static String phoneFileName(int i) { return "mock_phone_" + seq(i) + ".wav"; }

    private static String ymd(LocalDateTime t) { return "%04d%02d%02d".formatted(t.getYear(), t.getMonthValue(), t.getDayOfMonth()); }
    private static String hms(LocalDateTime t) { return "%02d%02d%02d".formatted(t.getHour(), t.getMinute(), t.getSecond()); }
    private static String dt14(LocalDateTime t) { return ymd(t) + hms(t); }
    private static String slash(Path p) { return p.toAbsolutePath().normalize().toString().replace('\\', '/'); }

    private int count(String table, String where) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return n == null ? 0 : n;
    }

    /** 경량 더미 — 생성 여부만 확인하면 되므로 한 줄짜리 텍스트(수십 byte)다. 실제 오디오가 아니다. */
    private Map<String, Object> writeDummy(Path file, String kind, String key) {
        return writeText(file, "SIMULATION DUMMY " + kind + " — " + key + " — 실제 오디오가 아닙니다\n");
    }

    private Map<String, Object> writeText(Path file, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", slash(file));
        try {
            Files.createDirectories(file.getParent());
            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            Files.write(file, body);
            m.put("bytes", body.length);
            m.put("written", true);
        } catch (IOException e) {
            m.put("written", false);
            m.put("error", e.getMessage());
            log.warn("[Sim] 더미 파일 생성 실패 — {} ({})", file, e.getMessage());
        }
        return m;
    }

    private Map<String, Object> fileStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (VoiceKind k : VoiceKind.values()) {
            Path dir = dirs.xvarmOriginalDir(k);
            List<String> names = new ArrayList<>();
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    s.filter(Files::isRegularFile).map(f -> f.getFileName().toString()).sorted().forEach(names::add);
                } catch (IOException ignored) {
                    // 목록을 못 읽으면 빈 목록 — 상태 조회가 실패를 만들면 안 된다
                }
            }
            String prefix = k == VoiceKind.MEET ? "mock_meet_" : "mock_phone_";
            List<String> mine = names.stream().filter(n -> n.startsWith(prefix)).toList();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("dir", slash(dir));
            d.put("exists", Files.isDirectory(dir));
            d.put("count", mine.size());
            d.put("names", mine);
            m.put(k.name(), d);
        }
        return m;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String s = cur.getMessage();
        return s == null ? cur.getClass().getSimpleName() : s.replaceAll("\\s+", " ").trim();
    }
}
