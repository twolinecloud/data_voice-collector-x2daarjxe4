package egovframework.voice.collector.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 로그 컬렉터 REST 클라이언트 — 수집 이력을 <b>API 로</b> 적재한다.
 *
 * <p><b>R&amp;R</b>: 로그 테이블(kcais T1~T11)의 단일 writer 는 <b>로그 컬렉터</b>다
 * (확정 2026-08-19). 커넥터도 DB 직접 INSERT 를 걷어내고 이 방식으로 바꿨다.
 * 수집 서비스도 자체 로그 테이블을 만들지 않는다.</p>
 *
 * <p><b>이 서비스가 쓰는 경로</b> (base-url 은 context-path {@code /logc} 까지 포함해 주입)</p>
 * <ul>
 *   <li>T1 생성: {@code POST {base}/api/v1/logs/batches} → <b>EXEC_ID 채번</b></li>
 *   <li>T1 종료: {@code PATCH {base}/api/v1/logs/batches/{execId}}</li>
 *   <li>T2 생성: {@code POST {base}/api/v1/logs/batches/{execId}/steps}</li>
 *   <li>T2 종료: {@code PATCH {base}/api/v1/logs/steps/{stepLogId}}</li>
 *   <li><b>T4 적재</b>: {@code POST {base}/api/v1/logs/batches/{execId}/file-procs}</li>
 * </ul>
 *
 * <p><b>정합성</b>: {@code TB_BATCH_EXEC_LOG.SUCCESS_CNT == Σ(T3·T4·T5)} 규칙이 있어
 * <b>파일 1건 = T4 1행</b>을 어기면 배치 전체의 대사가 깨진다.</p>
 *
 * <p><b>방어적 기본값</b>: {@code enabled=false} 이거나 base-url 이 비면 호출 자체를 하지 않는다.
 * 호출이 실패해도 예외를 밖으로 던지지 않는다 — 이력 적재 실패가 수집을 멈추게 하지 않는다.</p>
 *
 * <p><b>왜 전용 RestTemplate 인가</b>: 종료 갱신이 전부 <b>PATCH</b> 인데 기본
 * {@code SimpleClientHttpRequestFactory}(=HttpURLConnection)는 PATCH 를 지원하지 않는다.</p>
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class LogCollectorClient {

    private final ObjectMapper objectMapper;

    private RestTemplate restTemplate;

    @Value("${log-collector.connect-timeout-sec:5}")
    private int connectTimeoutSec;

    @Value("${log-collector.read-timeout-sec:30}")
    private int readTimeoutSec;

    /** context-path({@code /logc})까지 포함해서 주입한다. 예) {@code http://log-collector-a2z96kgyrm:8080/logc} */
    @Value("${log-collector.base-url:}")
    private String baseUrl;

    @Value("${log-collector.enabled:false}")
    private boolean enabled;

    @Value("${log-collector.auth-token:}")
    private String authToken;

    @PostConstruct
    void init() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSec)).build());
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSec));
        RestTemplate rt = new RestTemplate(factory);
        rt.getMessageConverters().removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
        rt.getMessageConverters().add(new MappingJackson2HttpMessageConverter(objectMapper));
        this.restTemplate = rt;
        if (isEnabled()) {
            log.info("[LogCollector] 이력 적재 활성 — base={}", baseUrl);
        } else {
            log.info("[LogCollector] 이력 적재 비활성(enabled={}, base-url={}) — 콘솔 이력만 남긴다",
                    enabled, StringUtils.hasText(baseUrl) ? baseUrl : "(미설정)");
        }
    }

    public boolean isEnabled() {
        return enabled && StringUtils.hasText(baseUrl);
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ── T1 배치 ────────────────────────────────────────────────────────────

    /**
     * T1 배치를 생성하고 <b>컬렉터가 채번한 EXEC_ID</b> 를 돌려준다(중앙 채번).
     *
     * <p>채번 규칙은 컬렉터가 소유한다 — {@code 실행일자(8) + 작업코드(3) + 회차(3)}.
     * 음성은 작업코드 {@code VOC} 로 떨어져야 한다(예: {@code 20260915VOC001}).
     * {@code jobId} 가 어떤 값일 때 {@code VOC} 가 되는지는 컬렉터 규칙이라 확인이 필요하다(계획서 Q4).</p>
     *
     * @return 채번된 execId. 미연동·실패 시 null
     */
    public String createBatch(String jobId, String dataTypeCd, String execTypeCd, String triggerBy) {
        if (!isEnabled()) {
            return null;
        }
        JsonNode result = exchange(HttpMethod.POST, url("/api/v1/logs/batches"),
                new BatchCreateReq(jobId, null, dataTypeCd, null, null,
                        execTypeCd, LocalDateTime.now().withNano(0), triggerBy));
        return (result == null || result.path("execId").isMissingNode())
                ? null : result.path("execId").asText(null);
    }

    /** T1 배치를 종료 상태로 갱신한다(C04: SUCCESS/FAIL/PARTIAL 등). */
    public void finishBatch(String execId, String execStsCd, Integer elapsedSec,
                            Long targetCnt, Long successCnt, Long failCnt, String errMsg) {
        if (!isEnabled() || !StringUtils.hasText(execId)) {
            return;
        }
        exchange(HttpMethod.PATCH, url("/api/v1/logs/batches/" + execId),
                new BatchFinishReq(execStsCd, LocalDateTime.now().withNano(0), elapsedSec,
                        targetCnt, successCnt, failCnt,
                        errMsg == null ? null : "DATA", null, errMsg, errMsg));
    }

    // ── T2 단계 ────────────────────────────────────────────────────────────

    /**
     * T2 단계를 생성한다(상태 RUNNING).
     *
     * @param stepTypeCd 공통코드 C05 — COLLECT / CLEANSE / DEIDENT / STORE / SEND / ANALYZE
     */
    public String createStep(String execId, Short stepSeq, String stepTypeCd) {
        if (!isEnabled() || !StringUtils.hasText(execId)) {
            return null;
        }
        JsonNode result = exchange(HttpMethod.POST, url("/api/v1/logs/batches/" + execId + "/steps"),
                new StepCreateReq(stepSeq, stepTypeCd, LocalDateTime.now().withNano(0)));
        return (result == null || result.path("stepLogId").isMissingNode())
                ? null : result.path("stepLogId").asText(null);
    }

    public void finishStep(String stepLogId, String stepStsCd, Integer elapsedSec,
                           Long inCnt, Long outCnt, Long errCnt, String errStack) {
        if (!isEnabled() || !StringUtils.hasText(stepLogId)) {
            return;
        }
        exchange(HttpMethod.PATCH, url("/api/v1/logs/steps/" + stepLogId),
                new StepFinishReq(stepStsCd, LocalDateTime.now().withNano(0), elapsedSec,
                        inCnt, outCnt, errCnt, errStack));
    }

    // ── T4 음성 파일 처리 이력 ──────────────────────────────────────────────

    /**
     * T4({@code TB_FILE_PROC_LOG}) 를 <b>bulk 적재</b>한다 — 파일 1건 = 1행.
     *
     * <p><b>⚠ 요청 DTO 필드명이 추정이다</b>(계획서 Q4). 컬렉터의 {@code file-procs} API 는
     * 이미 구현되어 있지만 요청 스키마를 확인하지 못했다. T5({@code DeidentSendReq}) 의
     * 필드 작명을 따라 맞춰 두었으니, 실제 스키마를 받으면 이 record 만 고치면 된다.</p>
     *
     * @return 채번된 id 목록. 미적재·실패 시 빈 목록
     */
    public List<Long> createFileProcs(String execId, List<FileProcReq> rows) {
        if (!isEnabled() || rows == null || rows.isEmpty()) {
            return List.of();
        }
        JsonNode result = exchange(HttpMethod.POST,
                url("/api/v1/logs/batches/" + execId + "/file-procs"), rows);
        List<Long> ids = new ArrayList<>();
        if (result != null && result.path("ids").isArray()) {
            result.path("ids").forEach(n -> ids.add(n.asLong()));
        }
        return ids;
    }

    /** 배치 상세(T1 + 단계 + 하위 집계) 조회 — 멱등 판정·정합성 대사의 근거. */
    public JsonNode getBatchDetail(String execId) {
        if (!isEnabled() || !StringUtils.hasText(execId)) {
            return null;
        }
        return exchange(HttpMethod.GET, url("/api/v1/logs/batches/" + execId), null);
    }

    // ── 내부 ───────────────────────────────────────────────────────────────

    private String url(String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + path;
    }

    /**
     * 컬렉터 호출 공통부. 응답 봉투 {@code {success, code, result}} 에서 {@code result} 만 반환한다.
     * 실패 시 예외를 던지지 않고 경고 후 null 을 돌려준다 — 이력 적재가 수집을 막지 않는다.
     */
    private JsonNode exchange(HttpMethod method, String url, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            if (StringUtils.hasText(authToken)) {
                headers.setBearerAuth(authToken);
            }
            ResponseEntity<JsonNode> res = restTemplate.exchange(
                    java.net.URI.create(url), method, new HttpEntity<>(body, headers), JsonNode.class);
            JsonNode env = res.getBody();
            if (env == null) {
                log.warn("[LogCollector] 빈 응답: {} {}", method, url);
                return null;
            }
            if (env.hasNonNull("success") && !env.path("success").asBoolean()) {
                log.warn("[LogCollector] 실패 응답: {} {} — {}", method, url,
                        env.path("error_message").asText(""));
                return null;
            }
            return env.path("result");
        } catch (Exception e) {
            log.warn("[LogCollector] 호출 실패(수집은 계속 진행): {} {} — {}", method, url, e.getMessage());
            return null;
        }
    }

    // ── 요청 DTO ───────────────────────────────────────────────────────────

    public record BatchCreateReq(String jobId, String jobNm, String dataTypeCd,
                                 LocalDateTime targetFromDtm, LocalDateTime targetToDtm,
                                 String execTypeCd, LocalDateTime startDtm, String triggerBy) {}

    public record BatchFinishReq(String execStsCd, LocalDateTime endDtm, Integer elapsedSec,
                                 Long targetCnt, Long successCnt, Long failCnt,
                                 String errTypeCd, String errCd, String errMsg, String errStack) {}

    public record StepCreateReq(Short stepSeq, String stepTypeCd, LocalDateTime startDtm) {}

    public record StepFinishReq(String stepStsCd, LocalDateTime endDtm, Integer elapsedSec,
                                Long inCnt, Long outCnt, Long errCnt, String errStack) {}

    /**
     * T4 적재 요청 1건 — <b>필드명 추정</b>(Q4).
     *
     * @param inmatePid   비식별 수용자ID. 교정번호를 그대로 넣지 않는다.
     * @param fileTypeCd  MEET / PHONE
     * @param fileNm      파일명. 경로는 넣지 않는다(내부 구조 노출 방지)
     * @param fileSize    byte
     * @param procStsCd   SUCCESS / FAIL
     * @param sttCharCnt  STT 결과 글자 수
     * @param errTypeCd   실패 시 오류 유형(ERR_TYPE_CD)
     * @param errStack    실패 사유. PII 가 섞이지 않도록 원문을 넣지 않는다
     */
    public record FileProcReq(String inmatePid, String fileTypeCd, String fileNm, Long fileSize,
                              String procStsCd, Integer sttCharCnt, String errTypeCd, String errStack) {}
}
