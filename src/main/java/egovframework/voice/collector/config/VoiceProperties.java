package egovframework.voice.collector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 음성 수집 서비스 설정 — 외부 의존 4종을 <b>각각 독립 스위치</b>로 둔다.
 *
 * <p>이 과제는 보라미 조회·XVARM 브로커·복호화·STT 네 가지가 서로 다른 시점에 준비된다.
 * 하나라도 막히면 전부 멈추는 구조를 피하려고, 준비된 것부터 REAL 로 돌리고 나머지는 MOCK 으로
 * 둘 수 있게 했다. 커넥터가 쓰는 방식과 같다 —
 * <b>REAL 이면 실서버만 호출하고 실패 시 Mock 으로 빠지지 않는다</b>(Fail-fast).
 * Mock 결과가 실제인 양 섞이면 시연·검증이 전부 무의미해지기 때문이다.</p>
 */
@ConfigurationProperties(prefix = "voice")
public record VoiceProperties(
        @DefaultValue Source source,
        @DefaultValue Broker broker,
        @DefaultValue Sync sync,
        @DefaultValue Decrypt decrypt,
        @DefaultValue Stt stt,
        @DefaultValue Sink sink,
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
            @DefaultValue("") String baseUrl,
            /** 추출 완료를 기다리는 폴링 간격. 브로커는 202 로 받고 상태를 따로 알려준다. */
            @DefaultValue("2000") long pollIntervalMs,
            @DefaultValue("120") int pollTimeoutSec
    ) {}

    /** ESB 가 떨궈 준 파일을 집어오는 구간. */
    public record Sync(
            /** 접견 파일 수신 디렉터리 */
            @DefaultValue("./work/voice_raw/meet") String meetDir,
            /** 전화 파일 수신 디렉터리 */
            @DefaultValue("./work/voice_raw/phone") String phoneDir,
            /** 복호화 산출물 등 작업 공간 */
            @DefaultValue("./work/voice_work") String workDir,
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

    /** 하류 — 비식별 커넥터로 STT 텍스트를 넘긴다. */
    public record Sink(
            @DefaultValue("true") boolean enabled,
            /** 커넥터 주소. K8s 서비스명 기준 {@code http://agent-connector-dp8qbi7xqh:8080} */
            @DefaultValue("") String connectorBaseUrl,
            /** 한 번에 보낼 레코드 수. 텍스트라 가볍지만 커넥터의 gzip 수신 한도를 넘기지 않는다. */
            @DefaultValue("100") int chunkSize,
            @DefaultValue("60") int timeoutSec
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
            @DefaultValue("VOICE_BATCH") String jobId,
            /** 커넥터 헤더의 dataTypeCd. AI-R(비정형) 경로로 태우는 근거가 된다. */
            @DefaultValue("VOICE") String dataTypeCd,
            /** STT 후 복호화 원본을 남길지. 기본은 삭제 — PII 보유를 최소화한다. */
            @DefaultValue("false") boolean retainSourceFile
    ) {}

    public enum SourceMode { MOCK, DIRECT_JDBC, ESB_HTTP2DB }

    public enum BrokerMode { MOCK, REST }

    public enum DecryptMode { SKIP, REAL }

    public enum SttMode { MOCK, NPU }
}
