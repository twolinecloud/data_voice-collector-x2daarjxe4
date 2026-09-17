package egovframework.voice.collector.config;

import egovframework.voice.collector.model.VoiceKind;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 디렉터리 5종의 <b>런타임 상태</b> — 서버를 재시작하지 않고 경로를 바꾼다.
 *
 * <p>{@link VoiceModeState} 와 같은 이유로 런타임에 둔다. 로컬에서는 브로커가 떨구는 폴더에,
 * 개발계에서는 PV 마운트 지점({@code /k8s})에 맞춰야 하는데, 경로가 어긋나면 배치는 "추출 완료" 뒤
 * 빈 폴더를 보며 타임아웃이 난다. 시뮬레이터에서 프리셋을 고르는 즉시 반영되어야 그 자리에서 잡힌다.</p>
 *
 * <p><b>기동 초기값은 설정({@link VoiceProperties.Dirs})에서 온다.</b> 런타임 변경은 이 프로세스에만 남고
 * 재기동하면 설정값으로 돌아간다. 모든 필드가 {@code volatile} — 배치 스레드와 API 스레드가 동시에 본다.</p>
 *
 * <p><b>STT 출력 경로 규칙</b>: {@code {outputMeet}/{execId}/} · {@code {outputPhone}/{execId}/}.
 * 배치 단위로 폴더를 나눠 재처리·시험 삭제가 폴더 단위로 끝나게 한다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class VoiceDirState {

    /** 표준 배치 — base-dir 아래 상대 경로. 프리셋과 {@link #layoutOf} 가 같은 표를 쓴다. */
    private static final String REL_RECEIVE_MEET = "voice_raw/meet";
    private static final String REL_RECEIVE_PHONE = "voice_raw/phone";
    private static final String REL_WORK = "voice_work";
    private static final String REL_OUTPUT_MEET = "xenon/voice";
    private static final String REL_OUTPUT_PHONE = "xenon/phone";
    /** XVARM 원본 음성 스토리지 폴더명 — base(C:/ 또는 /k8s) 바로 아래. */
    public static final String XVARM_ORIGINAL_DIR = "XVARM_ORIGINAL_VOICE_FILES";

    private final VoiceProperties props;

    private volatile String baseDir;
    private volatile String receiveMeet;
    private volatile String receivePhone;
    private volatile String work;
    private volatile String outputMeet;
    private volatile String outputPhone;
    /** XVARM 원본 음성 스토리지 뿌리 — 아래에 meet/ · phone/ 가 있다. 시뮬레이션 더미 파일이 여기에 놓인다. */
    private volatile String xvarmOriginalBase;

    @PostConstruct
    void init() {
        VoiceProperties.Dirs d = props.dirs();
        this.baseDir = norm(d.baseDir());
        this.receiveMeet = norm(d.receiveMeet());
        this.receivePhone = norm(d.receivePhone());
        this.work = norm(d.work());
        this.outputMeet = norm(d.outputMeet());
        this.outputPhone = norm(d.outputPhone());
        this.xvarmOriginalBase = configuredXvarmOriginalBase();
        log.info("[Dirs] 초기 경로 — base={} 수신(접견={} 전화={}) 작업={} 출력(접견={} 전화={}) XVARM원본={}",
                baseDir, receiveMeet, receivePhone, work, outputMeet, outputPhone, xvarmOriginalBase);
        ensureDirs();
    }

    /** 설정값이 비면 OS 로 정한다 — Windows {@code C:/XVARM_ORIGINAL_VOICE_FILES}, 그 외 {@code /k8s/XVARM_ORIGINAL_VOICE_FILES}. */
    private String configuredXvarmOriginalBase() {
        String v = norm(props.dirs().xvarmOriginalBase());
        return v.isEmpty() ? defaultXvarmOriginalBase() : v;
    }

    public static String defaultXvarmOriginalBase() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return (os.contains("win") ? "C:/" : "/k8s/") + XVARM_ORIGINAL_DIR;
    }

    /**
     * 우리가 쓰는 폴더를 미리 만든다(CREATE_IF_NOT_EXISTS) — 수신·작업·출력·XVARM 원본.
     * 못 만들어도 기동은 막지 않는다(권한·마운트 문제는 배치 때 사유와 함께 드러난다).
     */
    public void ensureDirs() {
        for (String d : List.of(receiveMeet, receivePhone, work, outputMeet, outputPhone,
                xvarmOriginalMeet(), xvarmOriginalPhone())) {
            try {
                java.nio.file.Files.createDirectories(Path.of(d));
            } catch (Exception e) {
                log.warn("[Dirs] 디렉터리 생성 실패 — {} ({})", d, e.getMessage());
            }
        }
    }

    public String xvarmOriginalBase() { return xvarmOriginalBase; }
    /** 접견 원본 더미 파일 폴더 — {@code {xvarmOriginalBase}/meet}. */
    public String xvarmOriginalMeet() { return xvarmOriginalBase + "/meet"; }
    /** 전화 원본 더미 파일 폴더 — {@code {xvarmOriginalBase}/phone}. */
    public String xvarmOriginalPhone() { return xvarmOriginalBase + "/phone"; }

    /** 종류별 XVARM 원본 폴더. */
    public Path xvarmOriginalDir(VoiceKind kind) {
        return Path.of(kind == VoiceKind.MEET ? xvarmOriginalMeet() : xvarmOriginalPhone());
    }

    public String baseDir() { return baseDir; }
    public String receiveMeet() { return receiveMeet; }
    public String receivePhone() { return receivePhone; }
    public String work() { return work; }
    public String outputMeet() { return outputMeet; }
    public String outputPhone() { return outputPhone; }

    /** 종류별 수신 디렉터리. */
    public Path receiveDir(VoiceKind kind) {
        return Path.of(kind == VoiceKind.MEET ? receiveMeet : receivePhone);
    }

    /** 종류별 STT 출력 뿌리(EXEC_ID 폴더의 부모). */
    public Path outputRoot(VoiceKind kind) {
        return Path.of(kind == VoiceKind.MEET ? outputMeet : outputPhone);
    }

    /**
     * 배치 1회의 STT 출력 디렉터리 — {@code {output}/{execId}/}.
     * EXEC_ID 는 컬렉터 채번값(영숫자)이지만 로컬 임시 ID 도 올 수 있어 파일명에 못 쓰는 문자를 걸러 낸다.
     */
    public Path outputDir(VoiceKind kind, String execId) {
        String safe = (execId == null || execId.isBlank()) ? "UNKNOWN" : execId.replaceAll("[^A-Za-z0-9_.-]", "_");
        return outputRoot(kind).resolve(safe);
    }

    /** 현재 경로 전체(조회·화면 표시용). */
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("baseDir", baseDir);
        m.put("receiveMeet", receiveMeet);
        m.put("receivePhone", receivePhone);
        m.put("work", work);
        m.put("outputMeet", outputMeet);
        m.put("outputPhone", outputPhone);
        m.put("xvarmOriginalBase", xvarmOriginalBase);
        m.put("xvarmOriginalMeet", xvarmOriginalMeet());
        m.put("xvarmOriginalPhone", xvarmOriginalPhone());
        return m;
    }

    /** 기동 시 설정값(되돌리기 기준). */
    public Map<String, String> configured() {
        VoiceProperties.Dirs d = props.dirs();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("baseDir", norm(d.baseDir()));
        m.put("receiveMeet", norm(d.receiveMeet()));
        m.put("receivePhone", norm(d.receivePhone()));
        m.put("work", norm(d.work()));
        m.put("outputMeet", norm(d.outputMeet()));
        m.put("outputPhone", norm(d.outputPhone()));
        String xb = configuredXvarmOriginalBase();
        m.put("xvarmOriginalBase", xb);
        m.put("xvarmOriginalMeet", xb + "/meet");
        m.put("xvarmOriginalPhone", xb + "/phone");
        return m;
    }

    /**
     * base-dir 하나로 표준 배치를 만든다 — 시뮬레이터 프리셋과 [base-dir 로 채우기] 가 쓴다.
     * 수신 폴더까지 base 아래로 두므로, 로컬 브로커를 같이 쓸 때는 브로커의 {@code BROKER_OUTPUT_DIR} 도 맞춰야 한다.
     */
    public static Map<String, String> layoutOf(String base) {
        String b = norm(base);
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("baseDir", b);
        m.put("receiveMeet", b + "/" + REL_RECEIVE_MEET);
        m.put("receivePhone", b + "/" + REL_RECEIVE_PHONE);
        m.put("work", b + "/" + REL_WORK);
        m.put("outputMeet", b + "/" + REL_OUTPUT_MEET);
        m.put("outputPhone", b + "/" + REL_OUTPUT_PHONE);
        // XVARM 원본은 드라이브/마운트 뿌리 바로 아래 — C:/k8s → C:/XVARM_…, /k8s → /k8s/XVARM_…
        String xb = (b.matches("^[A-Za-z]:(/.*)?$") ? b.substring(0, 3).replaceAll("/$", "") + "/" : b + "/") + XVARM_ORIGINAL_DIR;
        m.put("xvarmOriginalBase", xb);
        m.put("xvarmOriginalMeet", xb + "/meet");
        m.put("xvarmOriginalPhone", xb + "/phone");
        return m;
    }

    /**
     * 시뮬레이터가 한 번에 고를 수 있는 프리셋.
     * <ul>
     *   <li><b>로컬 기본(설정값)</b> — 기동 시 설정. 로컬은 브로커 local 프로파일이 떨구는 {@code ./work/voice_raw/meet} 와 맞는다</li>
     *   <li><b>Windows C:/k8s</b> — 로컬 PC 에서 PV 구조를 그대로 흉내 낼 때</li>
     *   <li><b>PV /k8s</b> — 개발계 파드의 PVC 마운트 지점</li>
     * </ul>
     */
    public List<Map<String, Object>> presets() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(preset("로컬 기본 (설정값)", "configured", configured()));
        out.add(preset("Windows C:/k8s", "win", layoutOf("C:/k8s")));
        out.add(preset("PV /k8s", "pv", layoutOf("/k8s")));
        return out;
    }

    private static Map<String, Object> preset(String label, String key, Map<String, String> dirs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("dirs", dirs);
        return m;
    }

    /**
     * 경로를 바꾼다. 주어진 키만 바꾸고 나머지는 그대로 둔다.
     *
     * @param values baseDir / receiveMeet / receivePhone / work / outputMeet / outputPhone → 경로
     * @return 바뀌기 전 값 전체
     * @throws IllegalArgumentException 모르는 키, 빈 값
     */
    public Map<String, String> set(Map<String, String> values) {
        Map<String, String> before = snapshot();
        if (values == null || values.isEmpty()) {
            return before;
        }
        // 전부 검증한 뒤에 반영한다 — 절반만 바뀐 상태로 배치가 돌면 안 된다.
        Map<String, String> next = new LinkedHashMap<>(before);
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim();
            String val = norm(e.getValue());
            if (key.equals("xvarmOriginalMeet") || key.equals("xvarmOriginalPhone")) {
                continue;   // 파생 경로 — base 로만 바꾼다
            }
            if (!before.containsKey(key)) {
                throw new IllegalArgumentException("알 수 없는 디렉터리 키: " + key
                        + " (baseDir/receiveMeet/receivePhone/work/outputMeet/outputPhone/xvarmOriginalBase)");
            }
            if (val.isEmpty()) {
                throw new IllegalArgumentException(key + " 경로가 비어 있다");
            }
            next.put(key, val);
        }
        this.baseDir = next.get("baseDir");
        this.receiveMeet = next.get("receiveMeet");
        this.receivePhone = next.get("receivePhone");
        this.work = next.get("work");
        this.outputMeet = next.get("outputMeet");
        this.outputPhone = next.get("outputPhone");
        this.xvarmOriginalBase = next.get("xvarmOriginalBase");
        log.info("[Dirs] 경로 변경 — {} → {}", before, snapshot());
        ensureDirs();
        return before;
    }

    /** 기동 시 설정값으로 되돌린다. */
    public void resetToConfigured() {
        init();
    }

    /** 표기를 통일한다 — 역슬래시를 슬래시로. Windows 도 슬래시를 받아들이고, 화면·로그에서 비교하기 쉽다. */
    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        String v = s.trim().replace('\\', '/');
        // 뒤 슬래시는 EXEC_ID 를 붙일 때 이중이 된다(루트 "/" 는 예외).
        while (v.length() > 1 && v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /** 절대경로 표기(화면 대조용). 상대경로는 프로세스 작업 디렉터리 기준. */
    public static String toAbs(String p) {
        try {
            return Path.of(p).toAbsolutePath().normalize().toString().replace('\\', '/');
        } catch (Exception e) {
            return p;
        }
    }

    /** 이 OS 의 표준 base-dir 제안값 — Windows 는 {@code C:/k8s}, 그 외 {@code /k8s}. */
    public static String suggestedBaseDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "C:/k8s" : "/k8s";
    }
}
