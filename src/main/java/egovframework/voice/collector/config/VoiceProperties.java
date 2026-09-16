package egovframework.voice.collector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 음성 수집 서비스 설정 — 외부 의존 4종을 <b>각각 독립 스위치</b>로 둔다.
 *
 * <p>이 과제는 보라미 조회·XVARM 브로커·복호화·STT 네 가지가 서로 다른 시점에 준비된다.
 * 하나라도 막히면 전부 멈추는 구조를 피하려고, 준비된 것부터 REAL 로 돌리고 나머지는 MOCK 으로
 * 둘 수 있게 했다.
 * <b>REAL 이면 실서버만 호출하고 실패 시 Mock 으로 빠지지 않는다</b>(Fail-fast).
 * Mock 결과가 실제인 양 섞이면 시연·검증이 전부 무의미해지기 때문이다.</p>
 */
@ConfigurationProperties(prefix = "voice")
public record VoiceProperties(
        @DefaultValue Source source,
        @DefaultValue Broker broker,
        @DefaultValue Phone phone,
        @DefaultValue Sync sync,
        @DefaultValue Dirs dirs,
        @DefaultValue Decrypt decrypt,
        @DefaultValue Stt stt,
        @DefaultValue Batch batch
) {

    /** 보라미 대상 조회 경로. */
    public record Source(
            @DefaultValue("MOCK") SourceMode mode,
            /** ESB_HTTP2DB 모드에서 쓸 연계 서버 주소. 표준 A 규약상 {@code https://{ip}:{port}/{인터페이스ID}} */
            @DefaultValue("") String esbBaseUrl,
            /** 인터페이스ID — 표준 A 명명규칙 {@code IF_송신(3)_수신(3)_일련(3)}. 미확정이라 설정으로 뺀다. */
            @DefaultValue("") String interfaceId,
            @DefaultValue Schema schema,
            @DefaultValue Flag flag
    ) {}

    /**
     * 보라미 테이블이 놓인 스키마.
     *
     * <p><b>실제 보라미는 테이블명 접두어가 곧 스키마다</b> — 2026-09-12 실DB 확인 결과
     * {@code im.tb_imsc_ptpr_dt}, {@code re.tb_rerd_tfin_ds}, {@code im.tb_imph_ucdr_ds} 였다.
     * 회의 축어록 {@code [00:17:26]} 의 "TVG의 RE면 RE점, IM이면 IM점" 이 이것이다.</p>
     *
     * <p>반면 개발용 H2 Mock 은 스키마 없이 평평하다. 두 쪽에서 같은 SQL 이 돌게 하려고
     * <b>비우면 수식 없이</b>, 채우면 {@code 스키마.테이블} 로 조립한다.</p>
     */
    public record Schema(
            /** 특이수용자상세·사용자통화내역 — 실제 {@code im} */
            @DefaultValue("") String imsc,
            /** 녹취파일내역 — 실제 {@code re} */
            @DefaultValue("") String rerd,
            /** 공통파일기본 — 실제 {@code sm} (2026-09-12 기준 borami-db 에 테이블 자체가 없음) */
            @DefaultValue("") String smsm,
            /** XVARM 콘텐츠 메타 — 실제 스키마 미확인 (borami-db 에 없음) */
            @DefaultValue("") String xvarm
    ) {}

    /**
     * 플래그 컬럼의 <b>값 도메인</b>.
     *
     * <p><b>하드코딩하면 안 되는 이유</b>: 스펙 문서에는 {@code CHAR NOT NULL} 이라고만 적혀 있고
     * 어떤 값이 들어가는지 없다. 실DB 를 열어 보니 {@code TELP_PTCR_PRSR_YN} 은 {@code 'Y'} 가 아니라
     * <b>{@code '0'}</b> 이었다. {@code 'Y'} 로 비교하던 지름길 필터는 영원히 0건을 돌려준다 —
     * 에러가 아니라 "대상 없음" 으로 보여서 알아채기도 어렵다.</p>
     */
    public record Flag(
            /** {@code TELP_PCALL_RECRD_YN} 의 '녹음됨' 값 */
            @DefaultValue("Y") String recordedYes,
            /** {@code TELP_PTCR_PRSR_YN} 의 '특이수용자' 값 — 실DB 관측값은 '0' 계열이었다 */
            @DefaultValue("Y") String ptcrYes,
            /** {@code DEL_YN}·{@code RECRD_FILE_DEL_YN} 의 '삭제 안 됨' 값 */
            @DefaultValue("N") String notDeleted,
            /** {@code CMMN_FILE_ENC_YN} 의 '암호화됨' 값 */
            @DefaultValue("Y") String encrypted
    ) {}

    /** 보라미 WAS 안에 배포될 XVARM 브로커 호출 설정. */
    public record Broker(
            @DefaultValue("MOCK") BrokerMode mode,
            /**
             * 브로커 주소. 기동 초기값이며, 시뮬레이터에서 런타임으로 바꿀 수 있다
             * ({@code VoiceModeState.brokerBaseUrl}). 바꾼 값은 이 프로세스에만 남는다.
             */
            @DefaultValue("") String baseUrl,
            /**
             * 시뮬레이터의 원클릭 주소 프리셋. 환경마다 브로커가 사는 곳이 달라
             * (개발계는 K8s 서비스명, 로컬은 localhost) 매번 손으로 치게 하지 않으려는 것이다.
             * 설정으로 빼 둔 이유는 주소가 바뀌었을 때 화면 코드를 고치지 않게 하려는 것.
             */
            List<Preset> presets,
            /** 추출 완료를 기다리는 폴링 간격. 브로커는 202 로 받고 상태를 따로 알려준다. */
            @DefaultValue("2000") long pollIntervalMs,
            @DefaultValue("120") int pollTimeoutSec
    ) {
        public Broker {
            presets = (presets == null) ? List.of() : List.copyOf(presets);
        }
    }

    /** 시뮬레이터에서 한 번에 고를 수 있는 주소 후보. */
    public record Preset(String label, String url) {}

    /**
     * 전화 파일 연계 경로. <b>브로커(XVARM)와 무관한 별도 트랙이다.</b>
     *
     * <p>접견은 우리가 XVARM 브로커에 추출을 지시하지만, 전화는 보라미가 아닌
     * <b>별도 서버</b>에 파일이 있고 ESB 가 전화 전용 연계 프로바이더를 구성해 준다
     * (2026-09-11 회의 {@code [00:11:39]~[00:12:07]}). 우리는 요청만 하고 떨어지기를 기다린다.</p>
     *
     * <p>예전에는 이 모드를 브로커 스위치에 얹어 두었는데, 그러면 "XVARM 브로커" 를 REST 로
     * 올리는 순간 XVARM 을 타지도 않는 전화 경로까지 같이 바뀌어 버린다. 두 시나리오는
     * 연동 주체가 다르므로 스위치도 따로 둔다.</p>
     */
    public record Phone(
            @DefaultValue("MOCK") PhoneMode mode
    ) {}

    /** ESB 가 떨궈 준 파일을 집어오는 구간 — <b>대기 정책</b>만 둔다. 경로는 {@link Dirs} 에 있다. */
    public record Sync(
            /**
             * 파일 크기가 이 시간만큼 그대로여야 "쓰기가 끝났다"고 본다.
             * ESB Agent 가 쓰는 중인 파일을 읽으면 깨진 오디오를 STT 에 태우게 된다.
             */
            @DefaultValue("1500") long stableCheckMs,
            @DefaultValue("300") int waitTimeoutSec,
            /**
             * 수신 파일명 규칙. 표준 A 에 두 규칙이 병기되어 있어 확정 전까지 설정으로 둔다(Q7).
             * ORIGINAL = 업무 파일명 그대로 / ESB_DAT = {@code R_...DAT} 규약
             */
            @DefaultValue("ORIGINAL") egovframework.voice.collector.sync.EsbFileNamingPolicy.Policy namingPolicy
    ) {}

    /**
     * 디렉터리 5종 — <b>수신(접견·전화) · 작업 · STT 출력(접견·전화)</b>.
     *
     * <p><b>왜 한곳에 모았나</b>: 수신 폴더는 ESB·브로커가 떨궈 주는 곳(상대가 정한다), 작업 폴더는
     * 복호화 산출물·멱등 표식(우리 내부), 출력 폴더는 STT 결과가 <b>남는</b> 곳(하류가 읽는다)이다.
     * 셋의 성격이 달라 하나의 값으로 묶을 수 없지만, 어디에 무엇이 쌓이는지는 한 화면에서 보여야
     * 경로가 어긋났을 때 바로 잡힌다. 기동 초기값은 여기서 오고, 시뮬레이터에서 런타임으로
     * 바꿀 수 있다({@code VoiceDirState}).</p>
     *
     * <p><b>STT 출력은 배치 단위로 격리한다</b> — {@code {output}/{execId}/} 아래에 그 배치가 만든
     * 텍스트만 놓인다. 배치를 재처리하면 새 EXEC_ID 폴더가 생기고, 시험 배치(TST)는 폴더째 지운다.
     * 표준 배치는 {@code {base-dir}/xenon/voice/{execId}/}(접견) · {@code {base-dir}/xenon/phone/{execId}/}(전화) 다.
     * base-dir 는 Windows 로컬 {@code C:/k8s}, 배포(PV) {@code /k8s}.</p>
     */
    public record Dirs(
            /** 표준 배치의 뿌리. 출력 폴더 기본값이 여기서 파생된다(application.yml 의 placeholder). */
            @DefaultValue("./work") String baseDir,
            /** 접견 파일 수신 디렉터리 — 브로커·ESB 가 떨궈 주는 곳 */
            @DefaultValue("./work/voice_raw/meet") String receiveMeet,
            /** 전화 파일 수신 디렉터리 — ESB 전화 프로바이더가 떨궈 주는 곳 */
            @DefaultValue("./work/voice_raw/phone") String receivePhone,
            /** 복호화 산출물·멱등 표식 등 작업 공간 */
            @DefaultValue("./work/voice_work") String work,
            /** 접견 STT 결과 출력 뿌리 — 실제 파일은 {@code {outputMeet}/{execId}/} 아래 */
            @DefaultValue("./work/xenon/voice") String outputMeet,
            /** 전화 STT 결과 출력 뿌리 — 실제 파일은 {@code {outputPhone}/{execId}/} 아래 */
            @DefaultValue("./work/xenon/phone") String outputPhone
    ) {}

    /** 복호화. 전화 건의 주체가 아직 확정되지 않아 기본은 SKIP 이다(계획서 11장 Q13). */
    public record Decrypt(
            @DefaultValue("SKIP") DecryptMode mode,
            /** 접견 파일 복호화 키 파일({@code rvs_key.txt}) 경로. 아직 미수령(Q8). */
            @DefaultValue("") String rvsKeyPath
    ) {}

    /** STT(NPU) 연동. */
    public record Stt(
            @DefaultValue("MOCK") SttMode mode,
            @DefaultValue("") String baseUrl,
            @DefaultValue("300") int timeoutSec
    ) {}

    /** 배치 스케줄·대상 조건. */
    public record Batch(
            /** 일배치(정기배치) — 전날 00시부터 오늘 00시까지. 기본 새벽 2시. */
            @DefaultValue("0 0 2 * * *") String dailyCron,
            /** 주기배치 — 10분 단위. */
            @DefaultValue("0 */10 * * * *") String periodicCron,
            /** 주기배치가 훑는 지연 폭(분). 회의 합의는 "20분 전 것까지". */
            @DefaultValue("20") int periodicLagMin,
            /** 스케줄 자동 기동 여부. 개발·시연 중에는 수동 호출만 쓰도록 끌 수 있다. */
            @DefaultValue("false") boolean scheduleEnabled,
            /** 특별관리구분코드 — 조직(0)·마약(1)·관심(2)·엄격(3)·일일중점(5) */
            @DefaultValue({"0", "1", "2", "3", "5"}) List<String> speclMngSeCd,
            /** 한 번에 처리할 파일 상한(안전장치). */
            @DefaultValue("500") int maxFilesPerRun,
            /** 로그 컬렉터에 배치를 열 때 쓸 JOB_ID. 작업코드 VOC 로 채번되도록 협의 필요(Q4). */
            /**
             * 로그 컬렉터에 보낼 JOB_ID. 컬렉터가 이 값으로 EXEC_ID 접두 3자를 정한다
             * ({@code JobId} enum). {@code VOICE_ANALYSIS} → {@code VOC} — 계획서가 음성에 예약한 코드다.
             *
             * <p>⚠ 예전 값 {@code VOICE_BATCH} 는 컬렉터 enum 에 없어서 폴백 규칙("영문만 남겨 앞 3자")이
             * 돌았고, EXEC_ID 가 {@code ...VOI...} 로 채번됐다. 눈에 잘 안 띄는데, 작업코드로 배치를
             * 구분하는 모든 조회·정합성 대사가 어긋난다.</p>
             */
            @DefaultValue("VOICE_ANALYSIS") String jobId,
            /**
             * 시뮬레이터에서 돌리는 배치의 JOB_ID. {@code TEST_BATCH} → EXEC_ID 접두 {@code TST}.
             *
             * <p>컬렉터의 테스트 데이터 삭제({@code DELETE /api/v1/logs/test-data})가 이 JOB_ID 를
             * 기준으로 T1~T8 을 연쇄 삭제한다. 운영 배치(STR/VOC/EXT)는 그 조건에 걸리지 않으므로
             * 시연·시험 기록만 안전하게 지울 수 있다.</p>
             */
            @DefaultValue("TEST_BATCH") String testJobId,
            /**
             * 로그 컬렉터 T1 의 {@code DATA_TYPE_CD}(공통코드 C01, 4종 고정) — 음성은 <b>{@code UNSTRUCTURED}(비정형)</b> 다.
             *
             * <p>⚠ 예전 값 {@code VOICE} 는 C01 에 없는 코드였다. 컬렉터가 거절하지는 않았지만
             * 대시보드 4종 필터에 잡히지 않았고, T2 순번을 정하는 유형별 체인
             * (비정형: COLLECT → ANALYZE → DEIDENT → SEND)도 타지 못해 단계가 체인 밖 순번(11~)으로 밀렸다.</p>
             */
            @DefaultValue("UNSTRUCTURED") String dataTypeCd,
            /** STT 후 복호화 원본을 남길지. 기본은 삭제 — PII 보유를 최소화한다. */
            @DefaultValue("false") boolean retainSourceFile
    ) {}

    public enum SourceMode { MOCK, DIRECT_JDBC, ESB_HTTP2DB }

    public enum BrokerMode { MOCK, REST }

    /** 전화 파일 연계 — MOCK(내부 더미 생성) / ESB(연계 프로바이더가 떨궈 주기를 대기). */
    public enum PhoneMode { MOCK, ESB }

    public enum DecryptMode { SKIP, REAL }

    public enum SttMode { MOCK, NPU }
}
