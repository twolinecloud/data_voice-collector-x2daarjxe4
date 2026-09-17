package egovframework.voice.collector.source;

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
 * <p><b>구성 (접견 5 · 전화 5 = 10건)</b>: 트랙마다 3건은 <b>어제 09:10/09:20/09:30</b>(일배치 창), 2건은
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

    public static final int MEET_COUNT = 5;
    public static final int PHONE_COUNT = 5;
    /** 앞 3건은 일배치(어제), 뒤 2건은 주기배치(최근 10분). */
    public static final int DAILY_PER_KIND = 3;
    /** 이 번호의 전화 건은 "보라미가 이미 STT 를 가지고 있는" 시나리오(계획서 Q1). */
    public static final int PHONE_WITH_SOURCE_STT = 3;
    /** 이 번호의 접견 건은 암호화되지 않은 파일 — 복호화가 통과(Noop)하는지 본다. */
    public static final int MEET_UNENCRYPTED = 2;

    private static final String USR = "simadm";
    /** 특이수용자 코드 — 001 만 마약(1) 이라 코드 필터 테스트의 기준이 된다. */
    private static final String[] SPECL_CODES = {"1", "2", "3", "0", "5"};

    private final JdbcTemplate jdbc;
    private final BoramiTableNames tables;
    private final DbKindDetector db;
    private final VoiceDirState dirs;
    private final VoiceProperties props;

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
     * Clean &amp; Seed — 기존 SIM 행·더미 파일을 지운 뒤 10건을 새로 만든다.
     *
     * @return 결과 요약(테이블별 행 수 · 파일 목록 · 폴더)
     */
    @Transactional
    public Map<String, Object> seed() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", db.label());
        out.put("ensured", ensureXvarmMockTables());
        Map<String, Object> cleaned = cleanRows();
        out.put("cleaned", cleaned);

        LocalDateTime now = LocalDateTime.now().withNano(0);
        Timestamp ts = Timestamp.valueOf(now);
        // 특이수용자 5명 — 접견·전화가 같은 사람(001~005)을 쓴다
        for (int i = 1; i <= 5; i++) {
            jdbc.update("INSERT INTO " + tables.imscPtprDt()
                    + " (CORR_NO, PTCR_PRSR_DTL_SN, SPECL_MNG_SE_CD, PTCR_PRSR_SE_CD, PTCR_PRSR_APNT_YMD, PTCR_PRSR_RMV_YMD,"
                    + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    corrNo(i), 1, SPECL_CODES[i - 1], "A01", "20260101", null, ts, USR, ts, USR);
        }

        List<Map<String, Object>> files = new ArrayList<>();
        Path meetDir = dirs.xvarmOriginalDir(VoiceKind.MEET);
        Path phoneDir = dirs.xvarmOriginalDir(VoiceKind.PHONE);

        // 접견 5건 — re → im → sm → xvarm
        for (int i = 1; i <= MEET_COUNT; i++) {
            LocalDateTime at = occurredAt(now, i);
            Timestamp crt = Timestamp.valueOf(at);
            String fileNm = meetFileName(i);
            Path file = meetDir.resolve(fileNm);
            String cmfi = "SIMCMFI%04d".formatted(i);
            String doc = "SIMDOC%04d".formatted(i);
            jdbc.update("INSERT INTO " + tables.smsmCmfiBs()
                    + " (CMMN_FILE_ID, DOC_ID, FILE_NM, CORR_WRK_SE_CD, FILE_TY_CD, REG_DT, RPRS_YN, CMMN_FILE_ENC_YN, DEL_YN,"
                    + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    cmfi, doc, fileNm, "01", "A", crt, "Y", i == MEET_UNENCRYPTED ? "N" : "Y", "N", crt, USR, crt, USR);
            jdbc.update("INSERT INTO " + tables.asysContentElement() + " (ELEMENTID, FILEKEY) VALUES (?,?)",
                    doc, slash(file));
            jdbc.update("INSERT INTO " + tables.rerdTfinDs()
                    + " (TARE_FILE_NO, CORR_INSTT_CD, ADNC_SE_CD, RCPT_YMD, RCPT_SN, CORR_NO, ADNC_YMD,"
                    + "  TBLT_RECRD_FILE_ID, TBLT_VTR_FILE_ID, TARE_FILE_NM, TARE_BGNG_HMS, TARE_END_HMS, TARE_FILE_MG_VL, TARE_FLPTH_NM,"
                    + "  DEL_YN, RECRD_FILE_DEL_YN, RECRD_BKUP_FILE_DEL_YN, CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    meetKey(i), "CI00001", "01", ymd(at), i, corrNo(i), ymd(at),
                    "SIMTRCD%04d".formatted(i), cmfi, fileNm, hms(at), hms(at.plusMinutes(10)), "1024", slash(meetDir),
                    "N", "N", "N", crt, USR, crt, USR);
            files.add(writeDummy(file, "m4a", meetKey(i)));
        }

        // 전화 5건 — im 통화내역 → im 특이수용자
        for (int i = 1; i <= PHONE_COUNT; i++) {
            LocalDateTime at = occurredAt(now, i);
            Timestamp crt = Timestamp.valueOf(at);
            String fileNm = phoneFileName(i);
            Path file = phoneDir.resolve(fileNm);
            String sttPath = null;
            if (i == PHONE_WITH_SOURCE_STT) {
                Path stt = phoneDir.resolve("mock_phone_%03d.stt.txt".formatted(i));
                files.add(writeText(stt, "(보라미 기존 STT / 시뮬레이션) 여보세요 저 김수용입니다. 어머니 잘 계시죠.\n"
                        + "연락처 010-9876-5432 로 전화 주세요. 주민번호는 900101-1234567 입니다.\n"));
                sttPath = slash(stt);
            }
            jdbc.update("INSERT INTO " + tables.imphUcdrDs()
                    + " (VRFC_ESTL_ID, PCALL_KND_CD, TELP_USR_SCPT_SE_CD, CORR_NO, TELP_LST_SE_CD, RCVER_NM, ACQT_RLTNS_NM, INTRL_TELNO,"
                    + "  TELP_PCALL_BGNG_DT, TELP_PCALL_RSPNS_DT, TELP_PCALL_END_DT, TELP_PCALL_TIME, TELP_RSPNS_TIME, TELP_PCALL_RSPNS_YN,"
                    + "  TELP_PCALL_OCRN_AMT, TELP_PCALL_RECRD_YN, TELP_PTCR_PRSR_YN, TELP_PTCR_PRSR_TCNT, CORR_INSTT_CD, TELP_USE_PLACE_NM,"
                    + "  TELP_RECRD_FLPTH_NM, TELP_RECRD_FILE_NM, TELP_RECRD_FILE_ID, TELP_STT_FLPTH_NM, DEL_DT,"
                    + "  CRT_DT, CRT_USR_ID, MDFCN_DT, MDFCN_USR_ID)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    phoneKey(i), "P001", "01", corrNo(i), "L001", "(시뮬레이션)수신자" + i, "(시뮬레이션)관계", "000-0000-000" + i,
                    dt14(at), dt14(at.plusSeconds(5)), dt14(at.plusMinutes(3)), 180, 5, "Y",
                    0, "Y", props.source().flag().ptcrYes(), 1, "CI00001", "(시뮬레이션)전화실",
                    slash(phoneDir), fileNm, "SIMPHONEKEY%04d".formatted(i), sttPath, null,
                    crt, USR, crt, USR);
            files.add(writeDummy(file, "wav", phoneKey(i)));
        }

        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("inmates", 5);
        rows.put("meet", MEET_COUNT);
        rows.put("phone", PHONE_COUNT);
        rows.put("cmfi", MEET_COUNT);
        rows.put("xvarm", MEET_COUNT);
        out.put("rows", rows);
        out.put("files", files);
        out.put("dirs", Map.of("meet", slash(meetDir), "phone", slash(phoneDir)));
        out.put("tables", tables.describe());
        out.put("windows", Map.of(
                "daily", "접견 3 · 전화 3 (어제 09:10/09:20/09:30)",
                "periodic", "접견 2 · 전화 2 (지금-6분 / 지금-3분)"));
        log.info("[Sim] 시뮬레이션 데이터 생성 — {} · 파일 {}개 · {}", rows, files.size(), tables.describe());
        return out;
    }

    // ── 초기화 ────────────────────────────────────────────────────────────

    /** Complete Clean — SIM 행 일괄 삭제 + 원본 스토리지의 더미 파일 삭제. */
    @Transactional
    public Map<String, Object> clean() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("db", db.label());
        out.put("cleaned", cleanRows());
        out.put("filesDeleted", deleteDummyFiles());
        log.info("[Sim] 시뮬레이션 데이터 초기화 — {}", out);
        return out;
    }

    /** SIM 접두 행만 지운다 — 자식(xvarm·sm·re·im 통화) → 부모(im 수용자). 테이블이 없으면(REAL 인데 미구축) 사유만 남긴다. */
    private Map<String, Object> cleanRows() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("xvarm", safeDelete(tables.asysContentElement(), "ELEMENTID LIKE 'SIMDOC%'"));
        m.put("cmfi", safeDelete(tables.smsmCmfiBs(), "CMMN_FILE_ID LIKE 'SIMCMFI%'"));
        m.put("meet", safeDelete(tables.rerdTfinDs(), "TARE_FILE_NO LIKE 'SIM-MEET-%'"));
        m.put("phone", safeDelete(tables.imphUcdrDs(), "VRFC_ESTL_ID LIKE 'SIM-PHONE-%'"));
        m.put("inmates", safeDelete(tables.imscPtprDt(), "CORR_NO LIKE 'SIM%'"));
        return m;
    }

    private Object safeDelete(String table, String where) {
        try {
            return jdbc.update("DELETE FROM " + table + " WHERE " + where);
        } catch (Exception e) {
            String msg = rootMessage(e);
            log.warn("[Sim] 삭제 건너뜀 — {} ({})", table, msg);
            return "skip: " + msg;
        }
    }

    /** 원본 스토리지(meet·phone)의 더미 파일({@code mock_*})을 지운다. 우리가 만든 이름만 — 남의 파일은 두 손 댄다. */
    private Map<String, Integer> deleteDummyFiles() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (VoiceKind k : VoiceKind.values()) {
            Path dir = dirs.xvarmOriginalDir(k);
            int n = 0;
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path f : s.filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().startsWith("mock_")).toList()) {
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
            jdbc.execute(sql);
            n++;
        }
        String msg = "PostgreSQL — %s.tb_smsm_cmfi_bs · %s.asyscontentelement 확인/생성 (%d문장)".formatted(sm, xv, n);
        log.info("[Sim] {}", msg);
        return msg;
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    /** i 번째 건의 발생 시각 — 1~3 은 어제 09:10/09:20/09:30, 4~5 는 지금-6분/지금-3분. */
    static LocalDateTime occurredAt(LocalDateTime now, int i) {
        if (i <= DAILY_PER_KIND) {
            return now.toLocalDate().minusDays(1).atTime(9, 0).plusMinutes(10L * i);
        }
        return now.minusMinutes(3L * (MEET_COUNT - i + 1));   // 4 → -6분, 5 → -3분
    }

    public static String corrNo(int i) { return "SIM%014d".formatted(i); }
    public static String meetKey(int i) { return "SIM-MEET-%03d".formatted(i); }
    public static String phoneKey(int i) { return "SIM-PHONE-%03d".formatted(i); }
    public static String meetFileName(int i) { return "mock_meet_%03d.m4a".formatted(i); }
    public static String phoneFileName(int i) { return "mock_phone_%03d.wav".formatted(i); }

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
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("dir", slash(dir));
            d.put("exists", Files.isDirectory(dir));
            d.put("count", names.size());
            d.put("names", names);
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
